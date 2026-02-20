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