package com.atemukesu.nebula.client.render;

import java.util.concurrent.ConcurrentLinkedQueue;

public class SoAFrameData {
    private static final int POOL_SOFT_CAP = 16;
    private static final ConcurrentLinkedQueue<SoAFrameData> POOL = new ConcurrentLinkedQueue<>();

    public int count;
    public float[] prevX;
    public float[] prevY;
    public float[] prevZ;
    public float[] curX;
    public float[] curY;
    public float[] curZ;
    public float[] sizes;
    public int[] colorsPacked;
    public float[] texLayers;
    public byte[] texs;
    public byte[] seqs;

    private SoAFrameData() {
    }

    public static SoAFrameData obtain(int minCapacity) {
        SoAFrameData data = POOL.poll();
        if (data == null) {
            data = new SoAFrameData();
        }
        data.count = 0;
        data.ensureCapacity(minCapacity);
        return data;
    }

    public void release() {
        count = 0;
        if (POOL.size() < POOL_SOFT_CAP) {
            POOL.offer(this);
        }
    }

    public void ensureCapacity(int capacity) {
        if (prevX != null && prevX.length >= capacity) {
            return;
        }

        int newCapacity = Math.max(4096, capacity);
        prevX = new float[newCapacity];
        prevY = new float[newCapacity];
        prevZ = new float[newCapacity];
        curX = new float[newCapacity];
        curY = new float[newCapacity];
        curZ = new float[newCapacity];
        sizes = new float[newCapacity];
        colorsPacked = new int[newCapacity];
        texLayers = new float[newCapacity];
        texs = new byte[newCapacity];
        seqs = new byte[newCapacity];
    }
}
