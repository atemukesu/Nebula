import struct
import math
import numpy as np
import zstandard as zstd
from pathlib import Path
import traceback
import os

# --- NBL 格式常量 ---
MAGIC = b'NEBULAFX'
VERSION = 1
ATTRIBUTES = 3  # Alpha(1) | Size(2)
POS_SCALE = 1000.0 
SIZE_SCALE = 100.0
I_FRAME_INTERVAL = 2  

def write_string(f, s):
    """写入带长度前缀的 UTF-8 字符串"""
    encoded = s.encode('utf-8')
    f.write(struct.pack('<H', len(encoded)))
    f.write(encoded)
    return 2 + len(encoded)  # 返回写入的字节数

class ParticleSystem:
    def __init__(self, num_particles):
        self.num_particles = num_particles
        # 静态属性
        self.orbit_radii = np.zeros(num_particles, dtype=np.float32)
        self.orbit_speeds = np.zeros(num_particles, dtype=np.float32)
        self.orbit_start_angles = np.zeros(num_particles, dtype=np.float32)
        
        # 本地坐标
        self.local_x = np.zeros(num_particles, dtype=np.float32)
        self.local_y = np.zeros(num_particles, dtype=np.float32)
        self.local_z = np.zeros(num_particles, dtype=np.float32)
        
        # 属性
        self.base_colors = np.zeros((num_particles, 4), dtype=np.uint8)
        self.sizes = np.full(num_particles, 15, dtype=np.uint16)
        self.tex_ids = np.zeros(num_particles, dtype=np.uint8)
        self.ids = np.arange(num_particles, dtype=np.int32)
        
        self.is_saturn = np.zeros(num_particles, dtype=bool)
        self.saturn_tilt = math.radians(27)

    def init_solar_system(self):
        """初始化逻辑保持不变"""
        print("正在初始化行星数据结构...")
        # ... (此处省略具体的行星初始化代码，与原版一致，为了节省篇幅) ...
        # 请保留你原有的 init_solar_system 实现
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

        total_weight = sum(p["weight"] for p in planets)
        particle_counts = [int(self.num_particles * (p["weight"] / total_weight)) for p in planets]
        diff = self.num_particles - sum(particle_counts)
        if diff > 0:
            last_non_zero = next(i for i in reversed(range(len(particle_counts))) if particle_counts[i] > 0)
            particle_counts[last_non_zero] += diff
        
        current_idx = 0
        np.random.seed(42)

        for p_idx, p in enumerate(planets):
            count = particle_counts[p_idx]
            if count <= 0: continue
            end_idx = current_idx + count
            end_idx = min(end_idx, self.num_particles)
            
            start_angle = np.random.uniform(0, 2 * np.pi)
            self.orbit_radii[current_idx:end_idx] = p["dist"]
            self.orbit_speeds[current_idx:end_idx] = p["speed"]
            self.orbit_start_angles[current_idx:end_idx] = start_angle
            
            c_base = np.array(p["col"], dtype=np.int16)
            noise = np.random.randint(-20, 20, (count, 3))
            cols = np.clip(c_base + noise, 0, 255).astype(np.uint8)
            self.base_colors[current_idx:end_idx, 0:3] = cols
            self.base_colors[current_idx:end_idx, 3] = 255
            
            if p["name"] == "Saturn": self.is_saturn[current_idx:end_idx] = True
            
            is_saturn = (p["name"] == "Saturn")
            n_body = int(count * 0.4) if is_saturn else count
            n_rings = count - n_body
            
            if n_body > 0:
                phi = np.arccos(np.random.uniform(-1, 1, n_body))
                theta = np.random.uniform(0, 2*np.pi, n_body)
                rad = p["r"]
                by = rad * np.cos(phi)
                bx = rad * np.sin(phi) * np.cos(theta)
                bz = rad * np.sin(phi) * np.sin(theta)
                self.local_x[current_idx : current_idx+n_body] = bx
                self.local_y[current_idx : current_idx+n_body] = by
                self.local_z[current_idx : current_idx+n_body] = bz
                self.sizes[current_idx : current_idx+n_body] = 30 

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
                self.base_colors[r_idx : end_idx, 0:3] = [200, 190, 180]
                self.base_colors[r_idx : end_idx, 3] = 180
                self.sizes[r_idx : end_idx] = 15

            current_idx = end_idx

