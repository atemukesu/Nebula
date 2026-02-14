import struct
import zstandard as zstd
import argparse
import os
import sys
import questionary
from tqdm import tqdm

# ==========================================
# NBL 规范定义
# ==========================================
MAGIC = b'NEBULAFX'
HEADER_SIZE = 48
ATTR_HAS_SIZE = 0x02
QUANTIZE_SCALE = 100.0  # 文档 6.B: 大小有 100x 缩放

class NBLResizer:
    def __init__(self, input_path):
        self.input_path = input_path
        self.data = bytearray()
        self.meta = {}
        
    def load(self):
        if not os.path.exists(self.input_path):
            raise FileNotFoundError(f"文件未找到: {self.input_path}")
            
        with open(self.input_path, 'rb') as f:
            self.data = bytearray(f.read())
            
        if len(self.data) < HEADER_SIZE:
            raise ValueError("文件头错误")
            
        if self.data[0:8] != MAGIC:
            raise ValueError("Magic 校验失败")
            
        # 解析必要元数据
        self.meta['total_frames'] = struct.unpack_from('<I', self.data, 0x0C)[0]
        self.meta['tex_count'] = struct.unpack_from('<H', self.data, 0x10)[0]
        self.meta['attrs'] = struct.unpack_from('<H', self.data, 0x12)[0]
        
        if not (self.meta['attrs'] & ATTR_HAS_SIZE):
            print("⚠️ 警告: 该文件 Attributes 声明不包含 Size 数据，处理可能无效。")

    def process(self, output_path, mode, value):
        """
        mode: 'scale' (缩放倍率, float) 或 'fixed' (固定大小, float)
        value: 用户输入的浮点数 (e.g. 1.5)
        """
        # 1. 跳过头部和纹理块，定位索引表
        offset = HEADER_SIZE
        for _ in range(self.meta['tex_count']):
            path_len = struct.unpack_from('<H', self.data, offset)[0]
            offset += 2 + path_len + 2
        
        frame_index_start = offset
        frame_index_size = self.meta['total_frames'] * 12
        offset += frame_index_size
        
        # 跳过关键帧表
        kf_count = struct.unpack_from('<I', self.data, offset)[0]
        offset += 4 + (kf_count * 4)
        
        chunks_start_offset = offset
        
        # 2. 构建新文件结构
        new_data = bytearray()
        new_data.extend(self.data[:frame_index_start])
        frame_index_placeholder = len(new_data)
        new_data.extend(b'\x00' * frame_index_size) # 占位
        new_data.extend(self.data[frame_index_start + frame_index_size : chunks_start_offset])
        
        # 3. 逐帧处理
        dctx = zstd.ZstdDecompressor()
        cctx = zstd.ZstdCompressor()
        
        old_index_table = self.data[frame_index_start : frame_index_start + frame_index_size]
        new_index_table = bytearray()
        current_write_pos = len(new_data)
        
        print(f"[*] 正在处理: 模式={mode}, 值={value}")
        
        # 进度条
        for i in tqdm(range(self.meta['total_frames']), unit="frame", colour="cyan"):
            # 读旧索引
            idx_ptr = i * 12
            c_offset = struct.unpack_from('<Q', old_index_table, idx_ptr)[0]
            c_size = struct.unpack_from('<I', old_index_table, idx_ptr + 8)[0]
            
            # 解压
            raw_chunk = dctx.decompress(self.data[c_offset : c_offset + c_size])
            mutable_chunk = bytearray(raw_chunk)
            
            # 解析帧头
            f_type = mutable_chunk[0]
            p_count = struct.unpack_from('<I', mutable_chunk, 1)[0]
            
            if p_count > 0:
                self._modify_sizes(mutable_chunk, f_type, p_count, mode, value)
            
            # 重新压缩 (必须 Clean Context)
            new_comp = cctx.compress(mutable_chunk)
            new_size = len(new_comp)
            
            # 记录新索引
            new_index_table.extend(struct.pack('<Q', current_write_pos))
            new_index_table.extend(struct.pack('<I', new_size))
            
            new_data.extend(new_comp)
            current_write_pos += new_size
            
        # 回填索引
        new_data[frame_index_placeholder : frame_index_placeholder + frame_index_size] = new_index_table
        
        with open(output_path, 'wb') as f:
            f.write(new_data)

    def _modify_sizes(self, chunk, f_type, count, mode, user_val):
        # 计算偏移
        # Header(5)
        # Type 0 (I-Frame): Pos(12N) + Col(4N) -> Size(2N)
        # Type 1 (P-Frame): Pos(6N)  + Col(4N) -> Size(2N)
        
        base_offset = 5
        if f_type == 0:
            offset = base_offset + (16 * count)
            is_delta = False
        else:
            offset = base_offset + (10 * count)
            is_delta = True
            
        for i in range(count):
            ptr = offset + (i * 2)
            
            if not is_delta:
                # === I-Frame (uint16) ===
                raw_val = struct.unpack_from('<H', chunk, ptr)[0]
                
                if mode == 'scale':
                    # 缩放模式：直接乘倍率
                    new_raw = int(raw_val * user_val)
                else:
                    # 固定模式：用户输入 float (如 1.5)，需转换为存储格式 (1.5 * 100 = 150)
                    new_raw = int(user_val * QUANTIZE_SCALE)
                
                # 限制范围 uint16
                new_raw = max(0, min(65535, new_raw))
                struct.pack_into('<H', chunk, ptr, new_raw)
                
            else:
                # === P-Frame (int16) ===
                raw_val = struct.unpack_from('<h', chunk, ptr)[0]
                
                if mode == 'scale':
                    # 缩放模式：差值也按比例缩放
                    new_raw = int(raw_val * user_val)
                else:
                    # 固定模式：如果强制固定大小，意味着该帧相对于上一帧没有大小变化
                    # 所以 Delta 必须归零
                    new_raw = 0
                
                # 限制范围 int16
                new_raw = max(-32768, min(32767, new_raw))
                struct.pack_into('<h', chunk, ptr, new_raw)

