package com.atemukesu.nebula.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * 升级版：跨步式采样校验工具
 * 适用于 10MB - 1GB 的超大动画文件同步
 */
public class NebulaHashUtils {

    private static final int CHUNK_SIZE = 8192; // 提升单块采样到 8KB
    private static final int TOTAL_SAMPLES = 10; // 全文件等距采样 10 个点

    /**
     * 跨步采样哈希：在文件中等距选取 10 个点进行校验
     * 无论文件多大，只读取约 80KB 数据，平衡了覆盖率与速度
     * 
     * @param path 文件路径
     * @return 16进制特征字符串，格式为 "文件大小-采样指纹" 或者 "F-文件大小-采样指纹"
     * @throws IOException 如果文件读取失败
     */
    public static String getSecureSampleHash(Path path) throws IOException {
        long fileSize = path.toFile().length();

        // 如果文件太小（小于 100KB），直接全量校验
        if (fileSize <= CHUNK_SIZE * TOTAL_SAMPLES) {
            return "F-" + getFullFileCRC32(path);
        }

        CRC32 crc = new CRC32();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            byte[] buffer = new byte[CHUNK_SIZE];

            // 计算步长：将文件平均分成 10 份
            long step = (fileSize - CHUNK_SIZE) / (TOTAL_SAMPLES - 1);

            for (int i = 0; i < TOTAL_SAMPLES; i++) {
                raf.seek(i * step);
                raf.readFully(buffer);
                crc.update(buffer);
            }
        }

        // 返回包含文件大小和采样指纹的组合
        return Long.toHexString(fileSize) + "-" + Long.toHexString(crc.getValue());
    }

    /**
     * 对小文件进行全量 CRC32 校验
     * 
     * @param path 文件路径
     * @return 16进制特征字符串
     * @throws IOException 如果文件读取失败
     */
    private static String getFullFileCRC32(Path path) throws IOException {
        CRC32 crc = new CRC32();
        byte[] allBytes = java.nio.file.Files.readAllBytes(path);
        crc.update(allBytes);
        return Long.toHexString(allBytes.length) + "-" + Long.toHexString(crc.getValue());
    }
}