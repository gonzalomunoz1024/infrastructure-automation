package com.iaf.console.drift.load;

import java.util.Arrays;

/** A min-heap of longs, without boxing: the due queue of one shard and tier holds a few hundred thousand. */
final class LongHeap {

    private long[] items = new long[1024];
    private int size;

    void push(long v) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
        }
        int at = size++;
        while (at > 0) {
            int parent = (at - 1) >>> 1;
            if (items[parent] <= v) {
                break;
            }
            items[at] = items[parent];
            at = parent;
        }
        items[at] = v;
    }

    boolean isEmpty() {
        return size == 0;
    }

    long peek() {
        return items[0];
    }

    long pop() {
        long top = items[0];
        long last = items[--size];
        int at = 0;
        while (true) {
            int child = 2 * at + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && items[child + 1] < items[child]) {
                child++;
            }
            if (items[child] >= last) {
                break;
            }
            items[at] = items[child];
            at = child;
        }
        if (size > 0) {
            items[at] = last;
        }
        return top;
    }

    int size() {
        return size;
    }
}
