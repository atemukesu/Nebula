/**
 * [AI GENERATION & MODIFICATION NOTICE / AI 编写与调整声明]
 *
 * ENGLISH:
 * This code was authored, modified, optimized, or adjusted by one or more of the
 * following AI models: Gemini 3 Pro, Gemini 3 Flash, and Claude 3.5 Opus.
 * Although efforts have been made to ensure functionality through testing, the
 * code is provided "AS IS". Please perform a thorough code audit before using,
 * reading, distributing, or modifying.
 *
 * 中文版：
 * 本代码由以下一个或多个 AI 模型编写、修改、优化或调整：
 * Gemini 3 Pro, Gemini 3 Flash, 以及 Claude 3.5 Opus。
 * 代码虽经努力测试以确保其功能实现，但仍按“原样”提供。在您进行使用、阅读、
 * 分发或修改前，请务必进行仔细的代码审计与测试。
 *
 * ----------------------------------------------------------------------------------
 * [LICENSE & WARRANTY / 开源协议与免责声明]
 *
 * ENGLISH:
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details <https://www.gnu.org/licenses/>.
 *
 * 中文版：
 * 本程序为自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可协议（GPL）条款
 *（可以选择版本 3 或更高版本）对本程序进行重新分发和/或修改。
 *
 * 本程序的发布是希望其能发挥作用，但【不附带任何担保】，甚至不包括对【适销性】或
 * 【特定用途适用性】的暗示保证。开发者不对因使用本代码产生的任何损害承担责任。
 * 详情请参阅 GNU 通用公共许可协议官方页面 <https://www.gnu.org/licenses/>。
 * ----------------------------------------------------------------------------------
 */

