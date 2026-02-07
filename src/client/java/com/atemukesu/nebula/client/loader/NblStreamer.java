package com.atemukesu.nebula.client.loader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.render.AnimationFrame;
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
import java.util.HashMap;
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

    private final Map<Integer, ParticleState> stateMap = new HashMap<>();
    private final BlockingQueue<ByteBuffer> gpuBufferQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private volatile boolean isFinished = false;

    // 复用缓冲区 (Zero-Allocation)
    private ByteBuffer cachedDecompressedBuffer = null;
    private ByteBuffer cachedCompressedBuffer = null;

    // GPU Buffer Pool (智能对象池)
    // 使用队列存储空闲 Buffer，用完归还。
    private static final int INITIAL_BUFFER_SIZE = 1 * 1024 * 1024; // 1MB
    private static final int MAX_TOTAL_MEMORY = 1024 * 1024 * 1024; // 1GB 总上限
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> freeBuffers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong totalAllocatedMemory = new java.util.concurrent.atomic.AtomicLong(
            0);

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
                    cachedCompressedBuffer = ByteBuffer.allocateDirect((int) (compressedSize * 1.5));
                }
                cachedCompressedBuffer.clear();
                cachedCompressedBuffer.limit(compressedSize);
                channel.read(cachedCompressedBuffer, offset);
                cachedCompressedBuffer.flip();

                long decompressedSize = Zstd.decompressedSize(cachedCompressedBuffer);
                if (cachedDecompressedBuffer == null || cachedDecompressedBuffer.capacity() < decompressedSize) {
                    int newSize = (int) (decompressedSize > 0 ? decompressedSize * 1.5 : compressedSize * 15);
                    cachedDecompressedBuffer = ByteBuffer.allocateDirect(newSize).order(ByteOrder.LITTLE_ENDIAN);
                }
                cachedDecompressedBuffer.clear();
                try {
                    Zstd.decompress(cachedDecompressedBuffer, cachedCompressedBuffer);
                    cachedDecompressedBuffer.flip();
                } catch (Exception e) {
                    Nebula.LOGGER.error("Decompress failed frame {}", currentFrameIdx);
                    currentFrameIdx++;
                    continue;
                }

                // 3. 处理数据
                boolean isSkipping = currentFrameIdx < fastForwardTo;
                // 注意：这里我们使用 acquireBuffer，如果 isSkipping=true 则传 skipOutput=true 不分配
                ByteBuffer gpuBuffer = processFrameData(cachedDecompressedBuffer, isSkipping);

                // 4. 输出
                if (!isSkipping && gpuBuffer != null) {
                    try {
                        if (!gpuBufferQueue.offer(gpuBuffer, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            // 队列满，丢弃旧帧并回收
                            ByteBuffer old = gpuBufferQueue.poll();
                            releaseBuffer(old);
                            gpuBufferQueue.put(gpuBuffer);
                        }
                    } catch (InterruptedException e) {
                        releaseBuffer(gpuBuffer); // 别忘了释放
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                currentFrameIdx++;

                // 5. 结束
                if (currentFrameIdx >= totalFrames) {
                    gpuBufferQueue.put(BufferUtils.createByteBuffer(0)); // EOF 标记 (Size=0)
                    isFinished = true;
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
            // [Fix] 线程退出时确保清空队列，防止 buffer 泄漏 (导致 Memory usage high)
            List<ByteBuffer> cleanupList = new ArrayList<>();
            gpuBufferQueue.drainTo(cleanupList);
            for (ByteBuffer buf : cleanupList) {
                releaseBuffer(buf);
            }
        }
    }

    private ByteBuffer processFrameData(ByteBuffer data, boolean skipOutput) {
        if (data.remaining() < 5)
            return null;
        int frameType = data.get() & 0xFF;
        int particleCount = data.getInt();

        ByteBuffer gpuBuffer = null;
        if (!skipOutput) {
            int requiredSize = particleCount * AnimationFrame.BYTES_PER_PARTICLE;
            gpuBuffer = acquireBuffer(requiredSize);
        }

        if (frameType == 0)
            processIFrame(data, particleCount, gpuBuffer);
        else
            processPFrame(data, particleCount, gpuBuffer);

        if (gpuBuffer != null) {
            gpuBuffer.flip();
        }
        return gpuBuffer;
    }

    /**
     * 从池中获取 Buffer
     */
    private ByteBuffer acquireBuffer(int requiredSize) {
        ByteBuffer buf = freeBuffers.poll();

        // 如果拿到 buffer 但太小，就丢弃（重新分配）
        if (buf != null && buf.capacity() < requiredSize) {
            if (Nebula.LOGGER.isDebugEnabled()) {
                Nebula.LOGGER.debug("Discarding small buffer (cap={}, req={})", buf.capacity(), requiredSize);
            }
            totalAllocatedMemory.addAndGet(-buf.capacity());
            buf = null;
        }

        if (buf == null) {
            // 需要分配新的
            long currentTotal = totalAllocatedMemory.get();
            if (currentTotal > MAX_TOTAL_MEMORY) {
                Nebula.LOGGER.warn("Direct Memory usage high ({} MB), forcing GC...", currentTotal / 1024 / 1024);
                System.gc(); // 紧急 GC
                try {
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
            }

            int newSize = Math.max(INITIAL_BUFFER_SIZE, (int) (requiredSize * 1.2));
            try {
                buf = BufferUtils.createByteBuffer(newSize);
                totalAllocatedMemory.addAndGet(newSize);
                Nebula.LOGGER.info("Allocated NEW buffer: {} bytes (Total: {} MB)", newSize,
                        totalAllocatedMemory.get() / 1024 / 1024);
            } catch (OutOfMemoryError e) {
                System.gc();
                try {
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
                buf = BufferUtils.createByteBuffer(newSize);
                Nebula.LOGGER.warn("Allocated NEW buffer after OOM recovery: {} bytes", newSize);
            }
        } else {
            if (Nebula.LOGGER.isDebugEnabled()) {
                Nebula.LOGGER.debug("Reusing pooled buffer (cap={})", buf.capacity());
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

    private void processIFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer) {
        int baseOffset = data.position();
        int N = particleCount;
        int xArrayOffset = baseOffset;
        int yArrayOffset = xArrayOffset + (N * 4);
        int zArrayOffset = yArrayOffset + (N * 4);
        int rArrayOffset = zArrayOffset + (N * 4);
        int gArrayOffset = rArrayOffset + N;
        int bArrayOffset = gArrayOffset + N;
        int aArrayOffset = bArrayOffset + N;
        int sizeArrayOffset = aArrayOffset + N;
        int texArrayOffset = sizeArrayOffset + (N * 2);
        int seqArrayOffset = texArrayOffset + N;
        int idArrayOffset = seqArrayOffset + N;

        for (int i = 0; i < particleCount; i++) {
            try {
                int id = data.getInt(idArrayOffset + i * 4);
                ParticleState p = stateMap.get(id);
                if (p == null) {
                    p = new ParticleState();
                    stateMap.put(id, p);
                }

                p.x = data.getFloat(xArrayOffset + i * 4);
                p.y = data.getFloat(yArrayOffset + i * 4);
                p.z = data.getFloat(zArrayOffset + i * 4);
                p.r = data.get(rArrayOffset + i) & 0xFF;
                p.g = data.get(gArrayOffset + i) & 0xFF;
                p.b = data.get(bArrayOffset + i) & 0xFF;
                p.a = data.get(aArrayOffset + i) & 0xFF;
                p.size = (data.getShort(sizeArrayOffset + i * 2) & 0xFFFF) / 100.0f;
                p.texID = data.get(texArrayOffset + i) & 0xFF;
                p.seqID = data.get(seqArrayOffset + i) & 0xFF;

                if (gpuBuffer != null)
                    writeParticleToGpu(gpuBuffer, p);
            } catch (Exception e) {
            }
        }
    }

    private void processPFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer) {
        int baseOffset = data.position();
        int N = particleCount;
        int dxArrayOffset = baseOffset;
        int dyArrayOffset = dxArrayOffset + (N * 2);
        int dzArrayOffset = dyArrayOffset + (N * 2);
        int drArrayOffset = dzArrayOffset + (N * 2);
        int dgArrayOffset = drArrayOffset + N;
        int dbArrayOffset = dgArrayOffset + N;
        int daArrayOffset = dbArrayOffset + N;
        int dSizeArrayOffset = daArrayOffset + N;
        int dTexArrayOffset = dSizeArrayOffset + (N * 2);
        int dSeqArrayOffset = dTexArrayOffset + N;
        int idArrayOffset = dSeqArrayOffset + N;

        for (int i = 0; i < particleCount; i++) {
            try {
                int id = data.getInt(idArrayOffset + i * 4);
                ParticleState p = stateMap.get(id);
                if (p == null) {
                    p = new ParticleState();
                    stateMap.put(id, p);
                }

                p.x += data.getShort(dxArrayOffset + i * 2) / 1000.0f;
                p.y += data.getShort(dyArrayOffset + i * 2) / 1000.0f;
                p.z += data.getShort(dzArrayOffset + i * 2) / 1000.0f;
                p.r = (p.r + data.get(drArrayOffset + i)) & 0xFF;
                p.g = (p.g + data.get(dgArrayOffset + i)) & 0xFF;
                p.b = (p.b + data.get(dbArrayOffset + i)) & 0xFF;
                p.a = (p.a + data.get(daArrayOffset + i)) & 0xFF;
                p.size += data.getShort(dSizeArrayOffset + i * 2) / 100.0f;
                p.texID = (p.texID + data.get(dTexArrayOffset + i)) & 0xFF;
                p.seqID = (p.seqID + data.get(dSeqArrayOffset + i)) & 0xFF;

                if (gpuBuffer != null)
                    writeParticleToGpu(gpuBuffer, p);
            } catch (Exception e) {
            }
        }
    }

    private void writeParticleToGpu(ByteBuffer buf, ParticleState p) {
        buf.putFloat(p.x);
        buf.putFloat(p.y);
        buf.putFloat(p.z);
        buf.put((byte) p.r);
        buf.put((byte) p.g);
        buf.put((byte) p.b);
        buf.put((byte) p.a);
        buf.putFloat(p.size);
        buf.putFloat(ParticleTextureManager.calculateLayerIndex(p.texID, p.seqID));
        buf.putFloat(0);
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