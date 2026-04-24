package com.dedeleledede;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RobotsTxt {
    private final HttpClient client;
    private final Map<String, Rules> cache = new ConcurrentHashMap<>();
    private final String userAgent = "DedeCrawler";

    public RobotsTxt(HttpClient client) {
        this.client = client;
    }

    public boolean isAllowed(String url) {
        try {
            URI uri = URI.create(url);
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            Rules rules = cache.computeIfAbsent(origin, this::fetchRules);

            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";

            return rules.isAllowed(path);

        } catch (Exception e) {
            return false;
        }
    }

    private Rules fetchRules(String origin) {
        Rules rules = new Rules();

        try {
            URI robotsUri = URI.create(origin + "/robots.txt");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(robotsUri)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return rules;
            }

            boolean applies = false;

            for (String rawLine : response.body().split("\n")) {
                String line = rawLine.strip();

                int comment = line.indexOf("#");
                if (comment != -1) {
                    line = line.substring(0, comment).strip();
                }

                if (line.isBlank()) continue;

                int colon = line.indexOf(":");
                if (colon == -1) continue;

                String key = line.substring(0, colon).strip().toLowerCase();
                String value = line.substring(colon + 1).strip();

                if (key.equals("user-agent")) {
                    String agent = value.toLowerCase();
                    applies = agent.equals("*") || agent.equals(userAgent.toLowerCase());
                }

                if (applies && key.equals("allow")) {
                    if (!value.isBlank()) {
                        rules.add(value, true);
                    }
                }

                if (applies && key.equals("disallow")) {
                    if (!value.isBlank()) {
                        rules.add(value, false);
                    }
                }
            }

        } catch (Exception ignored) {
            return rules;
        }

        return rules;
    }

    private static class Rules {
        private final List<Rule> rules = new ArrayList<>();

        void add(String path, boolean allow) {
            rules.add(new Rule(path, allow));
        }

        boolean isAllowed(String path) {
            Rule best = null;

            for (Rule rule : rules) {
                if (path.startsWith(rule.path)) {
                    if (best == null || rule.path.length() > best.path.length()) {
                        best = rule;
                    }
                }
            }

            return best == null || best.allow;
        }
    }

    private static class Rule {
        private final String path;
        private final boolean allow;

        Rule(String path, boolean allow) {
            this.path = path;
            this.allow = allow;
        }
    }
}