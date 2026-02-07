package com.atemukesu.nebula.client.loader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.render.ParticleTextureManager;
import com.atemukesu.nebula.particle.data.NblHeader;
import com.atemukesu.nebula.particle.data.ParticleState;
import com.github.luben.zstd.Zstd;
import org.lwjgl.BufferUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NBL 流式加载器 (v4 最终优化版)
 * 特性：
 * 1. 支持 RandomAccessFile 快速 Seek
 * 2. 支持 Zero-Allocation 内存复用（解决 GC 卡顿）
 * 3. 支持 Fast-Forward 快进模式
 * 4. 支持 Rent-Return Buffer Pool（解决多动画闪烁问题）
 */
public class NblStreamer implements Runnable {

    private final File file;
    private long[] frameOffsets;
    private int[] frameSizes;
    private int totalFrames;
    private int targetFps;
    @SuppressWarnings("unused")
    private NblHeader header;

    private final List<ParticleTextureManager.TextureEntry> textureEntries = new ArrayList<>();
    private float[] bboxMin = new float[3];
    private float[] bboxMax = new float[3];

    // [Optimization] Use simpler HashMap as runImpl is single-threaded
    private final Map<Integer, ParticleState> stateMap = new java.util.HashMap<>();

    private final BlockingQueue<ByteBuffer> gpuBufferQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private volatile boolean isFinished = false;

    // 复用缓冲区 (Zero-Allocation)
    private ByteBuffer cachedDecompressedBuffer = null;
    private ByteBuffer cachedCompressedBuffer = null;

    // GPU Buffer Pool (智能对象池)
    private static final int INITIAL_BUFFER_SIZE = 1 * 1024 * 1024; // 1MB
    private static final int MAX_TOTAL_MEMORY = 1024 * 1024 * 1024; // 1GB 总上限
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> freeBuffers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong totalAllocatedMemory = new java.util.concurrent.atomic.AtomicLong(
            0);

    public static long getTotalAllocatedMemory() {
        return totalAllocatedMemory.get();
    }

    // Seek 控制
    private volatile int seekTargetFrame = -1;
    private static final int QUEUE_CAPACITY = 10;

    public NblStreamer(File nblFile) throws IOException {
        this.file = nblFile;
        this.gpuBufferQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        parseHeader();
    }

    private void parseHeader() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            ByteBuffer headerBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
            raf.readFully(headerBuf.array());

            byte[] magic = new byte[8];
            headerBuf.get(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("NEBULAFX")) {
                throw new IOException("Invalid NBL file");
            }

            this.targetFps = headerBuf.getShort(10) & 0xFFFF;
            this.totalFrames = headerBuf.getInt(12);
            int textureCount = headerBuf.getShort(16) & 0xFFFF;

            bboxMin[0] = headerBuf.getFloat(20);
            bboxMin[1] = headerBuf.getFloat(24);
            bboxMin[2] = headerBuf.getFloat(28);
            bboxMax[0] = headerBuf.getFloat(32);
            bboxMax[1] = headerBuf.getFloat(36);
            bboxMax[2] = headerBuf.getFloat(40);

            textureEntries.clear();
            for (int i = 0; i < textureCount; i++) {
                int pathLen = readUnsignedShortLE(raf);
                byte[] pathBytes = new byte[pathLen];
                raf.readFully(pathBytes);
                textureEntries.add(new ParticleTextureManager.TextureEntry(
                        new String(pathBytes, StandardCharsets.UTF_8),
                        raf.read() & 0xFF, raf.read() & 0xFF));
            }

            this.frameOffsets = new long[totalFrames];
            this.frameSizes = new int[totalFrames];
            ByteBuffer indexBuf = ByteBuffer.allocate(totalFrames * 12).order(ByteOrder.LITTLE_ENDIAN);
            raf.readFully(indexBuf.array());
            for (int i = 0; i < totalFrames; i++) {
                frameOffsets[i] = indexBuf.getLong();
                frameSizes[i] = indexBuf.getInt();
            }
            this.header = new NblHeader(targetFps, totalFrames, textureEntries);

