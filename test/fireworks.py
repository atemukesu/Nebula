import struct
import random
import math
import numpy as np
import zstandard as zstd
import os

# =================配置区域=================
FILENAME = "fireworks_show.nbl"
TOTAL_TIME = 120  # 秒
FPS = 30
TOTAL_FRAMES = TOTAL_TIME * FPS
FIREWORK_COUNT = 100
PARTICLES_PER_FIREWORK = 20000 
TEXTURE_PATH = "minecraft:textures/particle/flash.png"

# 为了防止文件过大炸内存，粒子生命周期设短一点，但数量保持20000
PARTICLE_LIFE_MIN = 30
PARTICLE_LIFE_MAX = 60
GRAVITY = -0.04
DRAG = 0.96

# ================= NBL 结构定义 =================

class NBLWriter:
    def __init__(self, filename, fps, total_frames):
        self.f = open(filename, 'wb')
        self.fps = fps
        self.total_frames = total_frames
        self.frame_offsets = []
        self.frame_sizes = []
        self.keyframe_indices = [] # 我们全用 I-Frame 以保证高精度和防止伪影
        
        # 预留 Header 空间 (48 bytes)
        self.f.write(b'\x00' * 48)
        
        # 写入纹理块 (Texture Block)
        # 结构: TextureCount(在Header) -> Loop { len(2B), str, rows(1B), cols(1B) }
        # 我们这里只用一张贴图
        self.write_texture_block([TEXTURE_PATH])
        
        # 记录帧索引表的开始位置 (Header 之后，Texture Block 之后)
        self.frame_table_offset = self.f.tell()
        
        # 预留帧索引表空间 (TotalFrames * (8 + 4)) = 12 bytes per frame
        self.f.write(b'\x00' * (self.total_frames * 12))
        
        # 记录关键帧索引表开始位置
        self.keyframe_table_offset = self.f.tell()
        # 预留关键帧表头 (Count 4B)
        self.f.write(b'\x00' * 4) 
        # 实际上我们不知道会有多少关键帧(虽然本脚本设定全为I帧)，先预留一部分或稍后追加
        # 为简单起见，我们在最后回写时处理关键帧表，现在记录位置即可

        # 初始化包围盒
        self.bbox_min = np.array([float('inf')] * 3)
        self.bbox_max = np.array([float('-inf')] * 3)

    def write_texture_block(self, textures):
        # 这里的 TextureCount 会在 finalize 时写回 Header
        for tex in textures:
            tex_bytes = tex.encode('utf-8')
            self.f.write(struct.pack('<H', len(tex_bytes))) # Path Length
            self.f.write(tex_bytes)                         # Path
            self.f.write(struct.pack('<B', 1))              # Rows
            self.f.write(struct.pack('<B', 1))              # Cols

    def write_frame(self, frame_index, positions, colors, sizes, ids):
        """
        写入一帧数据 (强制 Type 0: I-Frame)
        positions: numpy array (N, 3) float32
        colors: numpy array (N, 4) uint8
        sizes: numpy array (N,) uint16
        ids: numpy array (N,) int32
        """
        particle_count = len(ids)
        
        # 更新全局包围盒
        if particle_count > 0:
            current_min = positions.min(axis=0)
            current_max = positions.max(axis=0)
            self.bbox_min = np.minimum(self.bbox_min, current_min)
            self.bbox_max = np.maximum(self.bbox_max, current_max)

        # 构建 Payload (SoA 布局)
        # 1. PosArrays: X list, Y list, Z list
        # float32 bytes
        pos_data = positions[:, 0].tobytes() + positions[:, 1].tobytes() + positions[:, 2].tobytes()
        
        # 2. ColArrays: R list, G list, B list, A list
        col_data = colors[:, 0].tobytes() + colors[:, 1].tobytes() + colors[:, 2].tobytes() + colors[:, 3].tobytes()
        
        # 3. Sizes
        size_data = sizes.tobytes()
        
        # 4. TextureIDs (全为0)
        tex_id_data = np.zeros(particle_count, dtype=np.uint8).tobytes()
        
        # 5. SeqIndices (全为0)
        seq_idx_data = np.zeros(particle_count, dtype=np.uint8).tobytes()
        
        # 6. ParticleIDs
        pid_data = ids.tobytes()

        # 拼接 Payload
        payload = pos_data + col_data + size_data + tex_id_data + seq_idx_data + pid_data
        
        # 构建 Frame Header
        # Type(1B) + Count(4B)
        frame_header = struct.pack('<BI', 0, particle_count) # Type 0 (I-Frame)
        
        # 压缩: Zstd(Header + Payload)
        cctx = zstd.ZstdCompressor(level=3) # level 3 for speed, compressed format required
        compressed_chunk = cctx.compress(frame_header + payload)
        
        # 记录偏移和大小
        current_offset = self.f.tell()
        chunk_size = len(compressed_chunk)
        
        self.f.write(compressed_chunk)
        
        self.frame_offsets.append(current_offset)
        self.frame_sizes.append(chunk_size)
        self.keyframe_indices.append(frame_index) # 每一帧都是关键帧

    def finalize(self):
        # 1. 回写 Header
        self.f.seek(0)
        # Magic(8), Version(2), TargetFPS(2), TotalFrames(4), TexCount(2), Attrs(2)
        self.f.write(b'NEBULAFX')
        self.f.write(struct.pack('<H', 1))          # Version 1
        self.f.write(struct.pack('<H', self.fps))   # Target FPS
        self.f.write(struct.pack('<I', self.total_frames))
        self.f.write(struct.pack('<H', 1))          # Texture Count
        self.f.write(struct.pack('<H', 3))          # Attr: Alpha(1) | Size(2) = 3
        
        # BBox Min/Max
        # 如果没有粒子，避免写入inf
        if np.isinf(self.bbox_min[0]): self.bbox_min[:] = 0
        if np.isinf(self.bbox_max[0]): self.bbox_max[:] = 0
        
        self.f.write(struct.pack('<3f', *self.bbox_min))
        self.f.write(struct.pack('<3f', *self.bbox_max))
        self.f.write(b'\x00' * 4) # Reserved

        # 2. 回写 Frame Index Table
        self.f.seek(self.frame_table_offset)
        for off, sz in zip(self.frame_offsets, self.frame_sizes):
            self.f.write(struct.pack('<QI', off, sz))

        # 3. 回写 Keyframe Index Table
        # 注意：在 write_frame 过程中，我们一直往后写数据，覆盖了原本预留 Keyframe Table 的位置
        # 所以 NBL 格式其实要求 Keyframe Table 紧接 Frame Table，
        # 我们需要在写 Frame Data 之前就把 Keyframe Table 占位好，或者把 Keyframe Table 挪到文件尾？
        # NBL 文档说明: "紧接在帧索引表之后"。
        # 修正逻辑：由于 Keyframe 表变长，且数据块紧跟其后，我们在初始化时其实很难预判。
        # 但既然我们在这个脚本里生成数据，我们可以先计算好 Frame Table 和 Keyframe Table 的大小，
        # 然后再开始写 Chunk。
        
        # 重新修正文件结构逻辑：
        # 数据块必须移动。由于我们是流式写入，最简单的办法是：
        # 1. 内存里构建 Frame Table 和 Keyframe Table。
        # 2. 生成完所有 Chunk 后，把 Chunk 数据读出来，或者追加。
        # 鉴于内存可能不够，我们采用 "Header + Tables + Placeholders -> Data" 的策略。
        pass # 实际逻辑在外部控制，这里只负责写

    def write_tables_at_correct_pos(self):
        # 这是一个修正函数。
        # 此时文件指针在所有 Chunks 的末尾。
        # 我们需要把 Frame Table 和 Keyframe Table 插入到 Header 之后，把 Chunks 往后挪？
        # 不，这太慢了。
        # 正确做法：初始化时就算好 Table 大小。
        pass