def create_nbl_solar_system(filename, num_particles=100000, duration=20.0, fps=30):
    ps = ParticleSystem(num_particles)
    ps.init_solar_system()
    
    total_frames = int(duration * fps)
    time_steps = np.linspace(0, duration, total_frames)
    cctx = zstd.ZstdCompressor(level=3)
    
    prev_pos = None
    prev_size = None
    prev_tex = None
    prev_col = None
    
    # 存储每一帧的压缩后大小，用于后续计算偏移
    chunk_sizes = []
    # 存储关键帧的帧号
    keyframe_indices = []
    
    chunk_temp_path = Path(f"{filename}.tmp")
    
    bbox_min = np.full(3, np.inf, dtype=np.float32)
    bbox_max = np.full(3, -np.inf, dtype=np.float32)
    
    tilt_rad = ps.saturn_tilt
    tilt_matrix = np.array([
        [1, 0, 0],
        [0, math.cos(tilt_rad), -math.sin(tilt_rad)],
        [0, math.sin(tilt_rad), math.cos(tilt_rad)]
    ], dtype=np.float32)

    print(f"开始生成 NBL 帧数据 ({total_frames} 帧)...")
    try:
        with open(chunk_temp_path, 'wb') as chunk_file:
            for frame_idx in range(total_frames):
                if frame_idx % 10 == 0:
                    print(f"  处理帧 {frame_idx}/{total_frames}...")
                    
                t = time_steps[frame_idx]
                
                # --- 1. 物理计算 (不变) ---
                spin_ang = 1.0 * t
                spin_matrix = np.array([
                    [math.cos(spin_ang), 0, -math.sin(spin_ang)],
                    [0, 1, 0],
                    [math.sin(spin_ang), 0, math.cos(spin_ang)]
                ], dtype=np.float32)
                
                local_coords = np.vstack([ps.local_x, ps.local_y, ps.local_z]).T 
                rotated_local = local_coords @ spin_matrix.T
                
                saturn_mask = ps.is_saturn
                final_local = rotated_local.copy()
                final_local[saturn_mask] = rotated_local[saturn_mask] @ tilt_matrix.T
                
                orbit_theta = ps.orbit_start_angles + ps.orbit_speeds * 0.2 * t
                planet_x = ps.orbit_radii * np.cos(orbit_theta)
                planet_z = ps.orbit_radii * np.sin(orbit_theta)
                
                world_x = planet_x + final_local[:, 0]
                world_y = final_local[:, 1]
                world_z = planet_z + final_local[:, 2]
                
                frame_min = np.array([world_x.min(), world_y.min(), world_z.min()], dtype=np.float32)
                frame_max = np.array([world_x.max(), world_y.max(), world_z.max()], dtype=np.float32)
                bbox_min = np.minimum(bbox_min, frame_min)
                bbox_max = np.maximum(bbox_max, frame_max)
                
                curr_pos = np.column_stack([world_x, world_y, world_z]).astype(np.float32)
                curr_col = ps.base_colors
                curr_size = ps.sizes
                curr_tex = ps.tex_ids
                
                # --- 2. 帧类型判断与数据打包 ---
                is_iframe = (frame_idx % (I_FRAME_INTERVAL * fps) == 0)
                frame_type = 0 # 默认I帧
                packed_payload = None # 纯数据部分，不含头
                
                # 尝试生成 P-Frame
                if not is_iframe and prev_pos is not None:
                    delta_pos = (curr_pos - prev_pos) * POS_SCALE
                    delta_size = (curr_size - prev_size) * SIZE_SCALE
                    delta_tex = curr_tex - prev_tex
                    delta_col = (curr_col - prev_col).astype(np.int8) if prev_col is not None else np.zeros_like(curr_col, dtype=np.int8)
                    
                    pos_valid = np.all((delta_pos >= -32768) & (delta_pos <= 32767))
                    size_valid = np.all((delta_size >= -32768) & (delta_size <= 32767))
                    tex_valid = np.all((delta_tex >= -128) & (delta_tex <= 127))
                    col_valid = np.all((delta_col >= -128) & (delta_col <= 127))
                    
                    if pos_valid and size_valid and tex_valid and col_valid:
                        frame_type = 1
                        buf = bytearray()
                        buf.extend(delta_pos.astype(np.int16).T.tobytes('C')) # PosDelta
                        buf.extend(delta_col.astype(np.int8).T.tobytes('C'))  # ColDelta
                        buf.extend(delta_size.astype(np.int16).tobytes('C'))  # SizeDelta
                        buf.extend(delta_tex.astype(np.int8).tobytes('C'))    # TexIDDelta
                        buf.extend(np.zeros(num_particles, dtype=np.int8).tobytes('C')) # SeqDelta
                        buf.extend(ps.ids.tobytes('C')) # ParticleIDs
                        packed_payload = buf
                
                # 如果必须是I帧或P帧生成失败，生成I-Frame
                if packed_payload is None:
                    frame_type = 0
                    keyframe_indices.append(frame_idx) # 记录关键帧
                    
                    buf = bytearray()
                    buf.extend(curr_pos.T.tobytes('C')) # Pos
                    buf.extend(curr_col.T.tobytes('C')) # Col
                    buf.extend(curr_size.tobytes('C'))  # Size
                    buf.extend(curr_tex.tobytes('C'))   # TexID
                    buf.extend(np.zeros(num_particles, dtype=np.uint8).tobytes('C')) # Seq
                    buf.extend(ps.ids.tobytes('C'))     # ParticleIDs
                    packed_payload = buf
                
                # --- 3. 压缩修正 (CRITICAL FIX) ---
                # 规范要求：Zstd(Header + Payload)
                # Header = FrameType(1 byte) + ParticleCount(4 bytes)
                raw_chunk_data = struct.pack('<BI', frame_type, num_particles) + packed_payload
                
                # 每一帧必须独立压缩 (clean context)，ZstdCompressor.compress 默认就是单次无状态压缩
                compressed_chunk = cctx.compress(raw_chunk_data)
                
                chunk_file.write(compressed_chunk)
                chunk_sizes.append(len(compressed_chunk))
                
                prev_pos = curr_pos.copy()
                prev_size = curr_size.copy()
                prev_tex = curr_tex.copy()
                prev_col = curr_col.copy()
        
        # 第二步：写入最终NBL文件
        print("正在写入最终NBL文件...")
        with open(filename, 'wb') as f:
            # 1. File Header (48 Bytes)
            f.write(MAGIC) 
            f.write(struct.pack('<H', VERSION)) 
            f.write(struct.pack('<H', fps)) 
            f.write(struct.pack('<I', total_frames))
            f.write(struct.pack('<H', 1)) # TextureCount
            f.write(struct.pack('<H', ATTRIBUTES)) 
            f.write(struct.pack('<fff', *bbox_min)) 
            f.write(struct.pack('<fff', *bbox_max)) 
            f.write(b'\x00' * 4) # Reserved
            
            # 2. Texture Block
            tex_path_str = "minecraft:textures/particle/white_ash.png"
            # write_string 写入了 uint16 len + bytes
            tex_block_size = write_string(f, tex_path_str)
            f.write(struct.pack('<BB', 1, 1)) # rows, cols
            tex_block_size += 2 
            
            # --- 计算偏移量 (OFFSET CALCULATION) ---
            # 头部: 48
            # 纹理块: tex_block_size
            # 帧索引表: total_frames * 12 (uint64 offset + uint32 size)
            # 关键帧表: 4 (count) + len(keyframe_indices) * 4
            
            frame_table_size = total_frames * 12
            keyframe_table_size = 4 + len(keyframe_indices) * 4
            
            # 数据块的起始位置
            current_offset = 48 + tex_block_size + frame_table_size + keyframe_table_size
            
            # 3. Frame Index Table
            for size in chunk_sizes:
                f.write(struct.pack('<QI', current_offset, size))
                current_offset += size
                
            # 4. Keyframe Index Table (CRITICAL MISSING PART FIXED)
            f.write(struct.pack('<I', len(keyframe_indices)))
            for kf_idx in keyframe_indices:
                f.write(struct.pack('<I', kf_idx))
                
            # 5. Frame Data Chunks
            with open(chunk_temp_path, 'rb') as chunk_file:
                # 使用 copyfileobj 高效复制
                import shutil
                shutil.copyfileobj(chunk_file, f)
        
        # 清理
        chunk_temp_path.unlink(missing_ok=True)
        
        print(f"修正版 NBL 文件已生成: {filename}")
        print(f"  关键帧数量: {len(keyframe_indices)}")
        print(f"  文件大小: {os.path.getsize(filename) / 1024 / 1024:.2f} MB")
        
    except Exception as e:
        print(f"出错: {e}")
        traceback.print_exc()
        if chunk_temp_path.exists():
            chunk_temp_path.unlink(missing_ok=True)

if __name__ == "__main__":
    create_nbl_solar_system("solar_system_fixed.nbl", num_particles=100000, duration=20.0, fps=30)
