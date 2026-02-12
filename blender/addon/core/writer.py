import struct
import numpy as np
from ..utils.dependencies import HAS_ZSTD

class NBLWriter:
    def __init__(self, filepath, fps, total_frames, texture_list, scale):
        self.filepath = filepath
        self.fps = int(fps)
        self.total_frames = total_frames
        self.texture_list = texture_list
        self.scale = scale
        self.frames_index = []
        self.bbox_min = np.array([float('inf')] * 3, dtype=np.float32)
        self.bbox_max = np.array([float('-inf')] * 3, dtype=np.float32)
        self.keyframes = [] # 记录关键帧的序号
        self.file = None
        self.cctx = None
        if HAS_ZSTD:
            import zstandard as zstd
            self.cctx = zstd.ZstdCompressor(level=1)

    def __enter__(self):
        self.file = open(self.filepath, 'wb')
        self._write_header()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.file:
            self._finalize_file()
            self.file.close()

    def _write_header(self):
        f = self.file
        f.write(b'NEBULAFX')
        f.write(struct.pack('<H', 1)) 
        f.write(struct.pack('<H', self.fps))
        f.write(struct.pack('<I', self.total_frames))
        
        # 确保 header 中的数量与实际写入的一致
        writing_textures = self.texture_list if self.texture_list else ["minecraft:textures/particle/glitter_7.png"]
        f.write(struct.pack('<H', len(writing_textures))) 
        f.write(struct.pack('<H', 3)) # attrs
        
        f.write(b'\x00' * 12) # BBox Min Placeholder
        f.write(b'\x00' * 12) # BBox Max Placeholder
        f.write(b'\x00' * 4)  # Reserved
        
        for tex_path in writing_textures:
            enc_path = tex_path.encode('utf-8')
            f.write(struct.pack('<H', len(enc_path)))
            f.write(enc_path)
            f.write(struct.pack('<BB', 1, 1)) # rows, cols
        
        self.index_offset_pos = f.tell()
        # 预留帧索引表空间 (12 * total_frames)
        f.write(b'\x00' * (12 * self.total_frames))
        
        # 预留关键帧索引表空间 (4 + 4 * total_frames) 
        # 最差情况：每一帧都是关键帧 (当前 Exporter 逻辑就是这样)
        self.kf_table_pos = f.tell()
        f.write(struct.pack('<I', 0)) # Placeholder for KeyframeCount
        f.write(b'\x00' * (4 * self.total_frames))

    def write_frame(self, pos, col, size, tex_id, pid):
        num = len(pos)
        
        # Build Payload
        payload = bytearray()
        if num > 0:
            payload.extend(pos.T.astype('<f4').tobytes('C'))
            payload.extend(col.T.astype('B').tobytes('C'))
            payload.extend(size.astype('<H').tobytes('C'))
            payload.extend(tex_id.astype('B').tobytes('C'))
            payload.extend(np.zeros(num, dtype='B').tobytes('C'))
            payload.extend(pid.astype('<i4').tobytes('C'))
        
        # Compress
        header = struct.pack('<BI', 0, num)
        if self.cctx:
            chunk = self.cctx.compress(header + payload)
        else:
            chunk = header + payload # Should not happen if HAS_ZSTD checked
        
        # Write
        offset = self.file.tell()
        self.file.write(chunk)
        self.frames_index.append((offset, len(chunk)))
        
        # 记录关键帧
        self.keyframes.append(len(self.frames_index) - 1)
        
        # BBox
        if num > 0:
            f_min = pos.min(axis=0)
            f_max = pos.max(axis=0)
            self.bbox_min = np.minimum(self.bbox_min, f_min)
            self.bbox_max = np.maximum(self.bbox_max, f_max)

    def _finalize_file(self):
        f = self.file
        f.seek(self.index_offset_pos)
        for offset, size in self.frames_index:
            f.write(struct.pack('<QI', offset, size))
            
        # 写入关键帧索引表 (Section 4)
        f.seek(self.kf_table_pos)
        f.write(struct.pack('<I', len(self.keyframes)))
        for kf_idx in self.keyframes:
            f.write(struct.pack('<I', kf_idx))
            
        # 写入 BBox (0x14)
        f.seek(0x14)
        if np.isinf(self.bbox_min[0]): self.bbox_min[:] = 0
        if np.isinf(self.bbox_max[0]): self.bbox_max[:] = 0
        f.write(struct.pack('<fff', *self.bbox_min))
        f.write(struct.pack('<fff', *self.bbox_max))