package com.atemukesu.nebula.client.loader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.render.ParticleTextureManager;
import com.atemukesu.nebula.client.render.SharedTextureResource;
import com.atemukesu.nebula.client.render.TextureAtlasMap;
import com.atemukesu.nebula.client.render.TextureCacheSystem;
import com.atemukesu.nebula.particle.data.NblHeader;
import com.github.luben.zstd.Zstd;
import net.minecraft.client.MinecraftClient;
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
import java.util.Arrays;
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

    // 持有资源引用
    private final SharedTextureResource textureResource;

    // 依然持有 Map 的快捷引用 (为了性能，不用每次都去 resource.getMap())
    private final TextureAtlasMap textureMap;

    // [优化] 使用数组 (Structure of Arrays) 代替 HashMap，提升 CPU 缓存命中率
    // 默认分配 100w 粒子容量，避免频繁扩容
    private final ParticleStateData state = new ParticleStateData(1_050_000);

    private final BlockingQueue<ByteBuffer> gpuBufferQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private volatile boolean isFinished = false;

    // 复用缓冲区 (Zero-Allocation)
    private ByteBuffer cachedDecompressedBuffer = null;
    private ByteBuffer cachedCompressedBuffer = null;

    // GPU Buffer Pool (智能对象池)
    private static final int INITIAL_BUFFER_SIZE = 1 * 1024 * 1024; // 1MB
    private static final int MAX_BUFFER_CAPACITY = 8 * 1024 * 1024; // 8MB
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> freeBuffers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong totalAllocatedMemory = new java.util.concurrent.atomic.AtomicLong(
            0);

    private volatile int seekTargetFrame = -1;
    // [新增字段] 用于 runImpl 内部通信
    private volatile int forceResetToFrame = -1;
    private volatile int fastForwardTo = -1;

    private static final int QUEUE_CAPACITY = 10;

    // 并行处理相关
    private static final ForkJoinPool PARALLEL_POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
    private static final int PARALLEL_THRESHOLD = 5000;

    public NblStreamer(File nblFile, SharedTextureResource resource) throws IOException {
        this.file = nblFile;
        this.textureResource = resource;
        // 增加引用计数 (Streamer 开始使用)
        if (this.textureResource != null) {
            this.textureResource.grab();
        }

        // 从资源中获取 Map
        this.textureMap = resource != null ? resource.getMap() : TextureAtlasMap.EMPTY;

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
                // Skip path bytes + rows (1 byte) + cols (1 byte)
                raf.skipBytes(pathLen + 2);
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

    /**
     * 静态辅助方法：仅预扫描纹理列表 (不创建 Streamer 实例)
     * 用于在主线程提前加载纹理并生成 Map
     */
    public static List<ParticleTextureManager.TextureEntry> preScanTextures(File file) throws IOException {
        List<ParticleTextureManager.TextureEntry> entries = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            ByteBuffer headerBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            raf.readFully(headerBuf.array()); // Read up to textureCount

            byte[] magic = new byte[8];
            headerBuf.get(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("NEBULAFX")) {
                throw new IOException("Invalid NBL file");
            }

            int textureCount = headerBuf.getShort(16) & 0xFFFF;

            raf.seek(48);

            for (int i = 0; i < textureCount; i++) {
                int pathLen = readUnsignedShortLE_Static(raf);
                byte[] pathBytes = new byte[pathLen];
                raf.readFully(pathBytes);
                entries.add(new ParticleTextureManager.TextureEntry(
                        new String(pathBytes, StandardCharsets.UTF_8),
                        raf.read() & 0xFF, raf.read() & 0xFF));
            }
        }
        return entries;
    }

    private static int readUnsignedShortLE_Static(RandomAccessFile raf) throws IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        return (b2 << 8) | b1;
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
                        state.clear();
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
        final int baseOffset = data.position();
        int N = particleCount;
        final int xOff = baseOffset;
        final int yOff = xOff + (N * 4);
        final int zOff = yOff + (N * 4);
        final int rOff = zOff + (N * 4);
        final int gOff = rOff + N;
        final int bOff = gOff + N;
        final int aOff = bOff + N;
        final int sizeOff = aOff + N;
        final int texOff = sizeOff + (N * 2);
        final int seqOff = texOff + N;
        final int idOff = seqOff + N;

        long bufferAddr = (gpuBuffer != null) ? MemoryUtil.memAddress(gpuBuffer) : 0;

        // 1. 预扫描最大 ID
        int maxId = -1;
        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);
            if (id > maxId)
                maxId = id;
        }
        state.ensureCapacity(maxId);

        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);

            state.lastSeenFrame[id] = frameIdx;

            float nx = data.getFloat(xOff + i * 4);
            float ny = data.getFloat(yOff + i * 4);
            float nz = data.getFloat(zOff + i * 4);

            state.x[id] = nx;
            state.y[id] = ny;
            state.z[id] = nz;
            state.prevX[id] = nx;
            state.prevY[id] = ny;
            state.prevZ[id] = nz;

            state.r[id] = data.get(rOff + i);
            state.g[id] = data.get(gOff + i);
            state.b[id] = data.get(bOff + i);
            state.a[id] = data.get(aOff + i);
            state.size[id] = Math.max(0.01f, (data.getShort(sizeOff + i * 2) & 0xFFFF) / 100.0f);
            state.tex[id] = data.get(texOff + i);
            state.seq[id] = data.get(seqOff + i);

            if (bufferAddr != 0)
                writeParticleToGpuDirect(bufferAddr, i, id, state);
        }
    }

    private void processPFrame(ByteBuffer data, int particleCount, ByteBuffer gpuBuffer, int frameIdx) {
        final int baseOffset = data.position();
        int N = particleCount;
        // P-Frame 偏移计算 (final for lambda)
        final int dxOff = baseOffset;
        final int dyOff = dxOff + (N * 2);
        final int dzOff = dyOff + (N * 2);
        final int drOff = dzOff + (N * 2);
        final int dgOff = drOff + N;
        final int dbOff = dgOff + N;
        final int daOff = dbOff + N;
        final int sizeOff = daOff + N;
        final int texOff = sizeOff + (N * 2);
        final int seqOff = texOff + N;
        final int idOff = seqOff + N;

        final long bufferAddr = (gpuBuffer != null) ? MemoryUtil.memAddress(gpuBuffer) : 0;

        // 1. 预扫描最大 ID，解决并行流扩容的竞态条件 (Race Condition)
        int maxId = -1;
        for (int i = 0; i < particleCount; i++) {
            int id = data.getInt(idOff + i * 4);
            if (id > maxId)
                maxId = id;
        }
        state.ensureCapacity(maxId);

        // 定义处理逻辑 (Lambda)
        java.util.function.IntConsumer processParticle = i -> {
            int id = data.getInt(idOff + i * 4);

            boolean isSpawn = (state.lastSeenFrame[id] != frameIdx - 1);
            if (isSpawn) {
                state.x[id] = 0;
                state.y[id] = 0;
                state.z[id] = 0;
                state.r[id] = 0;
                state.g[id] = 0;
                state.b[id] = 0;
                state.a[id] = 0;
                state.size[id] = 0;
                state.tex[id] = 0;
                state.seq[id] = 0;
            }
            state.lastSeenFrame[id] = frameIdx;

            float oldX = state.x[id], oldY = state.y[id], oldZ = state.z[id];

            // 应用增量 (Short / 1000.0) -> 注意这里是 +=
            state.x[id] += data.getShort(dxOff + i * 2) / 1000.0f;
            state.y[id] += data.getShort(dyOff + i * 2) / 1000.0f;
            state.z[id] += data.getShort(dzOff + i * 2) / 1000.0f;

            // Color updates
            state.r[id] += data.get(drOff + i);
            state.g[id] += data.get(dgOff + i);
            state.b[id] += data.get(dbOff + i);

            // Alpha clamping needed
            int currentAlpha = state.a[id] & 0xFF;
            int deltaAlpha = data.get(daOff + i);
            int newAlpha = Math.max(0, Math.min(255, currentAlpha + deltaAlpha));
            state.a[id] = (byte) newAlpha;

            // Size min check
            state.size[id] = Math.max(0.01f, state.size[id] + data.getShort(sizeOff + i * 2) / 100.0f);

            state.tex[id] += data.get(texOff + i);
            state.seq[id] += data.get(seqOff + i);

            if (isSpawn) {
                state.prevX[id] = state.x[id];
                state.prevY[id] = state.y[id];
                state.prevZ[id] = state.z[id];
            } else {
                state.prevX[id] = oldX;
                state.prevY[id] = oldY;
                state.prevZ[id] = oldZ;
            }

            // 直接写入 GPU Buffer
            if (bufferAddr != 0) {
                writeParticleToGpuDirect(bufferAddr, i, id, state);
            }
        };

        // 决策：并行 vs 串行
        if (particleCount >= PARALLEL_THRESHOLD) {
            try {
                // 使用 ForkJoinPool 执行并行流
                PARALLEL_POOL.submit(() -> IntStream.range(0, particleCount).parallel().forEach(processParticle)).get(); // 阻塞等待完成
            } catch (Exception e) {
                Nebula.LOGGER.error("Parallel processing failed", e);
                // Fallback (though rare)
                IntStream.range(0, particleCount).forEach(processParticle);
            }
        } else {
            // 少量粒子直接串行
            for (int i = 0; i < particleCount; i++) {
                processParticle.accept(i);
            }
        }
    }

    /**
     * 将粒子数据直接写入 GPU 缓冲区 (SSBO std430 格式)
     * 使用直接内存地址访问，避免 ByteBuffer 开销
     */
    private void writeParticleToGpuDirect(long baseAddr, int index, int id, ParticleStateData state) {
        long addr = baseAddr + index * 48L;

        // === Vec4 #1: PrevPos(xyz) + Size(w) ===
        MemoryUtil.memPutFloat(addr, state.prevX[id]);
        MemoryUtil.memPutFloat(addr + 4, state.prevY[id]);
        MemoryUtil.memPutFloat(addr + 8, state.prevZ[id]);
        MemoryUtil.memPutFloat(addr + 12, state.size[id]);

        // === Vec4 #2: CurPos(xyz) + Color(w) ===
        MemoryUtil.memPutFloat(addr + 16, state.x[id]);
        MemoryUtil.memPutFloat(addr + 20, state.y[id]);
        MemoryUtil.memPutFloat(addr + 24, state.z[id]);

        // 颜色压缩 (RGBA 4 bytes -> 1 int)
        // Little Endian: R, G, B, A (0xAA BB GG RR for int value if low byte is R)
        // GLSL reads: (r, g, b, a) from low to high bytes.
        // So we need to put: R at +0, G at +1, B at +2, A at +3.
        // memPutInt (assuming Little Endian CPU): (A << 24 | B << 16 | G << 8 | R)
        int colorPacked = ((state.a[id] & 0xFF) << 24) |
                ((state.b[id] & 0xFF) << 16) |
                ((state.g[id] & 0xFF) << 8) |
                (state.r[id] & 0xFF);
        MemoryUtil.memPutInt(addr + 28, colorPacked);

        // === Vec4 #3: TexID, SeqID, Padding... ===
        // 使用私有的 textureMap 查询，无需访问全局静态类，无需 volatile 开销
        float layerIndex = textureMap.getLayer(state.tex[id] & 0xFF, state.seq[id] & 0xFF);
        MemoryUtil.memPutFloat(addr + 32, layerIndex);
        MemoryUtil.memPutFloat(addr + 36, 0f); // Padding
        MemoryUtil.memPutFloat(addr + 40, 0f); // Padding
        MemoryUtil.memPutFloat(addr + 44, 0f); // Padding
    }

    // 内部类：粒子状态数据 (SoA Layout)
    private static class ParticleStateData {
        public float[] x, y, z;
        public float[] prevX, prevY, prevZ;
        public float[] size;
        public byte[] r, g, b, a;
        public byte[] tex, seq;
        public int[] lastSeenFrame;

        private int capacity;

        public ParticleStateData(int initialCapacity) {
            this.capacity = initialCapacity;
            allocate(capacity);
        }

        private void allocate(int cap) {
            x = new float[cap];
            y = new float[cap];
            z = new float[cap];
            prevX = new float[cap];
            prevY = new float[cap];
            prevZ = new float[cap];
            size = new float[cap];
            r = new byte[cap];
            g = new byte[cap];
            b = new byte[cap];
            a = new byte[cap];
            tex = new byte[cap];
            seq = new byte[cap];
            lastSeenFrame = new int[cap];
            Arrays.fill(lastSeenFrame, -1);
        }

        public void ensureCapacity(int id) {
            if (id >= capacity) {
                synchronized (this) {
                    if (id >= capacity) {
                        int newCap = Math.max(capacity * 2, id + 4096);
                        resize(newCap);
                    }
                }
            }
        }

        private void resize(int newCap) {
            Nebula.LOGGER.warn("Resizing ParticleStateData from {} to {}", capacity, newCap);
            x = Arrays.copyOf(x, newCap);
            y = Arrays.copyOf(y, newCap);
            z = Arrays.copyOf(z, newCap);
            prevX = Arrays.copyOf(prevX, newCap);
            prevY = Arrays.copyOf(prevY, newCap);
            prevZ = Arrays.copyOf(prevZ, newCap);
            size = Arrays.copyOf(size, newCap);
            r = Arrays.copyOf(r, newCap);
            g = Arrays.copyOf(g, newCap);
            b = Arrays.copyOf(b, newCap);
            a = Arrays.copyOf(a, newCap);
            tex = Arrays.copyOf(tex, newCap);
            seq = Arrays.copyOf(seq, newCap);
            int oldCap = capacity;
            lastSeenFrame = Arrays.copyOf(lastSeenFrame, newCap);
            Arrays.fill(lastSeenFrame, oldCap, newCap, -1);
            capacity = newCap;
        }

        public void clear() {
            Arrays.fill(lastSeenFrame, -1);
        }
    }

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
            
        // 【关键】通知主线程释放纹理引用
        if (this.textureResource != null) {
            MinecraftClient.getInstance().execute(() -> {
                TextureCacheSystem.release(this.textureResource);
            });

    }}

    public BlockingQueue<ByteBuffer> getQueue() {
        return gpuBufferQueue;
    }

    public void loadTextures() {
        // Logically this is now handled externally before Streamer is created.
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
            // [Fix] 防止池中保留过大的缓冲区导致内存无法释放
            if (buf.capacity() > MAX_BUFFER_CAPACITY) {
                totalAllocatedMemory.addAndGet(-buf.capacity());
                // Let GC handle it
                return;
            }
            buf.clear();
            freeBuffers.offer(buf);
        }
    }
}