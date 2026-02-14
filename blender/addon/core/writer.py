import struct
import numpy as np

try:
    import zstandard as zstd

    HAS_ZSTD = True
except ImportError:
    HAS_ZSTD = False
    print("Error: 'zstandard' library is required for NBL format.")


class NBLWriter:
    def __init__(self, filepath, fps, total_frames, texture_list, keyframe_interval=60):
        if not HAS_ZSTD:
            raise RuntimeError("zstandard library missing.")

        self.filepath = filepath
        self.fps = int(fps)
        self.total_frames = total_frames
        self.texture_list = (
            texture_list
            if texture_list
            else ["minecraft:textures/particle/glitter_7.png"]
        )
        self.keyframe_interval = keyframe_interval

        self.frames_index = []
        self.keyframes = []
        self.bbox_min = np.array([float("inf")] * 3, dtype=np.float32)
        self.bbox_max = np.array([float("-inf")] * 3, dtype=np.float32)
        self.current_frame_idx = 0

        self._prev_pids_cache = None
        self._prev_pos_cache = None
        self._prev_col_cache = None
        self._prev_size_cache = None
        self._prev_tex_cache = None
        self._prev_seq_cache = None

        self.file = None
        self.cctx = zstd.ZstdCompressor(level=3)

    def __enter__(self):
        self.file = open(self.filepath, "wb")
        self._write_header()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.file:
            self._finalize_file()
            self.file.close()

    def _write_header(self):
        f = self.file
        f.write(b"NEBULAFX")
        f.write(struct.pack("<H", 1))
        f.write(struct.pack("<H", self.fps))
        f.write(struct.pack("<I", self.total_frames))
        f.write(struct.pack("<H", len(self.texture_list)))
        f.write(struct.pack("<H", 3))
        self.bbox_pos = f.tell()
        f.write(b"\x00" * 28)  # BBox + Reserved

        for tex_path in self.texture_list:
            enc_path = tex_path.encode("utf-8")
            f.write(struct.pack("<H", len(enc_path)))
            f.write(enc_path)
            f.write(struct.pack("<BB", 1, 1))

        self.index_offset_pos = f.tell()
        f.write(b"\x00" * (12 * self.total_frames))
        self.kf_table_pos = f.tell()
        f.write(struct.pack("<I", 0))
        f.write(b"\x00" * (4 * self.total_frames))

    def write_frame(self, pos, col, size, tex_id, seq_idx, pids):
        N = len(pids)
        payload = None
        frame_type = 0  # Default I-Frame

        # 1. 判断是否强制 I-Frame
        force_iframe = (self.current_frame_idx == 0) or (
            self.current_frame_idx % self.keyframe_interval == 0
        )

        # 2. 尝试构建 P-Frame (处理 Update/Spawn/Despawn)
        if not force_iframe:
            payload = self._try_build_p_frame(pos, col, size, tex_id, seq_idx, pids)
            if payload is not None:
                frame_type = 1  # P-Frame 成功
            else:
                force_iframe = True  # 失败 (通常因位移溢出)

        # 3. 构建 I-Frame
        if force_iframe:
            payload = self._build_i_frame(pos, col, size, tex_id, seq_idx, pids)
            frame_type = 0
            self.keyframes.append(self.current_frame_idx)

        # 4. 压缩写入
        raw_header = struct.pack("<BI", frame_type, N)
        compressed_chunk = self.cctx.compress(raw_header + payload)

        offset = self.file.tell()
        self.file.write(compressed_chunk)
        self.frames_index.append((offset, len(compressed_chunk)))

        # 5. 更新 BBox 和缓存
        if N > 0:
            f_min = pos.min(axis=0)
            f_max = pos.max(axis=0)
            self.bbox_min = np.minimum(self.bbox_min, f_min)
            self.bbox_max = np.maximum(self.bbox_max, f_max)

        self._update_prev_map(pos, col, size, tex_id, seq_idx, pids)
        self.current_frame_idx += 1

    def _build_i_frame(self, pos, col, size, tex_id, seq_idx, pids):
        payload = bytearray()
        if len(pids) > 0:
            payload.extend(pos[:, 0].astype("<f4").tobytes())
            payload.extend(pos[:, 1].astype("<f4").tobytes())
            payload.extend(pos[:, 2].astype("<f4").tobytes())
            payload.extend(col[:, 0].astype("B").tobytes())
            payload.extend(col[:, 1].astype("B").tobytes())
            payload.extend(col[:, 2].astype("B").tobytes())
            payload.extend(col[:, 3].astype("B").tobytes())
            payload.extend(size.astype("<H").tobytes())
            payload.extend(tex_id.astype("B").tobytes())
            payload.extend(seq_idx.astype("B").tobytes())
            payload.extend(pids.astype("<i4").tobytes())
        return payload

    def _try_build_p_frame(
        self, curr_pos, curr_col, curr_size, curr_tex, curr_seq, curr_pids
    ):
        """
        全功能 P-Frame 构建器：支持 Update, Spawn, Despawn
        使用 Numpy 向量化操作，拒绝 Python 循环。
        """
        N = len(curr_pids)
        if N == 0:
            return bytearray()

        # 如果上一帧没有数据，所有粒子都是 Spawn，需检查是否全在原点附近
        if self._prev_pids_cache is None or len(self._prev_pids_cache) == 0:
            diff_pos = curr_pos * 1000.0
            if np.any(diff_pos < -32768) or np.any(diff_pos > 32767):
                return None
            return None

        # --- 向量化匹配核心逻辑 ---

        # 1. 准备“上一帧”的数据查找表
        # 为了使用 searchsorted，我们需要对上一帧的 PID 进行排序索引
        prev_pids = self._prev_pids_cache
        sorter = np.argsort(prev_pids)
        sorted_prev_pids = prev_pids[sorter]

        # 2. 在上一帧中查找当前帧的 PID
        # searchsorted 返回插入位置，复杂度 O(N log M)
        insert_indices = np.searchsorted(sorted_prev_pids, curr_pids)

        # 修正越界索引 (防止找不到时 index=len)
        insert_indices = np.clip(insert_indices, 0, len(sorted_prev_pids) - 1)

        # 3. 确认哪些 ID 真正匹配上了 (Update)，哪些是没匹配上的 (Spawn)
        # mask_exist: True = Update (存活), False = Spawn (新生)
        mask_exist = sorted_prev_pids[insert_indices] == curr_pids

        # 4. 构建“对齐后”的上一帧数据数组
        # 如果是 Spawn，上一帧数据视为 0 (Zero-basis principle)

        # 获取匹配到的原始索引
        matched_orig_indices = sorter[insert_indices]

        # --- 坐标差分 ---
        # 创建一个全 0 的基准数组 (应对 Spawn)
        aligned_prev_pos = np.zeros_like(curr_pos)
        # 填入存活粒子的旧坐标
        aligned_prev_pos[mask_exist] = self._prev_pos_cache[
            matched_orig_indices[mask_exist]
        ]

        # 计算 Delta (Scale 1000)
        # Spawn: curr - 0 = curr (绝对值)
        # Update: curr - prev = delta (相对值)
        d_pos_raw = (curr_pos - aligned_prev_pos) * 1000.0

        # 溢出检查 (这是 P-Frame 最大的限制)
        # 如果有任何粒子瞬移超过 32 格，或者新生成的粒子距离原点超过 32 格
        # 必须放弃 P-Frame，回退到 I-Frame
        if np.any(d_pos_raw < -32768) or np.any(d_pos_raw > 32767):
            return None
        d_pos = d_pos_raw.astype("<i2")

        # --- 颜色差分 ---
        aligned_prev_col = np.zeros_like(curr_col)  # Spawn 默认为黑透 (0,0,0,0)
        aligned_prev_col[mask_exist] = self._prev_col_cache[
            matched_orig_indices[mask_exist]
        ]
        d_col_raw = curr_col.astype(np.int16) - aligned_prev_col.astype(np.int16)
        if np.any(d_col_raw < -128) or np.any(d_col_raw > 127):
            return None
        d_col = d_col_raw.astype("b")

        # --- 大小差分 ---
        aligned_prev_size = np.zeros_like(curr_size)
        aligned_prev_size[mask_exist] = self._prev_size_cache[
            matched_orig_indices[mask_exist]
        ]
        d_size_raw = curr_size.astype(np.int32) - aligned_prev_size.astype(np.int32)
        if np.any(d_size_raw < -32768) or np.any(d_size_raw > 32767):
            return None
        d_size = d_size_raw.astype("<i2")

        # --- 贴图/序列差分 ---
        aligned_prev_tex = np.zeros_like(curr_tex)
        aligned_prev_tex[mask_exist] = self._prev_tex_cache[
            matched_orig_indices[mask_exist]
        ]
        d_tex = (curr_tex.astype(np.int16) - aligned_prev_tex.astype(np.int16)).astype(
            "b"
        )

        aligned_prev_seq = np.zeros_like(curr_seq)
        aligned_prev_seq[mask_exist] = self._prev_seq_cache[
            matched_orig_indices[mask_exist]
        ]
        d_seq = (curr_seq.astype(np.int16) - aligned_prev_seq.astype(np.int16)).astype(
            "b"
        )

        # --- 组装 Payload ---
        payload = bytearray()
        payload.extend(d_pos[:, 0].tobytes())
        payload.extend(d_pos[:, 1].tobytes())
        payload.extend(d_pos[:, 2].tobytes())
        payload.extend(d_col[:, 0].tobytes())
        payload.extend(d_col[:, 1].tobytes())
        payload.extend(d_col[:, 2].tobytes())
        payload.extend(d_col[:, 3].tobytes())
        payload.extend(d_size.tobytes())
        payload.extend(d_tex.tobytes())
        payload.extend(d_seq.tobytes())
        payload.extend(curr_pids.astype("<i4").tobytes())

        return payload

    def _update_prev_map(self, pos, col, size, tex_id, seq_idx, pids):
        # 必须显式 copy，防止外部修改 numpy 数组影响内部缓存
        self._prev_pids_cache = pids.copy()
        self._prev_pos_cache = pos.copy()
        self._prev_col_cache = col.copy()
        self._prev_size_cache = size.copy()
        self._prev_tex_cache = tex_id.copy()
        self._prev_seq_cache = seq_idx.copy()

    def _finalize_file(self):
        f = self.file
        f.seek(self.index_offset_pos)
        for offset, size in self.frames_index:
            f.write(struct.pack("<QI", offset, size))

        f.seek(self.kf_table_pos)
        f.write(struct.pack("<I", len(self.keyframes)))
        kf_array = np.array(self.keyframes, dtype=np.uint32)
        f.write(kf_array.astype("<I").tobytes())

        f.seek(self.bbox_pos)
        if np.isinf(self.bbox_min[0]):
            self.bbox_min[:] = 0
        if np.isinf(self.bbox_max[0]):
            self.bbox_max[:] = 0
        f.write(struct.pack("<fff", *self.bbox_min))
        f.write(struct.pack("<fff", *self.bbox_max))

        print(
            f"NBL Export Finished: {self.total_frames} frames, {len(self.keyframes)} keyframes."
        )
