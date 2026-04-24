package com.dedeleledede;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GraphStore implements Closeable {
    private final Path dir;
    private final Path nodesFile;
    private final Path edgesFile;
    private final Path processedFile;

    private BufferedWriter nodesWriter;
    private BufferedWriter edgesWriter;
    private BufferedWriter processedWriter;

    private final Set<Integer> writtenNodes = new HashSet<>();
    private final Set<String> writtenEdges = new HashSet<>();
    private final Set<Integer> writtenProcessed = new HashSet<>();

    public GraphStore(String folder) {
        this.dir = Path.of(folder);
        this.nodesFile = dir.resolve("nodes.tsv");
        this.edgesFile = dir.resolve("edges.tsv");
        this.processedFile = dir.resolve("processed.tsv");
    }

    public synchronized void open() {
        try {
            Files.createDirectories(dir);

            if (!Files.exists(nodesFile)) Files.createFile(nodesFile);
            if (!Files.exists(edgesFile)) Files.createFile(edgesFile);
            if (!Files.exists(processedFile)) Files.createFile(processedFile);

            loadWrittenState();

            nodesWriter = Files.newBufferedWriter(nodesFile, StandardOpenOption.APPEND);
            edgesWriter = Files.newBufferedWriter(edgesFile, StandardOpenOption.APPEND);
            processedWriter = Files.newBufferedWriter(processedFile, StandardOpenOption.APPEND);

        } catch (IOException e) {
            throw new RuntimeException("Could not open graph store", e);
        }
    }

    private void loadWrittenState() throws IOException {
        for (String line : Files.readAllLines(nodesFile)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 2);
            writtenNodes.add(Integer.parseInt(parts[0]));
        }

        for (String line : Files.readAllLines(edgesFile)) {
            if (line.isBlank()) continue;
            writtenEdges.add(line.trim());
        }

        for (String line : Files.readAllLines(processedFile)) {
            if (line.isBlank()) continue;
            writtenProcessed.add(Integer.parseInt(line.trim()));
        }
    }

    public synchronized List<String> loadNodes() {
        try {
            List<String> result = new ArrayList<>();

            for (String line : Files.readAllLines(nodesFile)) {
                if (line.isBlank()) continue;

                String[] parts = line.split("\t", 2);
                int id = Integer.parseInt(parts[0]);
                String url = parts[1];

                while (result.size() <= id) {
                    result.add(null);
                }

                result.set(id, url);
            }

            return result;

        } catch (IOException e) {
            throw new RuntimeException("Could not load nodes", e);
        }
    }

    public synchronized List<int[]> loadEdges() {
        try {
            List<int[]> result = new ArrayList<>();

            for (String line : Files.readAllLines(edgesFile)) {
                if (line.isBlank()) continue;

                String[] parts = line.split("\t");
                result.add(new int[]{
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1])
                });
            }

            return result;

        } catch (IOException e) {
            throw new RuntimeException("Could not load edges", e);
        }
    }

    public synchronized Set<Integer> loadProcessed() {
        try {
            Set<Integer> result = new HashSet<>();

            for (String line : Files.readAllLines(processedFile)) {
                if (line.isBlank()) continue;
                result.add(Integer.parseInt(line.trim()));
            }

            return result;

        } catch (IOException e) {
            throw new RuntimeException("Could not load processed nodes", e);
        }
    }

    public synchronized void writeNode(int id, String url) {
        try {
            if (writtenNodes.add(id)) {
                nodesWriter.write(id + "\t" + url.replace("\t", " ") + "\n");
                nodesWriter.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not write node", e);
        }
    }

    public synchronized void writeEdge(int v, int w) {
        try {
            String edge = v + "\t" + w;

            if (writtenEdges.add(edge)) {
                edgesWriter.write(edge + "\n");
                edgesWriter.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not write edge", e);
        }
    }

    public synchronized void writeProcessed(int id) {
        try {
            if (writtenProcessed.add(id)) {
                processedWriter.write(id + "\n");
                processedWriter.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not write processed node", e);
        }
    }

    public synchronized void rewriteAll(List<String> labels, Graph graph, Set<Integer> processedIds) {
        try {
            close();

            Files.writeString(nodesFile, "");
            Files.writeString(edgesFile, "");
            Files.writeString(processedFile, "");

            writtenNodes.clear();
            writtenEdges.clear();
            writtenProcessed.clear();

            open();

            for (int i = 0; i < labels.size(); i++) {
                if (labels.get(i) != null) {
                    writeNode(i, labels.get(i));
                }
            }

            for (int v = 0; v < labels.size(); v++) {
                for (int w : graph.adj(v)) {
                    writeEdge(v, w);
                }
            }

            for (int id : processedIds) {
                writeProcessed(id);
            }

        } catch (IOException e) {
            throw new RuntimeException("Could not rewrite graph files", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (nodesWriter != null) nodesWriter.close();
        if (edgesWriter != null) edgesWriter.close();
        if (processedWriter != null) processedWriter.close();
    }
}