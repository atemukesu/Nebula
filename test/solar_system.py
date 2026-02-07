import struct
import math
import numpy as np
import zstandard as zstd  # pip install zstandard

# --- NBL 格式常量 ---
MAGIC = b'NEBULAFX'
VERSION = 1
# 属性位掩码: 0x01(Alpha) | 0x02(Size) = 3
ATTRIBUTES = 3 
# 坐标量化精度 (用于 P-Frame)
POS_SCALE = 1000.0 
SIZE_SCALE = 100.0

def write_string(f, s):
    """写入带长度前缀的 UTF-8 字符串 (uint16 + bytes)"""
    encoded = s.encode('utf-8')
    f.write(struct.pack('<H', len(encoded)))
    f.write(encoded)

class ParticleSystem:
    def __init__(self, num_particles):
        self.num_particles = num_particles
        # 静态属性 (不随时间变化，或仅用于计算)
        self.orbit_radii = np.zeros(num_particles, dtype=np.float32)
        self.orbit_speeds = np.zeros(num_particles, dtype=np.float32)
        self.orbit_start_angles = np.zeros(num_particles, dtype=np.float32)
        
        # 本地坐标 (相对于行星中心)
        self.local_x = np.zeros(num_particles, dtype=np.float32)
        self.local_y = np.zeros(num_particles, dtype=np.float32)
        self.local_z = np.zeros(num_particles, dtype=np.float32)
        
        # 属性
        self.base_colors = np.zeros((num_particles, 4), dtype=np.uint8) # R,G,B,A
        self.sizes = np.full(num_particles, 15, dtype=np.uint16) # 默认大小 0.15
        self.tex_ids = np.zeros(num_particles, dtype=np.uint8)
        self.ids = np.arange(num_particles, dtype=np.int32)
        
        # 特殊标记
        self.is_saturn = np.zeros(num_particles, dtype=bool)
        self.saturn_tilt = math.radians(27)

    def init_solar_system(self):
        """初始化所有粒子的静态物理属性"""
        print("正在初始化行星数据结构...")
        
        planets = [
            {"name": "Mercury", "dist": 20,  "r": 1.5, "col": (169, 169, 169), "weight": 0.03, "speed": 1.5},
            {"name": "Venus",   "dist": 35,  "r": 2.5, "col": (227, 187, 118), "weight": 0.05, "speed": 1.2},
            {"name": "Earth",   "dist": 50,  "r": 2.6, "col": (45, 100, 200),  "weight": 0.05, "speed": 1.0},
            {"name": "Mars",    "dist": 65,  "r": 2.0, "col": (193, 68, 14),   "weight": 0.04, "speed": 0.8},
            {"name": "Jupiter", "dist": 100, "r": 9.0, "col": (201, 144, 57),  "weight": 0.30, "speed": 0.4},
            {"name": "Saturn",  "dist": 140, "r": 7.5, "col": (234, 214, 184), "weight": 0.25, "speed": 0.3},
            {"name": "Uranus",  "dist": 180, "r": 6.0, "col": (209, 231, 231), "weight": 0.14, "speed": 0.2},
            {"name": "Neptune", "dist": 220, "r": 5.8, "col": (66, 107, 240),  "weight": 0.14, "speed": 0.15},
        ]

        # 计算粒子分配
        total_weight = sum(p["weight"] for p in planets)
        current_idx = 0
        
        np.random.seed(42)

        for p_idx, p in enumerate(planets):
            # 计算该行星的粒子数
            count = int(self.num_particles * (p["weight"] / total_weight))
            if p_idx == 4: # Jupiter 补齐误差
                diff = self.num_particles - sum(int(self.num_particles * (x["weight"] / total_weight)) for x in planets)
                count += diff
            
            end_idx = current_idx + count
            if end_idx > self.num_particles: end_idx = self.num_particles
            
            # --- 填充静态轨道数据 ---
            # 整个行星群共享的轨道参数
            start_angle = np.random.uniform(0, 2 * np.pi)
            self.orbit_radii[current_idx:end_idx] = p["dist"]
            self.orbit_speeds[current_idx:end_idx] = p["speed"]
            self.orbit_start_angles[current_idx:end_idx] = start_angle
            
            # 颜色 (带噪点)
            c_base = np.array(p["col"], dtype=np.int16)
            noise = np.random.randint(-20, 20, (count, 3))
            cols = np.clip(c_base + noise, 0, 255).astype(np.uint8)
            self.base_colors[current_idx:end_idx, 0:3] = cols
            self.base_colors[current_idx:end_idx, 3] = 255 # Alpha

            # 标记是否为土星 (用于特殊倾角处理)
            if p["name"] == "Saturn":
                self.is_saturn[current_idx:end_idx] = True
            
            # --- 构建本地形状 (本体 + 星环) ---
            is_saturn = (p["name"] == "Saturn")
            n_body = int(count * 0.4) if is_saturn else count
            n_rings = count - n_body
            
            # 1. 本体 (球体)
            if n_body > 0:
                phi = np.arccos(np.random.uniform(-1, 1, n_body))
                theta = np.random.uniform(0, 2*np.pi, n_body)
                rad = p["r"]
                
                # Y-up 坐标系
                by = rad * np.cos(phi)
                bx = rad * np.sin(phi) * np.cos(theta)
                bz = rad * np.sin(phi) * np.sin(theta)
                
                self.local_x[current_idx : current_idx+n_body] = bx
                self.local_y[current_idx : current_idx+n_body] = by
                self.local_z[current_idx : current_idx+n_body] = bz
                
                # 本体粒子稍大
                self.sizes[current_idx : current_idx+n_body] = 30 # 0.30

            # 2. 星环 (圆盘)
            if n_rings > 0:
                r_idx = current_idx + n_body
                r_dist = np.sqrt(np.random.uniform((p["r"]*1.5)**2, (p["r"]*2.5)**2, n_rings))
                r_theta = np.random.uniform(0, 2*np.pi, n_rings)
                
                rx = r_dist * np.cos(r_theta)
                rz = r_dist * np.sin(r_theta)
                ry = np.random.uniform(-0.1, 0.1, n_rings)
                
                self.local_x[r_idx : end_idx] = rx
                self.local_y[r_idx : end_idx] = ry
                self.local_z[r_idx : end_idx] = rz
                
                # 星环颜色微调
                self.base_colors[r_idx : end_idx, 0:3] = [200, 190, 180]
                self.base_colors[r_idx : end_idx, 3] = 180 # 半透明
                self.sizes[r_idx : end_idx] = 15 # 0.15

            current_idx = end_idx

