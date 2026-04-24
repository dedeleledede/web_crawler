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
            if (p != null && p.distance(worldPoint) < 14) {
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
        Map<Integer, Integer> depthMap = new HashMap<>();
        Map<Integer, Integer> componentMap = new HashMap<>();

        int component = 0;

        for (int start = 0; start < labels.size(); start++) {
            if (labels.get(start) == null) continue;
            if (depthMap.containsKey(start)) continue;

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

        Map<Integer, List<Integer>> levels = new HashMap<>();

        for (int v : depthMap.keySet()) {
            int c = componentMap.get(v);
            int d = depthMap.get(v);

            int combinedLevel = c * 20 + d;
            levels.computeIfAbsent(combinedLevel, k -> new ArrayList<>()).add(v);
        }

        List<Integer> sortedLevels = new ArrayList<>(levels.keySet());
        Collections.sort(sortedLevels);

        int levelHeight = 180;

        for (int row = 0; row < sortedLevels.size(); row++) {
            List<Integer> nodes = levels.get(sortedLevels.get(row));
            Collections.sort(nodes);

            int count = nodes.size();
            int width = Math.max(1200, count * 180);

            for (int i = 0; i < count; i++) {
                int v = nodes.get(i);

                int x = (width / (count + 1)) * (i + 1);
                int y = 100 + row * levelHeight;

                positions.put(v, new Point(x, y));
            }
        }
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

            if (v == hovered) {
                g.setColor(Color.RED);
                g.fillOval(s.x - 8, s.y - 8, 16, 16);
            } else {
                g.setColor(Color.BLUE);
                g.fillOval(s.x - 6, s.y - 6, 12, 12);
            }
        }

        if (hovered != -1) {
            String text = labels.get(hovered);

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