# ================= 烟花逻辑 =================

class Firework:
    def __init__(self, start_frame, fw_id):
        self.start_frame = start_frame
        self.fw_id = fw_id
        self.exploded = False
        self.dead = False
        
        # 随机位置
        self.x = random.uniform(-50, 50)
        self.z = random.uniform(-50, 50)
        self.y = 0
        
        # 火箭阶段
        self.vy = 1.5 + random.uniform(0, 0.5)
        
        # 颜色 (HSV 转 RGB 简易版)
        hue = random.random()
        self.color_base = self.hsv_to_rgb(hue, 1.0, 1.0)
        
        # 粒子数据 (延迟生成以节省内存)
        self.particles = None
        
    def hsv_to_rgb(self, h, s, v):
        i = int(h * 6)
        f = h * 6 - i
        p = v * (1 - s)
        q = v * (1 - f * s)
        t = v * (1 - (1 - f) * s)
        r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i % 6]
        return int(r*255), int(g*255), int(b*255)

    def update(self, current_frame):
        if current_frame < self.start_frame:
            return None, None, None, None
        
        if self.dead:
            return None, None, None, None

        # 火箭阶段
        if not self.exploded:
            self.y += self.vy
            self.vy += GRAVITY
            
            # 只有1个粒子代表火箭
            if self.vy <= 0.2: # 到达顶点，爆炸
                self.exploded = True
                self.spawn_explosion()
            else:
                # 返回火箭粒子 (1个)
                pos = np.array([[self.x, self.y, self.z]], dtype=np.float32)
                col = np.array([[255, 255, 255, 255]], dtype=np.uint8)
                sz = np.array([200], dtype=np.uint16) # Size 2.0
                pid = np.array([self.fw_id], dtype=np.int32) # 使用 fw_id 作为火箭 ID
                return pos, col, sz, pid

        # 爆炸阶段
        if self.exploded:
            # Physics
            self.p_pos += self.p_vel
            self.p_vel *= DRAG
            self.p_vel[:, 1] += GRAVITY # Y轴重力
            self.p_life -= 1
            
            # 颜色渐变 (Alpha 衰减)
            # 简单的线性衰减: life / max_life * 255
            # 为了性能，我们只在 life < 20 时计算
            mask = self.p_life < 20
            if np.any(mask):
                alpha = (self.p_life[mask] / 20.0) * 255
                self.p_col[mask, 3] = alpha.astype(np.uint8)

            # 移除死粒子
            alive = self.p_life > 0
            if not np.all(alive):
                self.p_pos = self.p_pos[alive]
                self.p_vel = self.p_vel[alive]
                self.p_col = self.p_col[alive]
                self.p_size = self.p_size[alive]
                self.p_ids = self.p_ids[alive]
                self.p_life = self.p_life[alive]
            
            if len(self.p_pos) == 0:
                self.dead = True
                return None, None, None, None
                
            return self.p_pos, self.p_col, self.p_size, self.p_ids

        return None, None, None, None

    def spawn_explosion(self):
        # 使用 NumPy 批量生成 20000 个粒子
        N = PARTICLES_PER_FIREWORK
        
        # 1. 随机球体速度向量
        # 简单算法: 生成高斯分布随机数然后归一化
        vecs = np.random.normal(size=(N, 3))
        mags = np.linalg.norm(vecs, axis=1, keepdims=True)
        vecs /= mags
        
        # 速度大小随机
        speed = np.random.uniform(0.1, 1.2, size=(N, 1))
        self.p_vel = (vecs * speed).astype(np.float32)
        
        # 位置 (全部从爆炸中心开始)
        self.p_pos = np.tile(np.array([self.x, self.y, self.z], dtype=np.float32), (N, 1))
        
        # 颜色 (基色 + 随机扰动)
        # 20000行，4列
        base = np.array(self.color_base + (255,), dtype=np.int16)
        noise = np.random.randint(-30, 30, size=(N, 4), dtype=np.int16)
        noise[:, 3] = 0 # Alpha 不扰动
        final_col = np.clip(base + noise, 0, 255).astype(np.uint8)
        self.p_col = final_col
        
        # ID: 使用 fw_id * 100000 + index
        # 确保每个烟花的粒子ID段不冲突
        start_id = (self.fw_id + 1) * 100000 # +1 避开 0-99 的火箭ID
        self.p_ids = np.arange(start_id, start_id + N, dtype=np.int32)
        
        # Size
        self.p_size = np.random.randint(20, 50, size=N, dtype=np.uint16) # 0.2 - 0.5 blocks
        
        # Life
        self.p_life = np.random.randint(PARTICLE_LIFE_MIN, PARTICLE_LIFE_MAX, size=N)