            Nebula.LOGGER.info("Parsed NBL Header: FPS={}, Frames={}, Textures={}", targetFps, totalFrames,
                    textureCount);
            Nebula.LOGGER.info("  BBox Min: ({}, {}, {})", bboxMin[0], bboxMin[1], bboxMin[2]);
            Nebula.LOGGER.info("  BBox Max: ({}, {}, {})", bboxMax[0], bboxMax[1], bboxMax[2]);
            if (!textureEntries.isEmpty()) {
                Nebula.LOGGER.info("  Textures: {}",
                        textureEntries.stream().map(e -> e.path).reduce((a, b) -> a + ", " + b).orElse(""));
            }
        }
    }

    private int readUnsignedShortLE(RandomAccessFile raf) throws IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        return (b2 << 8) | b1;
    }

    public void seek(int frameIndex) {
        if (frameIndex < 0)
            frameIndex = 0;
        if (frameIndex >= totalFrames)
            frameIndex = totalFrames - 1;
        this.seekTargetFrame = frameIndex;
    }

    @Override
    public void run() {
        runImpl();
    }

    public void runImpl() {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                FileChannel channel = raf.getChannel()) {

            int currentFrameIdx = 0;
            int fastForwardTo = -1;

            while (isRunning.get() && currentFrameIdx < totalFrames) {

                // 1. 检查 Seek
                int request = seekTargetFrame;
                if (request != -1) {
                    seekTargetFrame = -1;
                    fastForwardTo = request;
                    if (request < currentFrameIdx) {
                        currentFrameIdx = 0;
                        stateMap.clear();
                        gpuBufferQueue.forEach(NblStreamer::releaseBuffer); // 释放队列中的 buffer
                        gpuBufferQueue.clear();
                        Nebula.LOGGER.debug("Seek rewind to 0, fast-forwarding to {}", request);
                    }
                }

                // 2. 读取与解压
                long offset = frameOffsets[currentFrameIdx];
                int compressedSize = frameSizes[currentFrameIdx];

                if (cachedCompressedBuffer == null || cachedCompressedBuffer.capacity() < compressedSize) {
                    if (cachedCompressedBuffer != null) {
                        totalAllocatedMemory.addAndGet(-cachedCompressedBuffer.capacity());
                    }
                    int allocSize = (int) (compressedSize * 1.5);
                    cachedCompressedBuffer = ByteBuffer.allocateDirect(allocSize);
                    totalAllocatedMemory.addAndGet(allocSize);
                }
                cachedCompressedBuffer.clear();
                cachedCompressedBuffer.limit(compressedSize);
                channel.read(cachedCompressedBuffer, offset);
                cachedCompressedBuffer.flip();

                long decompressedSize = Zstd.decompressedSize(cachedCompressedBuffer);
                if (cachedDecompressedBuffer == null || cachedDecompressedBuffer.capacity() < decompressedSize) {
                    if (cachedDecompressedBuffer != null) {
                        totalAllocatedMemory.addAndGet(-cachedDecompressedBuffer.capacity());
                    }
                    int newSize = (int) (decompressedSize > 0 ? decompressedSize * 1.5 : compressedSize * 15);
                    cachedDecompressedBuffer = ByteBuffer.allocateDirect(newSize).order(ByteOrder.LITTLE_ENDIAN);
                    totalAllocatedMemory.addAndGet(newSize);
                }
                cachedDecompressedBuffer.clear();
                try {
                    long decompressedBytes = Zstd.decompress(cachedDecompressedBuffer, cachedCompressedBuffer);
                    if (decompressedBytes != decompressedSize) {
                        Nebula.LOGGER.warn("Zstd decompressed size mismatch! Expected {}, got {}", decompressedSize,
                                decompressedBytes);
                    }
                    cachedDecompressedBuffer.position((int) decompressedBytes);
                    cachedDecompressedBuffer.flip();
                } catch (Exception e) {
                    Nebula.LOGGER.error("Decompress failed frame {}", currentFrameIdx);
                    currentFrameIdx++;
                    continue;
                }

                // 3. 处理数据
                boolean isSkipping = currentFrameIdx < fastForwardTo;
                // Use acquireBuffer, if isSkipping=true then skip output=true
                ByteBuffer gpuBuffer = processFrameData(cachedDecompressedBuffer, isSkipping, currentFrameIdx);

                // 4. 输出
                if (!isSkipping && gpuBuffer != null) {
                    try {
                        // [Fix] Block if queue is full. Do NOT drop frames, or we lose interpolation
                        // data and waste CPU.
                        gpuBufferQueue.put(gpuBuffer);
                    } catch (InterruptedException e) {
                        releaseBuffer(gpuBuffer);
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                currentFrameIdx++;

                // 5. 结束
                if (currentFrameIdx >= totalFrames) {
                    // 确保最后一帧后发送 EOF
                    try {
                        gpuBufferQueue.put(BufferUtils.createByteBuffer(0)); // EOF
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    isFinished = true;
                    // Wait for seek or stop
                    while (isRunning.get() && seekTargetFrame == -1) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    if (seekTargetFrame != -1) {
                        isFinished = false;
                    }
                }
            }
        } catch (Exception e) {
            Nebula.LOGGER.error("Streamer crashed", e);
        } finally {
            if (cachedCompressedBuffer != null) {
                totalAllocatedMemory.addAndGet(-cachedCompressedBuffer.capacity());
                cachedCompressedBuffer = null;
            }
            if (cachedDecompressedBuffer != null) {
                totalAllocatedMemory.addAndGet(-cachedDecompressedBuffer.capacity());
                cachedDecompressedBuffer = null;
            }

            List<ByteBuffer> cleanupList = new ArrayList<>();
            gpuBufferQueue.drainTo(cleanupList);
            for (ByteBuffer buf : cleanupList) {
                releaseBuffer(buf);
            }
        }
    }

    private ByteBuffer processFrameData(ByteBuffer data, boolean skipOutput, int frameIdx) {
        if (data.remaining() < 5)
            return null;
        int frameType = data.get() & 0xFF;
        int particleCount = data.getInt();

        ByteBuffer gpuBuffer = null;
        if (!skipOutput) {
            // [Fix] Use local constant 40 to match writeParticleToGpuAbs logic.
            // External AnimationFrame.BYTES_PER_PARTICLE might be different (e.g. 32),
            // leading to overflow.
            int requiredSize = particleCount * 40;
            gpuBuffer = acquireBuffer(requiredSize);
        }

        if (frameType == 0)
            processIFrame(data, particleCount, gpuBuffer, frameIdx);
        else
            processPFrame(data, particleCount, gpuBuffer, frameIdx);

        // No flip here as we used absolute puts

        return gpuBuffer;
    }

    private void processIFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer, int frameIdx) {
        int baseOffset = data.position();
        int N = particleCount;

        // Pre-calculate offsets
        int xOff = baseOffset;
        int yOff = xOff + (N * 4);
        int zOff = yOff + (N * 4);
        int rOff = zOff + (N * 4);
        int gOff = rOff + N;
        int bOff = gOff + N;
        int aOff = bOff + N;
        int sizeOff = aOff + N;
        int texOff = sizeOff + (N * 2);
        int seqOff = texOff + N;
        int idOff = seqOff + N;

        // [Single-threading] Use standard for-loop for better performance/less overhead
        // on small/medium counts
        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);

            ParticleState p = stateMap.computeIfAbsent(id, k -> new ParticleState());
            p.lastSeenFrame = frameIdx; // Mark as seen

            float nx = data.getFloat(xOff + i * 4);
            float ny = data.getFloat(yOff + i * 4);
            float nz = data.getFloat(zOff + i * 4);

            p.x = nx;
            p.y = ny;
            p.z = nz;

            // For I-Frame, snap prev to current
            p.prevX = nx;
            p.prevY = ny;
            p.prevZ = nz;

            p.r = data.get(rOff + i) & 0xFF;
            p.g = data.get(gOff + i) & 0xFF;
            p.b = data.get(bOff + i) & 0xFF;
            p.a = data.get(aOff + i) & 0xFF;
            p.size = (data.getShort(sizeOff + i * 2) & 0xFFFF) / 100.0f;
            p.texID = data.get(texOff + i) & 0xFF;
            p.seqID = data.get(seqOff + i) & 0xFF;

            if (gpuBuffer != null) {
                writeParticleToGpuAbs(gpuBuffer, i, p);
            }
        }
    }

    private void processPFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer, int frameIdx) {
        int baseOffset = data.position();
        int N = particleCount;

        int dxOff = baseOffset;
        int dyOff = dxOff + (N * 2);
        int dzOff = dyOff + (N * 2);
        int drOff = dzOff + (N * 2);
        int dgOff = drOff + N;
        int dbOff = dgOff + N;
        int daOff = dbOff + N;
        int sizeOff = daOff + N;
        int texOff = sizeOff + (N * 2);
        int seqOff = texOff + N;
        int idOff = seqOff + N;

        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);
            ParticleState p = stateMap.computeIfAbsent(id, k -> new ParticleState());

            // [Lifecycle Check] If not seen in prev frame (or ever), it's a SPAWN. Reset to
            // 0.
            boolean isSpawn = false;
            if (p.lastSeenFrame != frameIdx - 1) {
                p.x = 0;
                p.y = 0;
                p.z = 0;
                p.r = 0;
                p.g = 0;
                p.b = 0;
                p.a = 0;
                p.size = 0;
                p.texID = 0;
                p.seqID = 0;
                isSpawn = true;
            }
            p.lastSeenFrame = frameIdx; // Mark as seen in this frame

            // Store old values for valid interpolation
            float oldX = p.x;
            float oldY = p.y;
            float oldZ = p.z;

            // Apply deltas
            p.x += data.getShort(dxOff + i * 2) / 1000.0f;
            p.y += data.getShort(dyOff + i * 2) / 1000.0f;
            p.z += data.getShort(dzOff + i * 2) / 1000.0f;

            p.r = (p.r + data.get(drOff + i)) & 0xFF;
            p.g = (p.g + data.get(dgOff + i)) & 0xFF;
            p.b = (p.b + data.get(dbOff + i)) & 0xFF;
            p.a = (p.a + data.get(daOff + i)) & 0xFF;

            p.size += data.getShort(sizeOff + i * 2) / 100.0f;
            p.texID = (p.texID + data.get(texOff + i)) & 0xFF;
            p.seqID = (p.seqID + data.get(seqOff + i)) & 0xFF;

            // [Interpolation Fix]
            if (isSpawn) {
                // Start of life: Snap prev to current (No interpolation from 0)
                p.prevX = p.x;
                p.prevY = p.y;
                p.prevZ = p.z;
            } else {
                // Update: prev is the state BEFORE applying delta
                p.prevX = oldX;
                p.prevY = oldY;
                p.prevZ = oldZ;
            }

            if (gpuBuffer != null) {
                writeParticleToGpuAbs(gpuBuffer, i, p);
            }
        }
    }

    // Thread-safe absolute write to GPU buffer
    private void writeParticleToGpuAbs(ByteBuffer buf, int index, ParticleState p) {
        // 40 bytes per particle
        int offset = index * 40;

        // [Fix] Safety check to prevent IndexOutOfBoundsException
        if (offset + 40 > buf.capacity()) {
            // This should technically not happen if acquireBuffer is correct, but let's be
            // safe.
            // However, we cannot easily resize 'buf' here as it's passed by reference and
            // might be in use.
            // The improved acquireBuffer logic should prevent this.
            // For now, just return to avoid crash, but log error once.
            if (index == 0)
                Nebula.LOGGER.error("Buffer overflow in writeParticleToGpuAbs! Cap: {}, Req: {}", buf.capacity(),
                        offset + 40);
            return;
        }

        // [Pos layout]: prevX, prevY, prevZ, curX, curY, curZ
        buf.putFloat(offset, p.prevX);
        buf.putFloat(offset + 4, p.prevY);
        buf.putFloat(offset + 8, p.prevZ);

        buf.putFloat(offset + 12, p.x);
        buf.putFloat(offset + 16, p.y);
        buf.putFloat(offset + 20, p.z);

        buf.put(offset + 24, (byte) p.r);
        buf.put(offset + 25, (byte) p.g);
        buf.put(offset + 26, (byte) p.b);
        buf.put(offset + 27, (byte) p.a);

        buf.putFloat(offset + 28, p.size);
        buf.putFloat(offset + 32, ParticleTextureManager.calculateLayerIndex(p.texID, p.seqID));
        buf.putFloat(offset + 36, 0); // Padding
    }

    /**
     * 从池中获取 Buffer
     */
    private ByteBuffer acquireBuffer(int requiredSize) {
        ByteBuffer buf = freeBuffers.poll();

        // 如果拿到 buffer 但太小，就丢弃（重新分配）
        if (buf != null && buf.capacity() < requiredSize) {
            totalAllocatedMemory.addAndGet(-buf.capacity());
            buf = null;
        }

        if (buf == null) {
            // 需要分配新的
            long currentTotal = totalAllocatedMemory.get();
            if (currentTotal > MAX_TOTAL_MEMORY) {
                // Nebula.LOGGER.warn("Direct Memory usage high ({} MB), forcing GC...",
                // currentTotal / 1024 / 1024);
                // System.gc(); // 减少频繁 GC，只在极端情况
            }

            int newSize = Math.max(INITIAL_BUFFER_SIZE, (int) (requiredSize * 1.2));
            try {
                buf = BufferUtils.createByteBuffer(newSize);
                totalAllocatedMemory.addAndGet(newSize);
            } catch (OutOfMemoryError e) {
                System.gc(); // Only GC on OOM
                try {
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
                buf = BufferUtils.createByteBuffer(newSize);
            }
        }

        buf.clear();
        buf.limit(requiredSize);
        return buf;
    }

    /**
     * [重要] 归还 Buffer 到池中
     * 必须在渲染完成后调用！
     */
    public static void releaseBuffer(ByteBuffer buf) {
        if (buf != null && buf.capacity() > 0) { // EOF buffer (cap=0) 不回收
            buf.clear();
            freeBuffers.offer(buf);
        }
    }

    public void loadTextures() {
        if (!textureEntries.isEmpty())
            ParticleTextureManager.loadFromNblEntries(textureEntries);
        else
            ParticleTextureManager.init();
    }

    public BlockingQueue<ByteBuffer> getQueue() {
        return gpuBufferQueue;
    }

    public void stop() {
        isRunning.set(false);
    }

    public boolean isFinished() {
        return isFinished;
    }

    public int getTargetFps() {
        return targetFps;
    }

    public int getTotalFrames() {
        return totalFrames;
    }

    public float[] getBboxMin() {
        return bboxMin;
    }

    public float[] getBboxMax() {
        return bboxMax;
    }

    public List<ParticleTextureManager.TextureEntry> getTextureEntries() {
        return textureEntries;
    }
}