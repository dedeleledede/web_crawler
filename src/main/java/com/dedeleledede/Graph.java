package com.dedeleledede;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Graph {
    private int V;
    private int E;
    private Bag<Integer>[] adj;

    @SuppressWarnings("unchecked")
    public Graph(int V) {
        this.V = V;
        this.E = 0;
        adj = (Bag<Integer>[]) new Bag[V];

        for (int v = 0; v < V; v++) {
            adj[v] = new Bag<>();
        }
    }

    public synchronized int V() {
        return V;
    }

    public synchronized int E() {
        return E;
    }

    @SuppressWarnings("unchecked")
    public synchronized void ensureVertex(int v) {
        if (v < adj.length) return;

        int newSize = adj.length;
        while (v >= newSize) {
            newSize *= 2;
        }

        Bag<Integer>[] newAdj = (Bag<Integer>[]) new Bag[newSize];

        for (int i = 0; i < adj.length; i++) {
            newAdj[i] = adj[i];
        }

        for (int i = adj.length; i < newSize; i++) {
            newAdj[i] = new Bag<>();
        }

        adj = newAdj;
        V = newSize;
    }

    public synchronized void addEdge(int v, int w) {
        ensureVertex(Math.max(v, w));

        if (!adj[v].contains(w)) {
            adj[v].add(w);
            E++;
        }
    }

    public synchronized void removeEdge(int v, int w) {
        if (v >= adj.length) return;

        if (adj[v].remove(w)) {
            E--;
        }
    }

    public synchronized void removeEdgesFrom(int v) {
        if (v >= adj.length) return;

        E -= adj[v].size();
        adj[v].clear();
    }

    public synchronized Iterable<Integer> adj(int v) {
        if (v >= adj.length) {
            return Collections.emptyList();
        }

        List<Integer> copy = new ArrayList<>();

        for (int w : adj[v]) {
            copy.add(w);
        }

        return copy;
    }
}