import os
import struct
import time
import zstandard as zstd
import glob

# ================= 配置区域 =================
# 是否覆盖源文件？ (建议先设为 False，测试无误后再设为 True)
OVERWRITE_SOURCE = False 

# 如果不覆盖，新文件的后缀名
NEW_FILE_SUFFIX = "_v2.nbl"

# ================= 常量定义 =================
MAGIC_NUMBER = b'NEBULAFX'
HEADER_SIZE = 48
UINT16_SIZE = 2
UINT32_SIZE = 4
UINT64_SIZE = 8
FRAME_INDEX_ENTRY_SIZE = 12  # uint64 offset + uint32 size

def log(msg):
    timestamp = time.strftime("%H:%M:%S", time.localtime())
    print(f"[{timestamp}] {msg}")

def read_uint16(f):
    return struct.unpack('<H', f.read(2))[0]

def read_uint32(f):
    return struct.unpack('<I', f.read(4))[0]

def convert_nbl(file_path):
    file_size = os.path.getsize(file_path)
    log(f"正在处理文件: {file_path} ({file_size / 1024 / 1024:.2f} MB)")
    
    start_time = time.time()
    
    # 临时输出文件
    temp_out_path = file_path + ".tmp"
    
    try:
        with open(file_path, 'rb') as src, open(temp_out_path, 'wb') as dst:
            
            # --- 1. 处理文件头 (Header) ---
            header_bytes = src.read(HEADER_SIZE)
            if len(header_bytes) < HEADER_SIZE:
                log("错误: 文件头不完整")
                return False
                
            magic = header_bytes[0:8]
            if magic != MAGIC_NUMBER:
                log("错误: 不是有效的 NBL 文件 (Magic Number mismatch)")
                return False
                
            version = struct.unpack('<H', header_bytes[8:10])[0]
            if version >= 2:
                log(f"跳过: 文件版本已经是 {version}，无需转换")
                return True # 视为成功
            
            log(f"当前版本: v{version} -> 目标版本: v2")
            
            # 读取一些元数据用于后续处理
            total_frames = struct.unpack('<I', header_bytes[12:16])[0]
            texture_count = struct.unpack('<H', header_bytes[16:18])[0]
            
            log(f"动画总帧数: {total_frames}, 纹理数: {texture_count}")

            # 修改 Version 字段为 2
            new_header = bytearray(header_bytes)
            struct.pack_into('<H', new_header, 8, 1)
            dst.write(new_header)
            
            # --- 2. 复制纹理块 (Texture Block) ---
            # 我们需要解析它以确定它的长度，因为它是变长的
            # 同时也需要直接写入到目标文件
            
            log("正在复制纹理定义块...")
            # 记录纹理块开始位置
            tex_block_start = src.tell()
            
            for _ in range(texture_count):
                # path length
                path_len = read_uint16(src)
                # path
                src.read(path_len)
                # rows (1) + cols (1)
                src.read(2)
            
            tex_block_end = src.tell()
            tex_block_size = tex_block_end - tex_block_start
            
            # 回退并复制数据
            src.seek(tex_block_start)
            dst.write(src.read(tex_block_size))
            
            # --- 3. 读取旧的帧索引表 (Frame Index Table) ---
            log("正在读取旧帧索引表...")
            old_index_table_size = total_frames * FRAME_INDEX_ENTRY_SIZE
            old_index_table_bytes = src.read(old_index_table_size)
            
            if len(old_index_table_bytes) != old_index_table_size:
                log("错误: 帧索引表数据不完整")
                return False
                
            # 解析旧索引表，我们需要 offset 和 size 来扫描关键帧
            # 格式: List of (offset, size)
            frame_indices = []
            for i in range(total_frames):
                base = i * FRAME_INDEX_ENTRY_SIZE
                offset = struct.unpack('<Q', old_index_table_bytes[base:base+8])[0]
                size = struct.unpack('<I', old_index_table_bytes[base+8:base+12])[0]
                frame_indices.append((offset, size))
            
            # --- 4. 扫描关键帧 (Pass 1: Analysis) ---
            log("正在扫描关键帧 (这可能需要一点时间)...")
            keyframes = []
            dctx = zstd.ZstdDecompressor()
            
            scan_start_time = time.time()
            
            for i, (offset, size) in enumerate(frame_indices):
                # 定位到帧数据开始
                src.seek(offset)
                
                # 读取压缩数据
                # 优化：通常 Zstd 解压第一个字节不需要读取整个巨大的 Chunk
                # 但为了安全起见，读取整个 Chunk 是最稳妥的，反正内存是一帧一帧释放的
                compressed_data = src.read(size)
                
                # 解压第一个字节 (FrameType)
                # 使用 stream_reader 防止解压整个 payload 浪费 CPU
                try:
                    with dctx.stream_reader(compressed_data) as reader:
                        first_byte = reader.read(1)
                        if first_byte and first_byte[0] == 0: # 0 = I-Frame
                            keyframes.append(i)
                except Exception as e:
                    log(f"警告: 第 {i} 帧解压失败: {e}")
                    return False
                
                if i % 100 == 0 and i > 0:
                    print(f"\r扫描进度: {i}/{total_frames} ({(i/total_frames)*100:.1f}%) - 发现关键帧: {len(keyframes)}", end='', flush=True)

            print(f"\r扫描进度: {total_frames}/{total_frames} (100.0%) - 发现关键帧: {len(keyframes)}")
            log(f"关键帧扫描完成，耗时 {time.time() - scan_start_time:.2f}秒")
            
            # --- 5. 构建并写入新的表 ---
            
            # 计算新加入的关键帧表的大小
            # Count(4) + Indices(Count * 4)
            keyframe_table_size = 4 + (len(keyframes) * 4)
            
            log(f"正在写入新表 (位移偏移量: +{keyframe_table_size} bytes)...")
            
            # A. 写入修正后的帧索引表
            # 所有旧的 Offset 都要加上 keyframe_table_size
            for offset, size in frame_indices:
                new_offset = offset + keyframe_table_size
                dst.write(struct.pack('<Q', new_offset))
                dst.write(struct.pack('<I', size))
                
            # B. 写入新的关键帧索引表 (v2.0 Feature)
            # Count
            dst.write(struct.pack('<I', len(keyframes)))
            # Indices
            for kf_idx in keyframes:
                dst.write(struct.pack('<I', kf_idx))
                
            # --- 6. 搬运帧数据 (Pass 2: Copying) ---
            log("正在搬运帧数据...")
            copy_start_time = time.time()
            
            total_data_bytes = 0
            
            for i, (offset, size) in enumerate(frame_indices):
                src.seek(offset)
                # 直接读取原始压缩数据写入新文件 (Zero-decode)
                # 使用 buffer 拷贝防止大帧爆内存 (虽然一般帧不大)
                chunk_data = src.read(size)
                dst.write(chunk_data)
                total_data_bytes += size
                
                if i % 200 == 0:
                     print(f"\r搬运进度: {i}/{total_frames}", end='', flush=True)
            
            print(f"\r搬运进度: {total_frames}/{total_frames}")
            log(f"数据搬运完成，共 {total_data_bytes / 1024 / 1024:.2f} MB")

    except Exception as e:
        log(f"严重错误: {e}")
        # 清理临时文件
        if os.path.exists(temp_out_path):
            os.remove(temp_out_path)
        return False

    # --- 7. 收尾工作 ---
    elapsed = time.time() - start_time
    log(f"文件转换成功! 总耗时: {elapsed:.2f}秒")
    
    # 重命名/覆盖逻辑
    if OVERWRITE_SOURCE:
        # 备份原文件
        bak_path = file_path + ".bak"
        if os.path.exists(bak_path):
            os.remove(bak_path)
        os.rename(file_path, bak_path)
        os.rename(temp_out_path, file_path)
        log(f"原文件已备份为 .bak，新文件已覆盖: {file_path}")
    else:
        # 生成新文件
        final_path = os.path.splitext(file_path)[0] + NEW_FILE_SUFFIX
        if os.path.exists(final_path):
            os.remove(final_path)
        os.rename(temp_out_path, final_path)
        log(f"新文件已保存为: {final_path}")
        
    log("-" * 50)
    return True

def main():
    print("=" * 50)
    print("    NEBULA NBL 格式转换器")
    print("=" * 50)
    
    # 获取当前目录下所有 .nbl 文件
    files = glob.glob("*.nbl")
    # 排除掉已经是 _v2 的文件防止重复转换
    files = [f for f in files if not f.endswith(NEW_FILE_SUFFIX)]
    
    if not files:
        log("未找到 .nbl 文件。请将此脚本放在包含 .nbl 文件的目录下。")
    else:
        log(f"找到 {len(files)} 个待处理文件。")
        print("-" * 50)
        
        success_count = 0
        for f in files:
            if convert_nbl(f):
                success_count += 1
                
        print("=" * 50)
        log(f"全部任务结束。成功: {success_count}，失败: {len(files) - success_count}")

    # 结束前暂停，防止窗口关闭
    input("\n按 Enter 键退出...")

if __name__ == "__main__":
    main()
