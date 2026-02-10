package com.atemukesu.nebula.client.loader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.render.ParticleTextureManager;
import com.atemukesu.nebula.particle.data.NblHeader;
import com.atemukesu.nebula.particle.data.ParticleState;
import com.github.luben.zstd.Zstd;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * <h1>
 * NBL 流式加载器 (For SSBO)
 * </h1>
 * <hr>
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

    // [v2.0 新增] 关键帧索引表
    private int[] keyframeIndices;

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
    // [新增字段] 用于 runImpl 内部通信
    private volatile int forceResetToFrame = -1;
    private volatile int fastForwardTo = -1;

    private static final int QUEUE_CAPACITY = 10;

    // 并行处理相关
    // 使用共享的 ForkJoinPool，避免创建过多线程
    private static final ForkJoinPool PARALLEL_POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
    // 粒子数超过此阈值时启用并行 GPU 写入
    private static final int PARALLEL_THRESHOLD = 5000;

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

            // 读取数量
            byte[] countBytes = new byte[4];
            raf.readFully(countBytes);
            int keyframeCount = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

            // 读取列表
            ByteBuffer kfBuf = ByteBuffer.allocate(keyframeCount * 4).order(ByteOrder.LITTLE_ENDIAN);
            raf.readFully(kfBuf.array());

            keyframeIndices = new int[keyframeCount];
            for (int i = 0; i < keyframeCount; i++) {
                keyframeIndices[i] = kfBuf.getInt();
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
            // 初始化
            fastForwardTo = -1;
            forceResetToFrame = -1;

            while (isRunning.get() && currentFrameIdx < totalFrames) {
                // Seek 处理
                int request = seekTargetFrame;
                if (request != -1) {
                    seekTargetFrame = -1;

                    // [智能 Seek 逻辑]
                    int targetFrame = request;
                    if (targetFrame < 0)
                        targetFrame = 0;
                    if (targetFrame >= totalFrames)
                        targetFrame = totalFrames - 1;

                    // 1. 找最近关键帧
                    int bestKeyframe = 0;
                    if (keyframeIndices != null) {
                        for (int kf : keyframeIndices) {
                            if (kf <= targetFrame) {
                                bestKeyframe = kf;
                            } else {
                                break;
                            }
                        }
                    }

                    // 2. 判断是否需要 Reset
                    // 如果需要回退，或者相距超过 30 帧，则重置
                    boolean needReset = (targetFrame < currentFrameIdx) || (targetFrame - currentFrameIdx > 30);

                    if (needReset) {
                        forceResetToFrame = bestKeyframe;
                        fastForwardTo = targetFrame;
                    } else {
                        // 只需要快进
                        fastForwardTo = targetFrame;
                    }
                }

                // 处理强制重置 (Seek 到关键帧)
                if (forceResetToFrame != -1) {
                    int resetPoint = forceResetToFrame;
                    forceResetToFrame = -1;

                    // 1. 物理 Seek
                    if (resetPoint >= 0 && resetPoint < totalFrames) {
                        currentFrameIdx = resetPoint;
                        long offset = frameOffsets[currentFrameIdx];
                        channel.position(offset);

                        // 2. 状态清零 (I-Frame 特性：不依赖旧状态)
                        stateMap.clear();
                        // 清空管道里的旧数据
                        gpuBufferQueue.forEach(NblStreamer::releaseBuffer);
                        gpuBufferQueue.clear();
                    }
                }

                if (currentFrameIdx >= totalFrames) {
                    // 已经是 EOF 了，但可能因为 seek 重置了 currentFrameIdx，所以需要再次检查
                    break;
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

                // 处理快进 (只运算逻辑，不输出 Buffer)
                // [优化] 如果 fastForwardTo 被设置，且当前帧 < fastForwardTo，则 isSkipping = true
                boolean isSkipping = (fastForwardTo != -1) && (currentFrameIdx < fastForwardTo);

                // 如果已经追上了，取消快进标记
                if (currentFrameIdx == fastForwardTo) {
                    fastForwardTo = -1;
                    isSkipping = false;
                }

                // 处理帧数据
                ByteBuffer gpuBuffer = processFrameData(cachedDecompressedBuffer, isSkipping, currentFrameIdx);

                // 只有不跳过的时候，才塞入队列
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
                    // 等待 Seek 信号或退出
                    while (isRunning.get() && seekTargetFrame == -1) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (seekTargetFrame != -1) {
                        isFinished = false;
                        // 移除这里的 EOF 标记防止重复？
                        // 实际上 blocking queue 里的 EOF 已经被 consumer 读走或者还在那里。
                        // 如果 seek 重启，queue 会在 reset 时 clear。
                    }
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

        // 【阶段 1】单线程解析并更新状态
        // 创建状态快照数组用于并行写入
        // 注意：ParticleState 是可变对象，需要复制值
        float[][] stateSnapshots = null;
        if (gpuBuffer != null) {
            stateSnapshots = new float[particleCount][12]; // prevX,prevY,prevZ,size,x,y,z,r,g,b,a,texLayer
        }

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

            // 保存状态快照
            if (gpuBuffer != null) {
                stateSnapshots[i][0] = p.prevX;
                stateSnapshots[i][1] = p.prevY;
                stateSnapshots[i][2] = p.prevZ;
                stateSnapshots[i][3] = p.size;
                stateSnapshots[i][4] = p.x;
                stateSnapshots[i][5] = p.y;
                stateSnapshots[i][6] = p.z;
                stateSnapshots[i][7] = p.r;
                stateSnapshots[i][8] = p.g;
                stateSnapshots[i][9] = p.b;
                stateSnapshots[i][10] = p.a;
                stateSnapshots[i][11] = ParticleTextureManager.calculateLayerIndex(p.texID, p.seqID);
            }
        }

        // 【阶段 2】并行写入 GPU Buffer
        if (gpuBuffer != null && stateSnapshots != null) {
            final long bufferAddr = MemoryUtil.memAddress(gpuBuffer);
            final float[][] snapshots = stateSnapshots; // final 引用供 lambda 使用

            if (particleCount >= PARALLEL_THRESHOLD) {
                // 并行写入（粒子数较多时有收益）
                // 使用自定义的 ForkJoinPool，避免阻塞默认的 commonPool
                try {
                    PARALLEL_POOL.submit(() -> {
                        IntStream.range(0, particleCount).parallel().forEach(i -> {
                            writeParticleToGpuAbsFromSnapshot(bufferAddr, i, snapshots[i]);
                        });
                    }).get(); // 等待完成
                } catch (Exception e) {
                    // 并行执行失败，降级到顺序写入
                    for (int i = 0; i < particleCount; i++) {
                        writeParticleToGpuAbsFromSnapshot(bufferAddr, i, snapshots[i]);
                    }
                }
            } else {
                // 顺序写入（粒子数较少时避免并行开销）
                for (int i = 0; i < particleCount; i++) {
                    writeParticleToGpuAbsFromSnapshot(bufferAddr, i, snapshots[i]);
                }
            }
        }
    }

    /**
     * 从状态快照写入 GPU 缓冲区（用于并行写入）
     * 直接使用内存地址，无需 ByteBuffer 对象
     */
    private void writeParticleToGpuAbsFromSnapshot(long bufferAddr, int index, float[] snapshot) {
        long addr = bufferAddr + index * 48L;

        // === Vec4 #1: PrevPos(xyz) + Size(w) ===
        MemoryUtil.memPutFloat(addr, snapshot[0]); // prevX
        MemoryUtil.memPutFloat(addr + 4, snapshot[1]); // prevY
        MemoryUtil.memPutFloat(addr + 8, snapshot[2]); // prevZ
        MemoryUtil.memPutFloat(addr + 12, snapshot[3]); // size

        // === Vec4 #2: CurPos(xyz) + Color(w) ===
        MemoryUtil.memPutFloat(addr + 16, snapshot[4]); // x
        MemoryUtil.memPutFloat(addr + 20, snapshot[5]); // y
        MemoryUtil.memPutFloat(addr + 24, snapshot[6]); // z

        // 颜色压缩 (RGBA 4 bytes -> 1 int)
        int colorPacked = ((int) snapshot[10] << 24) | ((int) snapshot[9] << 16) | ((int) snapshot[8] << 8)
                | (int) snapshot[7];
        MemoryUtil.memPutInt(addr + 28, colorPacked);

        // === Vec4 #3: TexLayer + Padding ===
        MemoryUtil.memPutFloat(addr + 32, snapshot[11]); // texLayer
        MemoryUtil.memPutFloat(addr + 36, 0f);
        MemoryUtil.memPutFloat(addr + 40, 0f);
        MemoryUtil.memPutFloat(addr + 44, 0f);
    }

    /**
     * 将粒子数据写入 GPU 缓冲区 (SSBO std430 格式)
     * <p>
     * 【性能优化】使用 MemoryUtil.memPutFloat/memPutInt 直接内存操作
     * 相比 ByteBuffer.putFloat()，消除了 Java NIO 的边界检查开销
     * 对于每帧处理 20,000+ 粒子，CPU 占用显著降低
     * </p>
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

        // 越界检查（保留一次检查，避免崩溃）
        if (offset + 48 > buf.capacity()) {
            if (index == 0)
                Nebula.LOGGER.error("Buffer overflow! Cap: {}, Req: {}", buf.capacity(), offset + 48);
            return;
        }

        // 获取 Buffer 的内存地址（只需获取一次）
        long addr = MemoryUtil.memAddress(buf) + offset;

        // === Vec4 #1: PrevPos(xyz) + Size(w) ===
        MemoryUtil.memPutFloat(addr, p.prevX);
        MemoryUtil.memPutFloat(addr + 4, p.prevY);
        MemoryUtil.memPutFloat(addr + 8, p.prevZ);
        MemoryUtil.memPutFloat(addr + 12, p.size);

        // === Vec4 #2: CurPos(xyz) + Color(w) ===
        MemoryUtil.memPutFloat(addr + 16, p.x);
        MemoryUtil.memPutFloat(addr + 20, p.y);
        MemoryUtil.memPutFloat(addr + 24, p.z);

        // 颜色压缩 (RGBA 4 bytes -> 1 int)
        // Little Endian: ABGR in int memory (but GL reads bytes directly)
        int colorPacked = (p.a << 24) | (p.b << 16) | (p.g << 8) | p.r;
        MemoryUtil.memPutInt(addr + 28, colorPacked);

        // === Vec4 #3: TexID, SeqID, Padding... ===
        // 预计算 Texture Layer
        float layerIndex = ParticleTextureManager.calculateLayerIndex(p.texID, p.seqID);
        MemoryUtil.memPutFloat(addr + 32, layerIndex);
        MemoryUtil.memPutFloat(addr + 36, 0f); // Padding
        MemoryUtil.memPutFloat(addr + 40, 0f); // Padding
        MemoryUtil.memPutFloat(addr + 44, 0f); // Padding
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
     * 获取动画的最小边界点 (相对于原点)
     * 用于视锥剔除
     */
    public float[] getBboxMin() {
        return bboxMin;
    }

    /**
     * 获取动画的最大边界点 (相对于原点)
     * 用于视锥剔除
     */
    public float[] getBboxMax() {
        return bboxMax;
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
     * 归还 Buffer 到池中
     * 必须在渲染完成后调用！
     * 
     * @param buf 要归还的 Buffer
     */
    public static void releaseBuffer(ByteBuffer buf) {
        // EOF buffer (cap=0) 不回收，其他回收
        if (buf != null && buf.capacity() > 0) {
            buf.clear();
            freeBuffers.offer(buf);
        }
    }
}