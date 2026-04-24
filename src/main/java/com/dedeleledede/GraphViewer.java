package com.dedeleledede;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Collections;
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

    private int hovered = -1;

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
    }

    private void installMouseControls() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hovered = findNodeAt(e.getPoint());
                repaint();
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
            int depth = askDepth("Crawl depth", 2);
            runInBackground(() -> crawler.crawlFromNode(node, depth));
        });

        recrawl.addActionListener(event -> {
            int depth = askDepth("Recrawl depth", 2);
            runInBackground(() -> crawler.recrawlFromNode(node, depth));
        });

        uncrawl.addActionListener(event -> {
            runInBackground(() -> crawler.uncrawlFromNode(node));
        });

        menu.add(crawl);
        menu.add(recrawl);
        menu.add(uncrawl);

        menu.show(this, e.getX(), e.getY());
    }

    private int askDepth(String title, int defaultValue) {
        String result = JOptionPane.showInputDialog(this, title, String.valueOf(defaultValue));

        if (result == null || result.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(result.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void runInBackground(Runnable action) {
        new Thread(() -> {
            action.run();

            SwingUtilities.invokeLater(() -> {
                refreshLayout();
            });
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

    private void layoutNodes() {
        positions.clear();
        nodeRadius.clear();
        referenceCount.clear();

        for (int v = 0; v < labels.size(); v++) {
            for (int w : graph.adj(v)) {
                if (w < 0 || w >= labels.size()) continue;
                if (labels.get(w) == null) continue;

                referenceCount.put(w, referenceCount.getOrDefault(w, 0) + 1);
            }
        }

        for (int v = 0; v < labels.size(); v++) {
            if (labels.get(v) == null) continue;

            int refs = referenceCount.getOrDefault(v, 0);
            nodeRadius.put(v, radiusForReferences(refs));
        }

        Map<Integer, Integer> depthMap = new HashMap<>();
        Map<Integer, Integer> componentMap = new HashMap<>();

        int component = 0;

        for (int start = 0; start < labels.size(); start++) {
            if (labels.get(start) == null) continue;
            if (depthMap.containsKey(start)) continue;

            boolean hasOutgoing = graph.adj(start).iterator().hasNext();
            boolean hasIncoming = referenceCount.getOrDefault(start, 0) > 0;

            if (!hasOutgoing && !hasIncoming) {
                continue;
            }

            Queue<Integer> queue = new LinkedList<>();
            queue.add(start);

            depthMap.put(start, 0);
            componentMap.put(start, component);

            while (!queue.isEmpty()) {
                int v = queue.poll();
                int d = depthMap.get(v);

                for (int w : graph.adj(v)) {
                    if (w < 0 || w >= labels.size()) continue;
                    if (labels.get(w) == null) continue;

                    if (!depthMap.containsKey(w)) {
                        depthMap.put(w, d + 1);
                        componentMap.put(w, component);
                        queue.add(w);
                    }
                }
            }

            component++;
        }

        Map<Integer, Map<Integer, List<Integer>>> components = new HashMap<>();

        for (int v : depthMap.keySet()) {
            int c = componentMap.get(v);
            int d = depthMap.get(v);

            components
                    .computeIfAbsent(c, k -> new HashMap<>())
                    .computeIfAbsent(d, k -> new ArrayList<>())
                    .add(v);
        }

        List<Integer> sortedComponents = new ArrayList<>(components.keySet());
        Collections.sort(sortedComponents);

        int marginX = 90;
        int currentY = 90;

        int maxRowWidth = 1400;
        int horizontalGap = 45;
        int verticalGap = 45;
        int depthGap = 120;
        int componentGap = 180;

        for (int c : sortedComponents) {
            Map<Integer, List<Integer>> depths = components.get(c);

            List<Integer> sortedDepths = new ArrayList<>(depths.keySet());
            Collections.sort(sortedDepths);

            for (int d : sortedDepths) {
                List<Integer> nodes = depths.get(d);

                nodes.sort((a, b) -> {
                    int refsA = referenceCount.getOrDefault(a, 0);
                    int refsB = referenceCount.getOrDefault(b, 0);

                    if (refsA != refsB) {
                        return Integer.compare(refsB, refsA);
                    }

                    return Integer.compare(a, b);
                });

                int x = marginX;
                int rowHeight = 0;

                for (int v : nodes) {
                    int r = nodeRadius.getOrDefault(v, 6);
                    int diameter = r * 2;

                    if (x + diameter > marginX + maxRowWidth) {
                        x = marginX;
                        currentY += rowHeight + verticalGap;
                        rowHeight = 0;
                    }

                    positions.put(v, new Point(x + r, currentY + r));

                    x += diameter + horizontalGap;
                    rowHeight = Math.max(rowHeight, diameter);
                }

                currentY += Math.max(rowHeight, 20) + depthGap;
            }

            currentY += componentGap;
        }
    }

    private int radiusForReferences(int refs) {
        if (refs <= 0) return 6;

        int radius = 6 + (int) Math.round(Math.sqrt(refs) * 4);

        return Math.min(radius, 38);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        g.setColor(new Color(0, 0, 0, 80));

        for (int v = 0; v < labels.size(); v++) {
            Point p1 = positions.get(v);
            if (p1 == null) continue;

            Point s1 = worldToScreen(p1);

            for (int w : graph.adj(v)) {
                Point p2 = positions.get(w);
                if (p2 == null) continue;

                Point s2 = worldToScreen(p2);
                g.drawLine(s1.x, s1.y, s2.x, s2.y);
            }
        }

        for (int v = 0; v < labels.size(); v++) {
            Point p = positions.get(v);
            if (p == null) continue;

            Point s = worldToScreen(p);

            int r = nodeRadius.getOrDefault(v, 6);
            int screenRadius = (int) Math.max(2, r * scale);

            if (v == hovered) {
                g.setColor(Color.RED);
            } else {
                int refs = referenceCount.getOrDefault(v, 0);

                if (refs >= 20) {
                    g.setColor(new Color(20, 80, 200));
                } else if (refs >= 5) {
                    g.setColor(new Color(70, 120, 220));
                } else {
                    g.setColor(Color.BLUE);
                }
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
        }

        if (hovered != -1) {
            String text = labels.get(hovered)
                    + " | referenced by "
                    + referenceCount.getOrDefault(hovered, 0)
                    + " page(s)";

            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text);

            g.setColor(Color.WHITE);
            g.fillRect(10, getHeight() - 40, w + 10, 25);

            g.setColor(Color.BLACK);
            g.drawRect(10, getHeight() - 40, w + 10, 25);
            g.drawString(text, 15, getHeight() - 20);
        }
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