package com.dedeleledede;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class GraphViewer extends JPanel {
    private final Graph graph;
    private final List<String> labels;
    private final WebCrawler crawler;

    private final Map<Integer, Point> positions = new HashMap<>();
    private final Map<Integer, Integer> nodeRadius = new HashMap<>();
    private final Map<Integer, Integer> referenceCount = new HashMap<>();
    private final Map<Integer, List<Integer>> incoming = new HashMap<>();
    private final Map<Integer, Integer> hubOf = new HashMap<>();
    private final Map<Integer, Integer> bfsDepth = new HashMap<>();
    private final Map<Integer, Integer> outDegree = new HashMap<>();


    private final List<int[]> cachedEdges = new ArrayList<>();
    private final List<Integer> hubs = new ArrayList<>();

    private boolean showAllEdges = false;

    private int hovered = -1;
    private int primaryRoot = -1;
    private int maxReferenceCount = 1;
    private String primaryRootUrl = null;

    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;

    private Point lastDrag;

    public GraphViewer(Graph graph, List<String> labels, WebCrawler crawler) {
        this.graph = graph;
        this.labels = labels;
        this.crawler = crawler;

        setPreferredSize(new Dimension(1200, 800));
        layoutNodes();
        installMouseControls();

        setFocusable(true);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                javax.swing.KeyStroke.getKeyStroke('E'),
                "toggleEdges"
        );

        getActionMap().put("toggleEdges", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showAllEdges = !showAllEdges;
                repaint();
            }
        });
    }

    private void installMouseControls() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int newHovered = findNodeAt(e.getPoint());

                if (newHovered != hovered) {
                    hovered = newHovered;
                    repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    lastDrag = e.getPoint();
                }

                if (SwingUtilities.isRightMouseButton(e)) {
                    showNodeMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastDrag = null;

                if (SwingUtilities.isRightMouseButton(e)) {
                    showNodeMenu(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDrag == null) return;

                int dx = e.getX() - lastDrag.x;
                int dy = e.getY() - lastDrag.y;

                offsetX += dx;
                offsetY += dy;

                lastDrag = e.getPoint();
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double oldScale = scale;

                if (e.getWheelRotation() < 0) {
                    scale *= 1.1;
                } else {
                    scale *= 0.9;
                }

                scale = Math.max(0.1, Math.min(5.0, scale));

                double factor = scale / oldScale;

                offsetX = e.getX() - (e.getX() - offsetX) * factor;
                offsetY = e.getY() - (e.getY() - offsetY) * factor;

                repaint();
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private void showNodeMenu(MouseEvent e) {
        int node = findNodeAt(e.getPoint());
        if (node == -1) return;

        JPopupMenu menu = new JPopupMenu();

        JMenuItem crawl = new JMenuItem("Crawl from this node");
        JMenuItem recrawl = new JMenuItem("Recrawl this node");
        JMenuItem uncrawl = new JMenuItem("Uncrawl branch under this node");

        crawl.addActionListener(event -> {
            int depth = askDepth("Crawl depth");
            runInBackground(() -> crawler.crawlFromNode(node, depth));
        });

        recrawl.addActionListener(event -> {
            int depth = askDepth("Recrawl depth");
            runInBackground(() -> crawler.recrawlFromNode(node, depth));
        });

        uncrawl.addActionListener(_ -> runInBackground(() -> crawler.uncrawlFromNode(node)));

        menu.add(crawl);
        menu.add(recrawl);
        menu.add(uncrawl);

        menu.show(this, e.getX(), e.getY());
    }

    private int askDepth(String title) {
        String result = JOptionPane.showInputDialog(this, title, String.valueOf(2));

        if (result == null || result.isBlank()) {
            return 2;
        }

        try {
            return Integer.parseInt(result.trim());
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private void runInBackground(Runnable action) {
        new Thread(() -> {
            action.run();

            SwingUtilities.invokeLater(this::refreshLayout);
        }).start();
    }

    private void refreshLayout() {
        positions.clear();
        layoutNodes();
        repaint();
    }

    private int findNodeAt(Point screenPoint) {
        Point worldPoint = screenToWorld(screenPoint);

        for (int v = 0; v < labels.size(); v++) {
            Point p = positions.get(v);
            int r = nodeRadius.getOrDefault(v, 6);

            if (p != null && p.distance(worldPoint) < r + 4) {
                return v;
            }
        }

        return -1;
    }

    private Point screenToWorld(Point p) {
        int x = (int) ((p.x - offsetX) / scale);
        int y = (int) ((p.y - offsetY) / scale);
        return new Point(x, y);
    }

    private Point worldToScreen(Point p) {
        int x = (int) (p.x * scale + offsetX);
        int y = (int) (p.y * scale + offsetY);
        return new Point(x, y);
    }

    private void rebuildGraphCaches() {
        incoming.clear();
        referenceCount.clear();
        nodeRadius.clear();
        cachedEdges.clear();
        outDegree.clear();

        maxReferenceCount = 1;

        for (int v = 0; v < labels.size(); v++) {
            if (labels.get(v) == null) continue;

            int outgoing = 0;

            for (int w : graph.adj(v)) {
                if (w < 0 || w >= labels.size()) continue;
                if (labels.get(w) == null) continue;

                outgoing++;

                cachedEdges.add(new int[]{v, w});

                int refs = referenceCount.getOrDefault(w, 0) + 1;
                referenceCount.put(w, refs);
                maxReferenceCount = Math.max(maxReferenceCount, refs);

                incoming.computeIfAbsent(w, _ -> new ArrayList<>()).add(v);
            }

            outDegree.put(v, outgoing);
        }

        for (int v = 0; v < labels.size(); v++) {
            if (labels.get(v) == null) continue;

            int refs = referenceCount.getOrDefault(v, 0);
            nodeRadius.put(v, radiusForReferences(refs));
        }
    }

    private int choosePrimaryRoot() {
        Map<Integer, Boolean> visited = new HashMap<>();

        List<Integer> bestComponent = new ArrayList<>();

        for (int start = 0; start < labels.size(); start++) {
            if (labels.get(start) == null) continue;
            if (visited.containsKey(start)) continue;

            boolean connected =
                    outDegree.getOrDefault(start, 0) > 0 ||
                            referenceCount.getOrDefault(start, 0) > 0;

            if (!connected) continue;

            List<Integer> component = new ArrayList<>();
            Queue<Integer> queue = new LinkedList<>();

            queue.add(start);
            visited.put(start, true);

            while (!queue.isEmpty()) {
                int v = queue.poll();
                component.add(v);

                for (int w : graph.adj(v)) {
                    if (w < 0 || w >= labels.size()) continue;
                    if (labels.get(w) == null) continue;

                    if (!visited.containsKey(w)) {
                        visited.put(w, true);
                        queue.add(w);
                    }
                }

                List<Integer> parents = incoming.get(v);

                if (parents != null) {
                    for (int parent : parents) {
                        if (parent < 0 || parent >= labels.size()) continue;
                        if (labels.get(parent) == null) continue;

                        if (!visited.containsKey(parent)) {
                            visited.put(parent, true);
                            queue.add(parent);
                        }
                    }
                }
            }

            if (component.size() > bestComponent.size()) {
                bestComponent = component;
            }
        }

        if (bestComponent.isEmpty()) {
            return -1;
        }

        int bestRoot = -1;

        for (int v : bestComponent) {
            if (isSiteRoot(labels.get(v))) {
                if (bestRoot == -1 || v < bestRoot) {
                    bestRoot = v;
                }
            }
        }

        if (bestRoot != -1) {
            return bestRoot;
        }

        for (int v : bestComponent) {
            if (bestRoot == -1 || v < bestRoot) {
                bestRoot = v;
            }
        }

        return bestRoot;
    }

    private boolean isSiteRoot(String url) {
        String value = url;

        value = value.replaceFirst("(?i)^https?://", "");

        int query = value.indexOf('?');
        if (query != -1) {
            value = value.substring(0, query);
        }

        int slash = value.indexOf('/');

        if (slash == -1) {
            return true;
        }

        String path = value.substring(slash);

        return path.equals("/") || path.isBlank();
    }

    private void computeBfsDepths(int root) {
        bfsDepth.clear();
        int maxBfsDepth = 0;

        if (root == -1) return;

        Queue<Integer> queue = new LinkedList<>();

        queue.add(root);
        bfsDepth.put(root, 0);

        while (!queue.isEmpty()) {
            int v = queue.poll();
            int d = bfsDepth.get(v);

            maxBfsDepth = Math.max(maxBfsDepth, d);

            for (int w : graph.adj(v)) {
                if (w < 0 || w >= labels.size()) continue;
                if (labels.get(w) == null) continue;

                if (!bfsDepth.containsKey(w)) {
                    bfsDepth.put(w, d + 1);
                    queue.add(w);
                }
            }
        }
    }

    private int radiusForReferences(int refs) {
        if (refs <= 0) return 3;

        if (maxReferenceCount <= 1) {
            return 7;
        }

        double ratio = refs / (double) maxReferenceCount;

        int radius = 4 + (int) Math.round(Math.pow(ratio, 0.42) * 72);

        return Math.min(radius, 76);
    }

    private boolean isVisible(Point s, int r) {
        return !(s.x + r < -100 || s.x - r > getWidth() + 100
                || s.y + r < -100 || s.y - r > getHeight() + 100);
    }

    private boolean isSegmentVisible(Point a, Point b) {
        if (a.x < -100 && b.x < -100) return false;
        if (a.y < -100 && b.y < -100) return false;
        if (a.x > getWidth() + 100 && b.x > getWidth() + 100) return false;
        return a.y <= getHeight() + 100 || b.y <= getHeight() + 100;
    }

    private void layoutNodes() {
        positions.clear();
        hubOf.clear();
        hubs.clear();

        primaryRoot = -1;
        primaryRootUrl = null;

        rebuildGraphCaches();

        primaryRoot = choosePrimaryRoot();

        if (primaryRoot != -1) {
            primaryRootUrl = labels.get(primaryRoot);
        }

        computeBfsDepths(primaryRoot);

        List<Integer> candidates = new ArrayList<>();

        for (int v = 0; v < labels.size(); v++) {
            if (labels.get(v) == null) continue;

            boolean hasOutgoing = graph.adj(v).iterator().hasNext();
            boolean hasIncoming = referenceCount.getOrDefault(v, 0) > 0;

            if (!hasOutgoing && !hasIncoming) continue;

            candidates.add(v);
        }

        candidates.sort((a, b) -> {
            if (a == primaryRoot) return -1;
            if (b == primaryRoot) return 1;

            int refsA = referenceCount.getOrDefault(a, 0);
            int refsB = referenceCount.getOrDefault(b, 0);

            if (refsA != refsB) {
                return Integer.compare(refsB, refsA);
            }

            return Integer.compare(a, b);
        });

        int maxHubs = Math.min(18, candidates.size());

        for (int i = 0; i < maxHubs; i++) {
            int hub = candidates.get(i);
            hubs.add(hub);
            hubOf.put(hub, hub);
        }

        int centerX = 900;
        int centerY = 180;

        if (primaryRoot != -1) {
            positions.put(primaryRoot, new Point(centerX, 80));
        }

        int hubSpacingX = 320;
        int hubSpacingY = 260;
        int hubsPerRow = 4;

        int placedHubCount = 0;

        for (int hub : hubs) {
            if (hub == primaryRoot) continue;

            int row = placedHubCount / hubsPerRow;
            int col = placedHubCount % hubsPerRow;

            int rowCount = Math.min(hubsPerRow, hubs.size() - 1 - row * hubsPerRow);
            int startX = centerX - ((rowCount - 1) * hubSpacingX) / 2;

            int x = startX + col * hubSpacingX;
            int y = centerY + row * hubSpacingY;

            positions.put(hub, new Point(x, y));
            placedHubCount++;
        }

        Map<Integer, List<Integer>> satellites = new HashMap<>();

        for (int v : candidates) {
            if (hubOf.containsKey(v)) continue;

            int hub = chooseHubForNode(v);

            if (hub == -1) {
                hub = primaryRoot;
            }

            if (hub == -1) continue;

            hubOf.put(v, hub);
            satellites.computeIfAbsent(hub, _ -> new ArrayList<>()).add(v);
        }

        for (int hub : hubs) {
            List<Integer> nodes = satellites.get(hub);

            if (nodes == null || nodes.isEmpty()) continue;

            nodes.sort((a, b) -> {
                int refsA = referenceCount.getOrDefault(a, 0);
                int refsB = referenceCount.getOrDefault(b, 0);

                if (refsA != refsB) {
                    return Integer.compare(refsB, refsA);
                }

                return Integer.compare(a, b);
            });

            Point hubPoint = positions.get(hub);
            if (hubPoint == null) continue;

            placeSatellitesAroundHub(hub, nodes, hubPoint);
            placeLeafNodesNearParents();
        }
    }

    private void placeLeafNodesNearParents() {
        Map<Integer, List<Integer>> leavesByParent = new HashMap<>();

        for (int v = 0; v < labels.size(); v++) {
            if (labels.get(v) == null) continue;
            if (!positions.containsKey(v)) continue;
            if (hubs.contains(v)) continue;
            if (v == primaryRoot) continue;

            List<Integer> parents = incoming.get(v);

            if (parents == null || parents.size() != 1) continue;
            if (outDegree.getOrDefault(v, 0) > 0) continue;

            int parent = parents.getFirst();

            if (!positions.containsKey(parent)) continue;

            leavesByParent.computeIfAbsent(parent, _ -> new ArrayList<>()).add(v);
        }

        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

        for (Map.Entry<Integer, List<Integer>> entry : leavesByParent.entrySet()) {
            int parent = entry.getKey();
            List<Integer> leaves = entry.getValue();

            Point parentPoint = positions.get(parent);

            if (parentPoint == null) continue;

            leaves.sort(Integer::compare);

            int parentRadius = nodeRadius.getOrDefault(parent, 8);

            for (int i = 0; i < leaves.size(); i++) {
                int node = leaves.get(i);
                int r = nodeRadius.getOrDefault(node, 6);

                double angle = i * goldenAngle;
                double distance = parentRadius + r + 55 + Math.sqrt(i) * 22;

                Point candidate = positions.get(node);

                for (int attempt = 0; attempt < 100; attempt++) {
                    int x = (int) Math.round(parentPoint.x + Math.cos(angle) * distance);
                    int y = (int) Math.round(parentPoint.y + Math.sin(angle) * distance);

                    candidate = new Point(x, y);

                    if (!overlapsExisting(candidate, r, node)) {
                        break;
                    }

                    angle += 0.6;
                    distance += 10;
                }

                positions.put(node, candidate);
            }
        }
    }

    private Color colorForNode(int node) {
        if (node == primaryRoot) {
            return new Color(255, 200, 40);
        }

        int depth = bfsDepth.getOrDefault(node, -1);

        if (depth < 0) {
            return new Color(140, 140, 140);
        }

        return colorForDepth(depth);
    }

    private int chooseHubForNode(int node) {
        List<Integer> parents = incoming.get(node);

        if (parents == null || parents.isEmpty()) {
            return primaryRoot;
        }

        int bestHub = -1;
        int bestScore = -1;

        for (int parent : parents) {
            int candidateHub;

            if (hubOf.containsKey(parent)) {
                candidateHub = hubOf.get(parent);
            } else {
                candidateHub = closestHubToParent(parent);
            }

            if (candidateHub == -1) continue;

            int score = referenceCount.getOrDefault(parent, 0)
                    + referenceCount.getOrDefault(candidateHub, 0);

            if (score > bestScore) {
                bestScore = score;
                bestHub = candidateHub;
            }
        }

        return bestHub;
    }

    private int closestHubToParent(int parent) {
        List<Integer> parents = incoming.get(parent);

        if (parents != null) {
            for (int p : parents) {
                if (hubOf.containsKey(p)) {
                    return hubOf.get(p);
                }
            }
        }

        return primaryRoot;
    }

    private void placeSatellitesAroundHub(int hub, List<Integer> nodes, Point hubPoint) {
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

        int hubRadius = nodeRadius.getOrDefault(hub, 12);
        int baseDistance = hubRadius + 70;

        for (int i = 0; i < nodes.size(); i++) {
            int node = nodes.get(i);
            int r = nodeRadius.getOrDefault(node, 6);

            double angle = i * goldenAngle;
            double distance = baseDistance + Math.sqrt(i) * 34;

            Point candidate = null;

            for (int attempt = 0; attempt < 120; attempt++) {
                int x = (int) Math.round(hubPoint.x + Math.cos(angle) * distance);
                int y = (int) Math.round(hubPoint.y + Math.sin(angle) * distance);

                candidate = new Point(x, y);

                if (!overlapsExisting(candidate, r, node)) {
                    break;
                }

                angle += 0.55;
                distance += 12;
            }

            positions.put(node, candidate);
        }
    }

    private boolean overlapsExisting(Point candidate, int radius, int ignoredNode) {
        for (Map.Entry<Integer, Point> entry : positions.entrySet()) {
            int other = entry.getKey();

            if (other == ignoredNode) continue;

            Point p = entry.getValue();
            int otherRadius = nodeRadius.getOrDefault(other, 6);

            double minDistance = radius + otherRadius + 10;

            if (candidate.distance(p) < minDistance) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (showAllEdges && scale >= 0.18) {
            g.setColor(new Color(0, 0, 0, 25));

            for (int[] edge : cachedEdges) {
                int v = edge[0];
                int w = edge[1];

                Point p1 = positions.get(v);
                Point p2 = positions.get(w);

                if (p1 == null || p2 == null) continue;

                Point s1 = worldToScreen(p1);
                Point s2 = worldToScreen(p2);

                if (!isSegmentVisible(s1, s2)) continue;

                g.drawLine(s1.x, s1.y, s2.x, s2.y);
            }
        }

        for (int v = 0; v < labels.size(); v++) {
            Point p = positions.get(v);
            if (p == null) continue;

            int worldRadius = nodeRadius.getOrDefault(v, 6);
            int screenRadius = Math.max(2, (int) Math.round(worldRadius * scale));

            Point s = worldToScreen(p);

            if (!isVisible(s, screenRadius)) continue;

            if (v == primaryRoot) {
                g.setColor(new Color(255, 200, 40));
                g.fillOval(
                        s.x - screenRadius - 4,
                        s.y - screenRadius - 4,
                        (screenRadius + 4) * 2,
                        (screenRadius + 4) * 2
                );
            }

            if (v == hovered) {
                g.setColor(Color.RED);
            } else {
                g.setColor(colorForNode(v));
            }

            g.fillOval(
                    s.x - screenRadius,
                    s.y - screenRadius,
                    screenRadius * 2,
                    screenRadius * 2
            );

            g.setColor(Color.BLACK);
            g.drawOval(
                    s.x - screenRadius,
                    s.y - screenRadius,
                    screenRadius * 2,
                    screenRadius * 2
            );

            if (v == primaryRoot) {
                g.drawString("START", s.x + screenRadius + 6, s.y - screenRadius - 4);
            }
        }

        drawHoverEdges(g);

        if (primaryRootUrl != null) {
            String rootText = "START URL: " + primaryRootUrl;

            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(rootText);

            g.setColor(Color.WHITE);
            g.fillRect(10, 10, w + 12, 24);

            g.setColor(Color.BLACK);
            g.drawRect(10, 10, w + 12, 24);
            g.drawString(rootText, 16, 27);
        }

        if (hovered != -1) {
            String text = labels.get(hovered)
                    + " | referenced by "
                    + referenceCount.getOrDefault(hovered, 0)
                    + " page(s)"
                    + (hovered == primaryRoot ? " | START URL" : "");

            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text);

            g.setColor(Color.WHITE);
            g.fillRect(10, getHeight() - 40, w + 10, 25);

            g.setColor(Color.BLACK);
            g.drawRect(10, getHeight() - 40, w + 10, 25);
            g.drawString(text, 15, getHeight() - 20);
        }
    }

    private Color colorForDepth(int depth) {
        Color[] colors = {
                new Color(30, 120, 220),
                new Color(30, 170, 160),
                new Color(90, 180, 90),
                new Color(190, 160, 45),
                new Color(210, 110, 55),
                new Color(170, 90, 190),
                new Color(90, 110, 210)
        };

        return colors[depth % colors.length];
    }

    private int screenRadiusForNode(int node) {
        int worldRadius = nodeRadius.getOrDefault(node, 6);
        return Math.max(2, (int) Math.round(worldRadius * scale));
    }

    private Point edgePoint(Point center, Point toward, int radius, int padding) {
        double dx = toward.x - center.x;
        double dy = toward.y - center.y;

        double length = Math.sqrt(dx * dx + dy * dy);

        if (length == 0) {
            return center;
        }

        double ux = dx / length;
        double uy = dy / length;

        int x = (int) Math.round(center.x + ux * (radius + padding));
        int y = (int) Math.round(center.y + uy * (radius + padding));

        return new Point(x, y);
    }

    private void drawDirectedEdge(
            Graphics2D g2,
            int fromNode,
            int toNode,
            Color color,
            boolean markStart
    ) {
        Point fromWorld = positions.get(fromNode);
        Point toWorld = positions.get(toNode);

        if (fromWorld == null || toWorld == null) return;

        Point fromCenter = worldToScreen(fromWorld);
        Point toCenter = worldToScreen(toWorld);

        int fromRadius = screenRadiusForNode(fromNode);
        int toRadius = screenRadiusForNode(toNode);

        Point from = edgePoint(fromCenter, toCenter, fromRadius, 4);
        Point to = edgePoint(toCenter, fromCenter, toRadius, 12);

        if (from.distance(to) < 8) return;

        g2.setColor(color);
        g2.drawLine(from.x, from.y, to.x, to.y);

        double angle = Math.atan2(to.y - from.y, to.x - from.x);

        int arrowLength = 15;

        int x1 = (int) Math.round(to.x - arrowLength * Math.cos(angle - Math.PI / 7));
        int y1 = (int) Math.round(to.y - arrowLength * Math.sin(angle - Math.PI / 7));

        int x2 = (int) Math.round(to.x - arrowLength * Math.cos(angle + Math.PI / 7));
        int y2 = (int) Math.round(to.y - arrowLength * Math.sin(angle + Math.PI / 7));

        g2.drawLine(to.x, to.y, x1, y1);
        g2.drawLine(to.x, to.y, x2, y2);

        if (markStart) {
            g2.drawOval(
                    fromCenter.x - fromRadius - 5,
                    fromCenter.y - fromRadius - 5,
                    (fromRadius + 5) * 2,
                    (fromRadius + 5) * 2
            );
        }

        g2.drawOval(
                toCenter.x - toRadius - 6,
                toCenter.y - toRadius - 6,
                (toRadius + 6) * 2,
                (toRadius + 6) * 2
        );

        g2.drawOval(
                toCenter.x - toRadius - 12,
                toCenter.y - toRadius - 12,
                (toRadius + 12) * 2,
                (toRadius + 12) * 2
        );
    }

    private void drawHoverEdges(Graphics g) {
        if (hovered == -1) return;

        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        g2.setStroke(new BasicStroke(
                2.8f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
        ));

        for (int w : graph.adj(hovered)) {
            if (!positions.containsKey(w)) continue;

            drawDirectedEdge(
                    g2,
                    hovered,
                    w,
                    new Color(220, 40, 40, 220),
                    false
            );
        }

        List<Integer> parents = incoming.get(hovered);

        if (parents != null) {
            for (int parent : parents) {
                if (!positions.containsKey(parent)) continue;

                drawDirectedEdge(
                        g2,
                        parent,
                        hovered,
                        new Color(0, 130, 70, 220),
                        true
                );
            }
        }

        g2.dispose();
    }

    public static void show(Graph graph, List<String> labels, WebCrawler crawler) {
        JFrame frame = new JFrame("Web Graph");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new GraphViewer(graph, labels, crawler));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}