# ================= 主程序 =================

def main():
    print(f"Generating {FILENAME}...")
    print(f"Total Fireworks: {FIREWORK_COUNT}, Particles/FW: {PARTICLES_PER_FIREWORK}")
    print("Warning: This will use significant RAM and CPU.")
    
    writer = NBLWriter(FILENAME, FPS, TOTAL_FRAMES)
    
    # 手动处理 Header 后的 Table 占位
    # Frame Table: TotalFrames * 12 bytes
    # Keyframe Table: (TotalFrames * 4) + 4 bytes (因为我们打算全存I帧)
    
    # 移动文件指针，跳过 Texture Block 之后的位置
    start_of_tables = writer.f.tell()
    
    frame_table_size = TOTAL_FRAMES * 12
    keyframe_table_size = 4 + (TOTAL_FRAMES * 4)
    
    # 填充 0 占位
    writer.f.write(b'\x00' * (frame_table_size + keyframe_table_size))
    
    # 初始化烟花队列
    fireworks = []
    # 在 0 到 100秒 之间均匀发射，留20秒结尾
    launch_times = np.linspace(0, 100 * FPS, FIREWORK_COUNT)
    for i, t in enumerate(launch_times):
        fireworks.append(Firework(int(t), i))
    
    # 模拟循环
    for frame in range(TOTAL_FRAMES):
        if frame % 30 == 0:
            print(f"Processing Frame {frame}/{TOTAL_FRAMES} ({(frame/TOTAL_FRAMES)*100:.1f}%)")
        
        # 收集本帧所有粒子
        all_pos = []
        all_col = []
        all_sz = []
        all_ids = []
        
        active_fw_count = 0
        
        for fw in fireworks:
            pos, col, sz, pid = fw.update(frame)
            if pos is not None:
                active_fw_count += 1
                all_pos.append(pos)
                all_col.append(col)
                all_sz.append(sz)
                all_ids.append(pid)
        
        if active_fw_count > 0:
            # 合并数组
            flat_pos = np.concatenate(all_pos)
            flat_col = np.concatenate(all_col)
            flat_sz = np.concatenate(all_sz)
            flat_ids = np.concatenate(all_ids)
            
            # 写入帧
            writer.write_frame(frame, flat_pos, flat_col, flat_sz, flat_ids)
        else:
            # 空帧
            writer.write_frame(frame, np.empty((0,3), dtype=np.float32), 
                               np.empty((0,4), dtype=np.uint8),
                               np.empty((0,), dtype=np.uint16),
                               np.empty((0,), dtype=np.int32))

    # ================= 修正头部和索引表 =================
    print("Finalizing file structure...")
    
    # 1. 回写 Header (Global Info)
    writer.finalize() # 这里写了基本Header
    
    # 2. 定位到 Table 区域回写索引
    writer.f.seek(start_of_tables)
    
    # 写 Frame Index Table
    for off, sz in zip(writer.frame_offsets, writer.frame_sizes):
        writer.f.write(struct.pack('<QI', off, sz))
        
    # 写 Keyframe Index Table
    # Count
    writer.f.write(struct.pack('<I', len(writer.keyframe_indices)))
    # Indices
    for idx in writer.keyframe_indices:
        writer.f.write(struct.pack('<I', idx))
        
    writer.f.close()
    print("Done! File saved.")

if __name__ == "__main__":
    main()