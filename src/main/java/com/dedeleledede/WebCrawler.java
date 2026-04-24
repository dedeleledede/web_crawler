package com.dedeleledede;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebCrawler {
    private final Map<String, Integer> urlToId = new ConcurrentHashMap<>();
    private final List<String> idToUrl = Collections.synchronizedList(new ArrayList<>());
    private final Graph graph = new Graph(1024);

    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    private final Set<Integer> processedIds = ConcurrentHashMap.newKeySet();

    private final HttpClient client;
    private final RobotsTxt robots;
    private final GraphStore store;

    private final Pattern hrefPattern =
            Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']");

    private boolean sameHostOnly = true;
    private String rootHost;

    public WebCrawler() {
        this("crawler-data");
    }

    public WebCrawler(String folder) {
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        robots = new RobotsTxt(client);
        store = new GraphStore(folder);
        store.open();

        loadSavedGraph();
    }

    private void loadSavedGraph() {
        List<String> savedNodes = store.loadNodes();

        for (int i = 0; i < savedNodes.size(); i++) {
            String url = savedNodes.get(i);
            if (url == null) continue;

            while (idToUrl.size() <= i) {
                idToUrl.add(null);
            }

            idToUrl.set(i, url);
            urlToId.put(url, i);
            graph.ensureVertex(i);
        }

        for (int[] edge : store.loadEdges()) {
            graph.addEdge(edge[0], edge[1]);
        }

        processedIds.addAll(store.loadProcessed());

        for (int id : processedIds) {
            if (id >= 0 && id < idToUrl.size() && idToUrl.get(id) != null) {
                processed.add(idToUrl.get(id));
            }
        }
    }

    public void bfs(String startUrl, int maxDepth) {
        crawl(startUrl, maxDepth, 8, true, false);
    }

    public void crawlOnly(String startUrl, int maxDepth, int threads) {
        crawl(startUrl, maxDepth, threads, false, false);
    }

    public void crawlAndView(String startUrl, int maxDepth, int threads) {
        crawl(startUrl, maxDepth, threads, true, false);
    }

    public void viewOnly() {
        GraphViewer.show(graph, idToUrl, this);
    }

    private void crawl(String startUrl, int maxDepth, int threads, boolean showViewer, boolean forceStart) {
        String normalizedStart = normalizeUrl(startUrl, startUrl);
        if (normalizedStart == null) return;

        rootHost = hostOf(normalizedStart);
        getId(normalizedStart);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Phaser phaser = new Phaser(1);

        schedule(pool, phaser, normalizedStart, 0, maxDepth, forceStart);

        phaser.arriveAndAwaitAdvance();
        pool.shutdown();

        if (showViewer) {
            GraphViewer.show(graph, idToUrl, this);
        }
    }

    private void schedule(
            ExecutorService pool,
            Phaser phaser,
            String url,
            int depth,
            int maxDepth,
            boolean force
    ) {
        if (depth > maxDepth) return;

        if (!force && processed.contains(url)) {
            return;
        }

        if (!scheduled.add(url)) {
            return;
        }

        phaser.register();

        pool.submit(() -> {
            try {
                crawlOne(pool, phaser, url, depth, maxDepth);
            } finally {
                phaser.arriveAndDeregister();
            }
        });
    }

    private void crawlOne(
            ExecutorService pool,
            Phaser phaser,
            String url,
            int depth,
            int maxDepth
    ) {
        int v = getId(url);

        System.out.println("[" + depth + "] " + url);

        if (depth >= maxDepth) {
            return;
        }

        if (!robots.isAllowed(url)) {
            processed.add(url);
            processedIds.add(v);
            store.writeProcessed(v);
            System.out.println("Blocked by robots.txt: " + url);
            return;
        }

        for (String link : getLinks(url)) {
            if (sameHostOnly && !hostOf(link).equals(rootHost)) {
                continue;
            }

            int w = getId(link);

            graph.addEdge(v, w);
            store.writeEdge(v, w);

            schedule(pool, phaser, link, depth + 1, maxDepth, false);
        }

        processed.add(url);
        processedIds.add(v);
        store.writeProcessed(v);
    }

    private List<String> getLinks(String urlString) {
        List<String> links = new ArrayList<>();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .header("User-Agent", "DedeCrawler")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return links;
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("");

            if (!contentType.toLowerCase().contains("text/html")) {
                return links;
            }

            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            StringBuilder html = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append('\n');
            }

            Matcher matcher = hrefPattern.matcher(html);

            while (matcher.find()) {
                String rawLink = matcher.group(1);
                String normalized = normalizeUrl(urlString, rawLink);

                if (normalized != null) {
                    links.add(normalized);
                }
            }

        } catch (Exception ignored) {}

        return links;
    }

    private String normalizeUrl(String baseUrl, String rawUrl) {
        try {
            URI base = URI.create(baseUrl);
            URI resolved = base.resolve(rawUrl).normalize();

            String scheme = resolved.getScheme();
            if (scheme == null) return null;

            scheme = scheme.toLowerCase();

            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }

            URI clean = new URI(
                    scheme,
                    resolved.getUserInfo(),
                    resolved.getHost(),
                    resolved.getPort(),
                    resolved.getPath(),
                    resolved.getQuery(),
                    null
            );

            return clean.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private synchronized int getId(String url) {
        Integer existing = urlToId.get(url);
        if (existing != null) {
            return existing;
        }

        int id = idToUrl.size();

        urlToId.put(url, id);
        idToUrl.add(url);

        graph.ensureVertex(id);
        store.writeNode(id, url);

        return id;
    }

    public void crawlFromNode(int nodeId, int depth) {
        String url = getUrl(nodeId);
        if (url == null) return;

        crawl(url, depth, 8, false, false);
    }

    public void recrawlFromNode(int nodeId, int depth) {
        String url = getUrl(nodeId);
        if (url == null) return;

        graph.removeEdgesFrom(nodeId);

        processed.remove(url);
        processedIds.remove(nodeId);

        store.rewriteAll(idToUrl, graph, processedIds);

        crawl(url, depth, 8, false, true);
    }

    public void uncrawlFromNode(int nodeId) {
        Set<Integer> branch = collectBranch(nodeId);

        for (int id : branch) {
            graph.removeEdgesFrom(id);

            String url = getUrl(id);
            if (url != null) {
                processed.remove(url);
            }

            processedIds.remove(id);
        }

        store.rewriteAll(idToUrl, graph, processedIds);
    }

    private Set<Integer> collectBranch(int start) {
        Set<Integer> result = new HashSet<>();
        Queue<Integer> queue = new ArrayDeque<>();

        queue.add(start);
        result.add(start);

        while (!queue.isEmpty()) {
            int v = queue.poll();

            for (int w : graph.adj(v)) {
                if (result.add(w)) {
                    queue.add(w);
                }
            }
        }

        return result;
    }

    private String getUrl(int nodeId) {
        if (nodeId < 0 || nodeId >= idToUrl.size()) return null;
        return idToUrl.get(nodeId);
    }
}