package com.dedeleledede;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public class Bag<Item> implements Iterable<Item> {
    private Node<Item> first;
    private int n;

    private static class Node<Item> {
        private Item item;
        private Node<Item> next;
    }

    public Bag() {
        first = null;
        n = 0;
    }

    public boolean isEmpty() {
        return first == null;
    }

    public int size() {
        return n;
    }

    public void add(Item item) {
        Node<Item> oldfirst = first;
        first = new Node<Item>();
        first.item = item;
        first.next = oldfirst;
        n++;
    }

    public Iterator<Item> iterator()  {
        return new LinkedIterator(first);
    }

    private class LinkedIterator implements Iterator<Item> {
        private Node<Item> current;

        public LinkedIterator(Node<Item> first) {
            current = first;
        }

        public boolean hasNext()  {
            return current != null;
        }

        public Item next() {
            if (!hasNext()) throw new NoSuchElementException();
            Item item = current.item;
            current = current.next;
            return item;
        }
    }

    public void clear() {
        first = null;
        n = 0;
    }

    public boolean contains(Item item) {
        for (Item current : this) {
            if (Objects.equals(current, item)) {
                return true;
            }
        }
        return false;
    }

    public boolean remove(Item item) {
        Node<Item> previous = null;
        Node<Item> current = first;

        while (current != null) {
            if (Objects.equals(current.item, item)) {
                if (previous == null) {
                    first = current.next;
                } else {
                    previous.next = current.next;
                }

                n--;
                return true;
            }

            previous = current;
            current = current.next;
        }

        return false;
    }
}