# ==========================================
# 入口逻辑
# ==========================================
def main():
    # 1. 优先解析命令行参数
    parser = argparse.ArgumentParser(description="NBL 粒子大小调整工具")
    parser.add_argument("input", nargs='?', help="输入的 .nbl 文件路径")
    parser.add_argument("-m", "--mode", choices=['scale', 'fixed'], help="模式: scale(缩放倍率) 或 fixed(指定大小)")
    parser.add_argument("-v", "--value", type=float, help="数值 (缩放倍率 或 目标大小)")
    args = parser.parse_args()

    input_file = args.input
    mode = args.mode
    value = args.value

    # 2. 如果没有命令行参数，启动交互模式
    if not input_file:
        # 交互式输入文件名 (支持路径补全/手动输入)
        input_file = questionary.text("请输入 .nbl 文件路径:").ask()
        if not input_file: return
        # 去除可能的引号
        input_file = input_file.strip('"').strip("'")

    if not os.path.exists(input_file):
        print(f"❌ 错误: 文件 '{input_file}' 不存在。")
        return

    # 交互式选择模式 (如果未指定)
    if not mode:
        choice = questionary.select(
            "请选择操作模式:",
            choices=[
                "Scale (缩放) - 保持动态效果，整体放大/缩小",
                "Fixed (固定) - 强制所有粒子为指定大小 (如 1.5)"
            ]
        ).ask()
        mode = 'scale' if 'Scale' in choice else 'fixed'

    # 交互式输入数值 (如果未指定)
    if value is None:
        msg = "请输入缩放倍率 (如 2.0):" if mode == 'scale' else "请输入目标大小 (Float, 如 1.5):"
        val_str = questionary.text(
            msg,
            validate=lambda x: x.replace('.', '', 1).isdigit()
        ).ask()
        value = float(val_str)

    # 生成输出文件名
    suffix = f"_{mode}_{value}.nbl"
    output_file = os.path.splitext(input_file)[0] + suffix

    # 执行
    try:
        worker = NBLResizer(input_file)
        worker.load()
        worker.process(output_file, mode, value)
        print(f"\n✅ 处理完成! 输出文件: {output_file}")
    except Exception as e:
        print(f"\n❌ 发生错误: {e}")

if __name__ == "__main__":
    main()