def create_nbl_solar_system(filename, num_particles=100000, duration=20.0, fps=30):
    ps = ParticleSystem(num_particles)
    ps.init_solar_system()
    
    total_frames = int(duration * fps)
    time_steps = np.linspace(0, duration, total_frames)
    
    cctx = zstd.ZstdCompressor(level=3) # 快速压缩
    
    # 状态缓存
    prev_pos = None
    prev_col = None
    
    # 文件部分缓存
    chunk_table = [] # (offset, size)
    chunks_data = bytearray()
    
    bbox_min = np.array([float('inf')]*3, dtype=np.float32)
    bbox_max = np.array([float('-inf')]*3, dtype=np.float32)
    
    print(f"开始生成 NBL 帧数据 ({total_frames} 帧)...")
    
    # 预计算土星倾角
    c_tilt = math.cos(ps.saturn_tilt)
    s_tilt = math.sin(ps.saturn_tilt)
    
    for frame_idx in range(total_frames):
        if frame_idx % 10 == 0:
            print(f"  Processing Frame {frame_idx}/{total_frames}...")
            
        t = time_steps[frame_idx]
        
        # --- 1. 计算当前帧物理位置 ---
        # 自转参数
        spin_ang = 1.0 * t
        c_spin = math.cos(spin_ang)
        s_spin = math.sin(spin_ang)
        
        # A. 本地自转 (所有粒子绕各自的Y轴转)
        # x' = x cos - z sin, z' = x sin + z cos
        curr_lx = ps.local_x * c_spin - ps.local_z * s_spin
        curr_lz = ps.local_x * s_spin + ps.local_z * c_spin
        curr_ly = ps.local_y
        
        # B. 土星倾斜 (仅对土星粒子应用)
        # 绕 X 轴倾斜: y' = y cos - z sin, z' = y sin + z cos
        # 非土星粒子保持原样
        final_lx = np.where(ps.is_saturn, curr_lx, curr_lx)
        final_ly = np.where(ps.is_saturn, curr_ly * c_tilt - curr_lz * s_tilt, curr_ly)
        final_lz = np.where(ps.is_saturn, curr_ly * s_tilt + curr_lz * c_tilt, curr_lz)
        
        # C. 公转叠加
        orbit_theta = ps.orbit_start_angles + ps.orbit_speeds * 0.2 * t
        planet_x = ps.orbit_radii * np.cos(orbit_theta)
        planet_z = ps.orbit_radii * np.sin(orbit_theta)
        
        # 最终世界坐标
        world_x = planet_x + final_lx
        world_y = final_ly # 轨道在Y=0
        world_z = planet_z + final_lz
        
        # 更新 BBox
        min_x, max_x = world_x.min(), world_x.max()
        min_y, max_y = world_y.min(), world_y.max()
        min_z, max_z = world_z.min(), world_z.max()
        
        bbox_min = np.minimum(bbox_min, [min_x, min_y, min_z])
        bbox_max = np.maximum(bbox_max, [max_x, max_y, max_z])
        
        # 组合当前帧数据
        curr_pos = np.stack((world_x, world_y, world_z), axis=1).astype(np.float32)
        curr_col = ps.base_colors # 颜色此处为静态，若有变化需重新计算
        
        # --- 2. 决定帧类型并打包 ---
        is_iframe = (frame_idx % 60 == 0) # 每2秒强制I-Frame
        
        # 尝试构建 P-Frame
        packed_data = None
        frame_type = 0 # 0=I, 1=P
        
        if not is_iframe and prev_pos is not None:
            # 计算 Deltas
            diff_pos = (curr_pos - prev_pos) * POS_SCALE
            # 检查是否溢出 int16
            if np.all((diff_pos >= -32768) & (diff_pos <= 32767)):
                frame_type = 1
                # 构建 P-Frame 数据
                # Delta Pos (Int16)
                delta_pos_int = diff_pos.astype(np.int16)
                # Delta Col (Int8) -> 假设颜色不变，全是0
                delta_col_int = np.zeros_like(curr_col, dtype=np.int8) 
                # Delta Size (Int16) -> 假设大小不变
                delta_size_int = np.zeros_like(ps.sizes, dtype=np.int16)
                # Delta Tex -> 0
                delta_tex_int = np.zeros_like(ps.tex_ids, dtype=np.int8)
                # Seq Delta -> 0
                delta_seq_int = np.zeros_like(ps.tex_ids, dtype=np.int8)
                
                # SoA Packing for P-Frame
                buf = bytearray()
                # 1. PosDeltas (3 * N * int16)
                # 转置为 [x0, x1...], [y0...] 顺序
                buf.extend(delta_pos_int.T.tobytes('C'))
                # 2. ColDeltas (4 * N * int8)
                buf.extend(delta_col_int.T.tobytes('C'))
                # 3. SizeDeltas
                buf.extend(delta_size_int.tobytes('C'))
                # 4. TexIDDeltas
                buf.extend(delta_tex_int.tobytes('C'))
                # 5. SeqDeltas
                buf.extend(delta_seq_int.tobytes('C'))
                # 6. ParticleIDs
                buf.extend(ps.ids.tobytes('C'))
                
                packed_data = buf
            else:
                # 移动过快，回退到 I-Frame
                frame_type = 0
        
        if frame_type == 0:
            # I-Frame Packing
            buf = bytearray()
            # 1. Positions (3 * N * float32)
            # 转置以符合 [x0, x1...], [y0...] 连续存储
            buf.extend(curr_pos.T.tobytes('C'))
            # 2. Colors (4 * N * uint8)
            buf.extend(curr_col.T.tobytes('C'))
            # 3. Sizes (N * uint16)
            buf.extend(ps.sizes.tobytes('C'))
            # 4. TextureIDs (N * uint8)
            buf.extend(ps.tex_ids.tobytes('C'))
            # 5. SeqIndices (N * uint8)
            buf.extend(np.zeros(num_particles, dtype=np.uint8).tobytes('C'))
            # 6. ParticleIDs (N * int32)
            buf.extend(ps.ids.tobytes('C'))
            
            packed_data = buf

        # --- 3. 压缩并写入 ---
        # Chunk Header: FrameType(u8) + Count(u32)
        chunk_header = struct.pack('<BI', frame_type, num_particles)
        # Compress Body
        compressed_body = cctx.compress(packed_data)
        
        full_chunk = chunk_header + compressed_body
        
        # 记录偏移量
        current_offset = 48 + 0 + (total_frames * 12) + len(chunks_data) 
        # 注意: 这里的Offset是临时的，因为 Header 和 Index 的大小还没确定
        # 更好的做法是：先存 Chunk，最后统一写文件
        
        chunk_table.append(len(full_chunk))
        chunks_data.extend(full_chunk)
        
        # 更新历史
        prev_pos = curr_pos
        prev_col = curr_col

    # --- 4. 生成最终文件 ---
    print("正在写入 NBL 文件...")
    with open(filename, 'wb') as f:
        # A. Texture Block (构建内存数据)
        # 只有一张贴图
        tex_path = "minecraft:textures/particle/white_ash.png"
        tex_block = bytearray()
        tex_block.extend(struct.pack('<H', len(tex_path)))
        tex_block.extend(tex_path.encode('utf-8'))
        tex_block.extend(struct.pack('<BB', 1, 1)) # rows, cols
        tex_block_len = len(tex_block)
        
        # B. Header (48 bytes)
        f.write(MAGIC) # 8
        f.write(struct.pack('<H', VERSION)) # 2
        f.write(struct.pack('<H', fps)) # 2
        f.write(struct.pack('<I', total_frames)) # 4
        f.write(struct.pack('<H', 1)) # TextureCount = 1
        f.write(struct.pack('<H', ATTRIBUTES)) # 2
        # BBox
        f.write(struct.pack('<fff', *bbox_min)) # 12
        f.write(struct.pack('<fff', *bbox_max)) # 12
        f.write(b'\x00\x00\x00\x00') # Reserved 4
        
        # C. Texture Block
        f.write(tex_block)
        
        # D. Frame Index Table
        # 计算 Chunk 的起始偏移量
        # Header(48) + TexBlock(tex_block_len) + IndexTable(12 * frames)
        base_offset = 48 + tex_block_len + (total_frames * 12)
        
        current_data_offset = 0
        for size in chunk_table:
            abs_offset = base_offset + current_data_offset
            f.write(struct.pack('<QI', abs_offset, size))
            current_data_offset += size
            
        # E. Chunks
        f.write(chunks_data)
        
    print(f"成功！NBL 文件已生成: {filename}")
    print(f"  Total Frames: {total_frames}")
    print(f"  BBox: {bbox_min} -> {bbox_max}")

if __name__ == "__main__":
    # 生成 NBL 文件
    create_nbl_solar_system("solar_system.nbl", num_particles=100000)