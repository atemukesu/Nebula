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
 * NBL 流式加载器 (SSBO 优化版)
 * <p>
 * 负责从磁盘读取压缩的 NBL 文件，在 CPU 端进行 Zstd 解压和状态计算，
 * 并将数据打包成 GPU SSBO 友好的格式 (std430 布局)。
 * </p>
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

    // 使用 HashMap 存储粒子状态 (因为是单线程运行，无需 Concurrent)
    private final Map<Integer, ParticleState> stateMap = new java.util.HashMap<>();

    private final BlockingQueue<ByteBuffer> gpuBufferQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private volatile boolean isFinished = false;

    // 复用缓冲区 (Zero-Allocation)
    private ByteBuffer cachedDecompressedBuffer = null;
    private ByteBuffer cachedCompressedBuffer = null;

    // GPU Buffer Pool (智能对象池)
    private static final int INITIAL_BUFFER_SIZE = 1 * 1024 * 1024; // 1MB
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> freeBuffers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong totalAllocatedMemory = new java.util.concurrent.atomic.AtomicLong(
            0);

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
                // Seek 处理
                int request = seekTargetFrame;
                if (request != -1) {
                    seekTargetFrame = -1;
                    fastForwardTo = request;
                    if (request < currentFrameIdx) {
                        currentFrameIdx = 0;
                        stateMap.clear();
                        gpuBufferQueue.forEach(NblStreamer::releaseBuffer);
                        gpuBufferQueue.clear();
                    }
                }

                long offset = frameOffsets[currentFrameIdx];
                int compressedSize = frameSizes[currentFrameIdx];

                // 缓冲管理与解压 (Zstd)
                ensureCompressedBuffer(compressedSize);
                cachedCompressedBuffer.clear();
                cachedCompressedBuffer.limit(compressedSize);
                channel.read(cachedCompressedBuffer, offset);
                cachedCompressedBuffer.flip();

                long decompressedSize = Zstd.decompressedSize(cachedCompressedBuffer);
                ensureDecompressedBuffer((int) decompressedSize, compressedSize);

                cachedDecompressedBuffer.clear();
                try {
                    long decompressedBytes = Zstd.decompress(cachedDecompressedBuffer, cachedCompressedBuffer);
                    cachedDecompressedBuffer.position((int) decompressedBytes);
                    cachedDecompressedBuffer.flip();
                } catch (Exception e) {
                    Nebula.LOGGER.error("Decompress failed frame {}", currentFrameIdx);
                    currentFrameIdx++;
                    continue;
                }

                // 处理帧数据
                boolean isSkipping = currentFrameIdx < fastForwardTo;
                ByteBuffer gpuBuffer = processFrameData(cachedDecompressedBuffer, isSkipping, currentFrameIdx);

                if (!isSkipping && gpuBuffer != null) {
                    try {
                        gpuBufferQueue.put(gpuBuffer);
                    } catch (InterruptedException e) {
                        releaseBuffer(gpuBuffer);
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                currentFrameIdx++;

                // 结束处理
                if (currentFrameIdx >= totalFrames) {
                    try {
                        gpuBufferQueue.put(BufferUtils.createByteBuffer(0)); // EOF
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    isFinished = true;
                    while (isRunning.get() && seekTargetFrame == -1) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (seekTargetFrame != -1)
                        isFinished = false;
                }
            }
        } catch (Exception e) {
            Nebula.LOGGER.error("Streamer crashed", e);
        } finally {
            cleanup();
        }
    }

    /**
     * 处理解压后的帧数据，生成 SSBO 格式数据
     */
    private ByteBuffer processFrameData(ByteBuffer data, boolean skipOutput, int frameIdx) {
        if (data.remaining() < 5)
            return null;
        int frameType = data.get() & 0xFF;
        int particleCount = data.getInt();

        ByteBuffer gpuBuffer = null;
        if (!skipOutput) {
            // [修改] 申请 48 字节/粒子的缓冲区 (SSBO std430)
            int requiredSize = particleCount * 48;
            gpuBuffer = acquireBuffer(requiredSize);
        }

        if (frameType == 0)
            processIFrame(data, particleCount, gpuBuffer, frameIdx);
        else
            processPFrame(data, particleCount, gpuBuffer, frameIdx);

        return gpuBuffer;
    }

    private void processIFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer, int frameIdx) {
        // [优化] 计算所有字段的偏移量，避免循环内重复计算
        int baseOffset = data.position();
        int N = particleCount;
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

        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);
            ParticleState p = stateMap.computeIfAbsent(id, k -> new ParticleState());
            p.lastSeenFrame = frameIdx;

            float nx = data.getFloat(xOff + i * 4);
            float ny = data.getFloat(yOff + i * 4);
            float nz = data.getFloat(zOff + i * 4);
            p.x = nx;
            p.y = ny;
            p.z = nz;
            p.prevX = nx;
            p.prevY = ny;
            p.prevZ = nz; // I-Frame Snap

            p.r = data.get(rOff + i) & 0xFF;
            p.g = data.get(gOff + i) & 0xFF;
            p.b = data.get(bOff + i) & 0xFF;
            p.a = data.get(aOff + i) & 0xFF;
            p.size = (data.getShort(sizeOff + i * 2) & 0xFFFF) / 100.0f;
            p.texID = data.get(texOff + i) & 0xFF;
            p.seqID = data.get(seqOff + i) & 0xFF;

            if (gpuBuffer != null)
                writeParticleToGpuAbs(gpuBuffer, i, p);
        }
    }

    private void processPFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer, int frameIdx) {
        int baseOffset = data.position();
        int N = particleCount;
        // P-Frame 偏移计算
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

            boolean isSpawn = (p.lastSeenFrame != frameIdx - 1);
            if (isSpawn) {
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
            }
            p.lastSeenFrame = frameIdx;

            float oldX = p.x, oldY = p.y, oldZ = p.z;

            // 应用增量 (Short / 1000.0)
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

            if (isSpawn) {
                p.prevX = p.x;
                p.prevY = p.y;
                p.prevZ = p.z;
            } else {
                p.prevX = oldX;
                p.prevY = oldY;
                p.prevZ = oldZ;
            }

            if (gpuBuffer != null)
                writeParticleToGpuAbs(gpuBuffer, i, p);
        }
    }

    /**
     * 将粒子数据写入 GPU 缓冲区 (SSBO std430 格式)
     * <p>
     * Layout (48 bytes):
     * [0-11] PrevPos.xyz
     * [12-15] Size
     * [16-27] CurPos.xyz
     * [28-31] Color (Packed RGBA)
     * [32-35] TexID
     * [36-47] Padding
     * </p>
     */
    private void writeParticleToGpuAbs(ByteBuffer buf, int index, ParticleState p) {
        int offset = index * 48; // 48 bytes per particle

        // 越界检查
        if (offset + 48 > buf.capacity()) {
            if (index == 0)
                Nebula.LOGGER.error("Buffer overflow! Cap: {}, Req: {}", buf.capacity(), offset + 48);
            return;
        }

        // === Vec4 #1: PrevPos(xyz) + Size(w) ===
        buf.putFloat(offset, p.prevX);
        buf.putFloat(offset + 4, p.prevY);
        buf.putFloat(offset + 8, p.prevZ);
        buf.putFloat(offset + 12, p.size);

        // === Vec4 #2: CurPos(xyz) + Color(w) ===
        buf.putFloat(offset + 16, p.x);
        buf.putFloat(offset + 20, p.y);
        buf.putFloat(offset + 24, p.z);

        // 颜色压缩 (RGBA 4 bytes -> 1 int)
        // Little Endian: ABGR in int memory (but GL reads bytes directly)
        int colorPacked = (p.a << 24) | (p.b << 16) | (p.g << 8) | p.r;
        buf.putInt(offset + 28, colorPacked);

        // === Vec4 #3: TexID, SeqID, Padding... ===
        // 预计算 Texture Layer
        float layerIndex = ParticleTextureManager.calculateLayerIndex(p.texID, p.seqID);
        buf.putFloat(offset + 32, layerIndex);
        buf.putFloat(offset + 36, 0f); // Padding
        buf.putFloat(offset + 40, 0f); // Padding
        buf.putFloat(offset + 44, 0f); // Padding
    }

    // ... (acquireBuffer, releaseBuffer, 辅助方法等保持原样) ...

    // 内存管理辅助函数
    private void ensureCompressedBuffer(int size) {
        if (cachedCompressedBuffer == null || cachedCompressedBuffer.capacity() < size) {
            if (cachedCompressedBuffer != null)
                totalAllocatedMemory.addAndGet(-cachedCompressedBuffer.capacity());
            int allocSize = (int) (size * 1.5);
            cachedCompressedBuffer = ByteBuffer.allocateDirect(allocSize);
            totalAllocatedMemory.addAndGet(allocSize);
        }
    }

    private void ensureDecompressedBuffer(int size, int compressedSize) {
        if (cachedDecompressedBuffer == null || cachedDecompressedBuffer.capacity() < size) {
            if (cachedDecompressedBuffer != null)
                totalAllocatedMemory.addAndGet(-cachedDecompressedBuffer.capacity());
            int newSize = (int) (size > 0 ? size * 1.5 : compressedSize * 15);
            cachedDecompressedBuffer = ByteBuffer.allocateDirect(newSize).order(ByteOrder.LITTLE_ENDIAN);
            totalAllocatedMemory.addAndGet(newSize);
        }
    }

    private void cleanup() {
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
        for (ByteBuffer buf : cleanupList)
            releaseBuffer(buf);
    }

    public BlockingQueue<ByteBuffer> getQueue() {
        return gpuBufferQueue;
    }

    public void loadTextures() {
        if (!textureEntries.isEmpty())
            ParticleTextureManager.loadFromNblEntries(textureEntries);
        else
            ParticleTextureManager.init();
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

    /**
     * 获取当前分配的总堆外内存大小 (用于 DebugHud)
     */
    public static long getTotalAllocatedMemory() {
        return totalAllocatedMemory.get();
    }

    /**
     * 从池中获取 Buffer (如果不够大则新建)
     */
    private ByteBuffer acquireBuffer(int requiredSize) {
        ByteBuffer buf = freeBuffers.poll();

        // 如果拿到 buffer 但太小，就丢弃（重新分配）
        if (buf != null && buf.capacity() < requiredSize) {
            totalAllocatedMemory.addAndGet(-buf.capacity());
            buf = null;
        }

        if (buf == null) {
            // 内存分配策略：至少分配 INITIAL_BUFFER_SIZE，或者是需求的 1.2 倍
            int newSize = Math.max(INITIAL_BUFFER_SIZE, (int) (requiredSize * 1.2));
            try {
                buf = BufferUtils.createByteBuffer(newSize);
                totalAllocatedMemory.addAndGet(newSize);
            } catch (OutOfMemoryError e) {
                System.gc();
                try {
                    Thread.sleep(10);
                } catch (Exception ignored) {
                }
                buf = BufferUtils.createByteBuffer(newSize);
                totalAllocatedMemory.addAndGet(newSize);
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
        // EOF buffer (cap=0) 不回收，其他回收
        if (buf != null && buf.capacity() > 0) {
            buf.clear();
            freeBuffers.offer(buf);
        }
    }
}