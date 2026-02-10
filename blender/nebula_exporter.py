bl_info = {
    "name": "NebulaFX NBL Exporter",
    "author": "Atemukesu",
    "version": (1, 0, 1),
    "blender": (3, 0, 0),
    "location": "3D视图 > 侧边栏 > NebulaFX",
    "description": "直接导出二进制 .nbl 格式。修复了属性丢失问题，支持 MMD/粒子/刚体混合。",
    "category": "Import-Export",
}

import bpy
import struct
import math
import sys
import subprocess
import os
import random
import time
import concurrent.futures
import queue
import numpy as np
import bmesh
from mathutils import Vector, Matrix
from bpy.types import Panel, Operator, PropertyGroup
from bpy.props import StringProperty, FloatProperty, EnumProperty, PointerProperty, BoolProperty, IntProperty, FloatVectorProperty

# ==============================================================================
# 依赖检查
# ==============================================================================
def check_zstd_available():
    """动态检查 zstd 是否可用"""
    try:
        import zstandard
        return True
    except ImportError:
        return False

# 初始检查
HAS_ZSTD = check_zstd_available()

# ==============================================================================
# NBL 二进制写入器 (核心)
# ==============================================================================

class NBLWriter:
    def __init__(self, filepath, fps, total_frames, texture_path, scale):
        self.filepath = filepath
        self.fps = int(fps)
        self.total_frames = total_frames
        self.texture_path = texture_path
        self.scale = scale # Blender -> MC 缩放比例
        
        self.frames_index = [] # (offset, size)
        self.bbox_min = np.array([float('inf')] * 3, dtype=np.float32)
        self.bbox_max = np.array([float('-inf')] * 3, dtype=np.float32)
        self.file = None
        self.index_offset_pos = 0

    def __enter__(self):
        self.file = open(self.filepath, 'wb')
        self._write_header_placeholder()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.file:
            self._finalize_file()
            self.file.close()

    def _write_header_placeholder(self):
        f = self.file
        # 1. Magic & Version
        f.write(b'NEBULAFX')
        f.write(struct.pack('<H', 1)) # Version 1
        f.write(struct.pack('<H', self.fps))
        f.write(struct.pack('<I', self.total_frames))
        f.write(struct.pack('<H', 1)) # TextureCount (暂时只支持单张纹理)
        f.write(struct.pack('<H', 3)) # Attributes (Alpha | Size)
        
        # BBox (占位)
        f.write(b'\x00' * 12) # Min
        f.write(b'\x00' * 12) # Max
        f.write(b'\x00' * 4)  # Reserved
        
        # 2. Texture Block
        enc_path = self.texture_path.encode('utf-8')
        f.write(struct.pack('<H', len(enc_path)))
        f.write(enc_path)
        f.write(struct.pack('<BB', 1, 1)) # Rows, Cols (默认 1x1)
        
        # 记录 Index Table 开始的位置，先写一堆0占位
        self.index_offset_pos = f.tell()
        # Index Table: 每个条目 12 字节 (Offset u64 + Size u32)
        f.write(b'\x00' * (12 * self.total_frames))

    def prepare_payload(self, pos_np, col_np, size_np, ids_np):
        """
        [线程安全] 构建未压缩的 Payload 数据
        返回: (num_particles, bytes_payload)
        """
        num = len(pos_np)
        
        # 更新包围盒 (这里需要线程锁吗？还是在主线程做？)
        # 为了安全，包围盒更新最好在主线程完成，或者在这里加锁。
        # 鉴于 numpy 计算很快，我们让主线程计算 bbox min/max 传进来，或者暂时先在主线程算好再调这个。
        # 这里仅负责二进制打包。
        
        # === 1. 构建 Payload (SoA 布局) ===
        payload = bytearray()
        
        if num > 0:
            # A. Positions (3 * N * float32)
            payload.extend(pos_np.T.astype('<f4').tobytes('C'))
            # B. Colors (4 * N * uint8)
            payload.extend(col_np.T.astype('B').tobytes('C'))
            # C. Sizes (N * uint16)
            payload.extend(size_np.astype('<H').tobytes('C'))
            # D. TexIDs (N * uint8)
            payload.extend(np.zeros(num, dtype='B').tobytes('C'))
            # E. SeqIndices (N * uint8)
            payload.extend(np.zeros(num, dtype='B').tobytes('C'))
            # F. ParticleIDs (N * int32)
            payload.extend(ids_np.astype('<i4').tobytes('C'))
            
        return num, payload

    def compress_frame_data(self, num, payload):
        """
        [线程安全] 压缩数据
        """
        # [Header: Type(u8) + Count(u32)]
        frame_type = 0 
        chunk_header = struct.pack('<BI', frame_type, num)
        full_data = chunk_header + payload
        
        # Zstd 压缩 (释放 GIL)
        import zstandard as zstd
        cctx = zstd.ZstdCompressor(level=3)
        return cctx.compress(full_data)

    def write_compressed_chunk(self, compressed_chunk):
        """
        [主线程] 写入压缩块
        """
        current_offset = self.file.tell()
        self.file.write(compressed_chunk)
        self.frames_index.append((current_offset, len(compressed_chunk)))

    def update_bbox(self, frame_min, frame_max):
        """更新总包围盒"""
        self.bbox_min = np.minimum(self.bbox_min, frame_min)
        self.bbox_max = np.maximum(self.bbox_max, frame_max)

    def write_frame(self, pos_np, col_np, size_np, ids_np):
        # 兼容旧接口（单线程模式用）
        num, payload = self.prepare_payload(pos_np, col_np, size_np, ids_np)
        
        if num > 0:
            frame_min = pos_np.min(axis=0)
            frame_max = pos_np.max(axis=0)
            self.update_bbox(frame_min, frame_max)
            
        chunk = self.compress_frame_data(num, payload)
        self.write_compressed_chunk(chunk)

    def _finalize_file(self):
        f = self.file
        
        # 1. 回填 Index Table
        f.seek(self.index_offset_pos)
        for offset, size in self.frames_index:
            f.write(struct.pack('<QI', offset, size))
            
        # 2. 回填 BBox
        f.seek(0x14) # Header 偏移 20 字节处是 BBox
        
        # 如果没有粒子，防止 inf
        if np.isinf(self.bbox_min[0]):
            self.bbox_min[:] = 0
            self.bbox_max[:] = 0
            
        f.write(struct.pack('<fff', *self.bbox_min))
        f.write(struct.pack('<fff', *self.bbox_max))

# ==============================================================================
# 操作符：安装依赖
# ==============================================================================
class NEBULA_OT_InstallDeps(Operator):
    bl_idname = "nebula.install_deps"
    bl_label = "安装 zstandard 库"
    bl_description = "NBL 格式必须使用 Zstd 压缩。点击安装 Python 库。"

    def execute(self, context):
        py_exec = sys.executable
        cmd = [py_exec, "-m", "pip", "install", "zstandard", "-i", "https://pypi.tuna.tsinghua.edu.cn/simple/", "--trusted-host", "pypi.tuna.tsinghua.edu.cn"]
        try:
            self.report({'INFO'}, "正在安装 zstandard，请稍候...")
            subprocess.check_call(cmd)
            
            # 强制重新导入
            global HAS_ZSTD
            import importlib
            import zstandard
            importlib.reload(zstandard)
            HAS_ZSTD = True
            
            self.report({'INFO'}, "安装成功！现在可以导出了。")
            # 强制刷新 UI
            for area in context.screen.areas:
                area.tag_redraw()
        except subprocess.CalledProcessError as e:
            self.report({'ERROR'}, f"pip 安装失败 (错误码 {e.returncode})，请尝试手动安装")
        except Exception as e:
            self.report({'ERROR'}, f"安装失败: {str(e)}")
        return {'FINISHED'}

# ==============================================================================
# 操作符：导出逻辑
# ==============================================================================
# ==============================================================================
# 操作符：导出逻辑 (Modal + 多线程)
# ==============================================================================
class NEBULA_OT_Export(Operator):
    bl_idname = "nebula.export_nbl"
    bl_label = "导出 NBL"
    
    _timer = None
    _writer = None
    _executor = None
    _futures = [] # List[Future]
    _current_frame = 0
    _start_frame = 0
    _end_frame = 0
    _tasks = None
    _props = None
    _write_index = 0
    
    def invoke(self, context, event):
        if not HAS_ZSTD:
            self.report({'ERROR'}, "请先安装依赖库！")
            return {'CANCELLED'}
            
        props = context.scene.nebula_props
        
        # 校验
        if not props.target_collection:
            self.report({'ERROR'}, "未选择集合！")
            return {'CANCELLED'}
        
        objs = [o for o in props.target_collection.all_objects if o.type == 'MESH' and o.visible_get()]
        if not objs:
            self.report({'ERROR'}, "集合中没有可见网格物体")
            return {'CANCELLED'}
            
        # 路径检查
        abs_filepath = bpy.path.abspath(props.filepath)
        if not abs_filepath or abs_filepath.startswith('//'):
            self.report({'ERROR'}, "请先保存 .blend 文件，或使用绝对路径！")
            return {'CANCELLED'}
            
        # 初始化
        self._props = props
        depsgraph = context.evaluated_depsgraph_get()
        self._tasks = self._analyze_scene(props, objs, depsgraph)
        
        if not self._tasks:
            self.report({'ERROR'}, "没有生成任何粒子任务")
            return {'CANCELLED'}
            
        self._start_frame = context.scene.frame_start
        self._end_frame = context.scene.frame_end
        self._current_frame = self._start_frame
        self._write_index = 0
        total_frames = self._end_frame - self._start_frame + 1
        fps = context.scene.render.fps
        
        # 确保目录存在
        out_dir = os.path.dirname(abs_filepath)
        if out_dir and not os.path.exists(out_dir):
            os.makedirs(out_dir)
            
        # 创建 Writer
        self._writer = NBLWriter(abs_filepath, fps, total_frames, props.texture_path, props.scale)
        self._writer.__enter__()
        
        # 创建线程池
        self._executor = concurrent.futures.ThreadPoolExecutor(max_workers=max(1, os.cpu_count() - 1))
        self._futures = []
        
        # 开启进度条
        context.window_manager.progress_begin(0, total_frames)
        
        # 注册 Timer
        wm = context.window_manager
        self._timer = wm.event_timer_add(0.01, window=context.window)
        wm.modal_handler_add(self)
        
        return {'RUNNING_MODAL'}

    def modal(self, context, event):
        if event.type == 'ESC':
            return self.cancel(context)
            
        if event.type == 'TIMER':
            # 1. 采样与提交任务 (如果还没采完) 
            # 限制队列长度防止内存爆炸 (比如最多缓冲 20 帧)
            if self._current_frame <= self._end_frame and len(self._futures) - self._write_index < 20:
                self.process_current_frame(context)
                self._current_frame += 1
            
            # 2. 检查并写入已完成的任务 (按顺序)
            self.check_write_queue(context)
            
            # 3. 检查是否全部完成
            if self._current_frame > self._end_frame and self._write_index >= len(self._futures):
                return self.finish(context)
                
        return {'PASS_THROUGH'}
        
    def process_current_frame(self, context):
        """主线程：采样数据，并提交压缩任务"""
        frame = self._current_frame
        context.scene.frame_set(frame)
        
        # 采样 (耗时点 A)
        pos_list, col_list, size_list, id_list = self._process_frame(context, self._tasks, self._props)
        
        # 合并数据
        if not pos_list:
             final_pos = np.empty((0,3), dtype=np.float32)
             final_col = np.empty((0,4), dtype=np.uint8)
             final_size = np.empty(0, dtype=np.uint16)
             final_ids = np.empty(0, dtype=np.int32)
             frame_min = np.array([float('inf')]*3)
             frame_max = np.array([float('-inf')]*3)
        else:
            final_pos = np.vstack(pos_list)
            final_col = np.vstack(col_list)
            final_size = np.concatenate(size_list)
            final_ids = np.concatenate(id_list)
            
            # 在主线程计算 BBox (非常快)
            if len(final_pos) > 0:
                frame_min = final_pos.min(axis=0)
                frame_max = final_pos.max(axis=0)
            else:
                frame_min = np.array([float('inf')]*3)
                frame_max = np.array([float('-inf')]*3)
        
        # 准备未压缩的 Payload
        num, payload = self._writer.prepare_payload(final_pos, final_col, final_size, final_ids)
        
        # 提交压缩任务到线程池 (耗时点 B)
        future = self._executor.submit(self._writer.compress_frame_data, num, payload)
        
        # 存储 Future 和 BBox 信息，以便后续按顺序写入
        self._futures.append({
            'future': future, 
            'bbox_min': frame_min, 
            'bbox_max': frame_max,
            'frame': frame
        })
        
    def check_write_queue(self, context):
        """检查队列头部的任务是否完成，完成则写入"""
        while self._write_index < len(self._futures):
            item = self._futures[self._write_index]
            future = item['future']
            
            if future.done():
                try:
                    compressed_chunk = future.result()
                    
                    # 更新 Writer 状态
                    self._writer.update_bbox(item['bbox_min'], item['bbox_max'])
                    self._writer.write_compressed_chunk(compressed_chunk)
                    
                    # 更新进度
                    written_count = self._write_index + 1
                    total = self._end_frame - self._start_frame + 1
                    context.window_manager.progress_update(written_count)
                    
                    # 可选：打印日志减少频率
                    if written_count % 10 == 0 or written_count == total:
                         print(f"Exported {written_count}/{total}")
                         
                    self._write_index += 1
                except Exception as e:
                    self.report({'ERROR'}, f"压缩/写入失败: {str(e)}")
                    return self.cancel(context)
            else:
                # 头部任务没好，后面的也不能写（必须按顺序）
                break

    def finish(self, context):
        context.window_manager.progress_end()
        self._writer.__exit__(None, None, None)
        self._executor.shutdown()
        
        wm = context.window_manager
        wm.event_timer_remove(self._timer)
        
        output_path = self._writer.filepath
        self.report({'INFO'}, f"导出完成！共 {self._write_index} 帧")
        
        # 恢复可能的UI状态
        return {'FINISHED'}
        
    def cancel(self, context):
        context.window_manager.progress_end()
        if self._writer:
            self._writer.file.close() # 强制关闭
        if self._executor:
            self._executor.shutdown(wait=False)
            
        wm = context.window_manager
        if self._timer:
            wm.event_timer_remove(self._timer)
            
        self.report({'WARNING'}, "导出已取消")
        return {'CANCELLED'}

    def execute(self, context):
        # 这里的 execute 只在非 invoke 调用时触发（通常不会）
        return self.invoke(context, None)

    def _analyze_scene(self, props, objs, depsgraph):
        """生成任务列表"""
        tasks = []
        global_id_counter = 0
        
        for obj in objs:
            eval_obj = obj.evaluated_get(depsgraph)
            
            # 1. 粒子系统
            if props.enable_particles and len(eval_obj.particle_systems) > 0:
                for ps in eval_obj.particle_systems:
                    tasks.append({
                        'type': 'PARTICLE',
                        'obj_name': obj.name,
                        'sys_name': ps.name,
                        'start_id': global_id_counter
                    })
                    # 预估最大粒子数做ID偏移
                    global_id_counter += 100000 

            # 2. 骨骼/MMD (顶点锁定)
            is_deforming = any(m.type == 'ARMATURE' for m in obj.modifiers) or (obj.data.shape_keys)
            if props.enable_mmd and is_deforming:
                mesh = eval_obj.to_mesh()
                v_count = len(mesh.vertices)
                step = int(1.0 / props.mmd_density)
                if step < 1: step = 1
                indices = np.arange(0, v_count, step)
                
                tasks.append({
                    'type': 'MMD',
                    'obj_name': obj.name,
                    'indices': indices,
                    'base_id': global_id_counter
                })
                global_id_counter += len(indices) + 100
                eval_obj.to_mesh_clear()
                
            # 3. 刚体
            elif props.enable_rigid and not is_deforming:
                tasks.append({
                    'type': 'RIGID',
                    'obj_name': obj.name,
                    'id': global_id_counter
                })
                global_id_counter += 1
                
        return tasks

    def _process_frame(self, context, tasks, props):
        depsgraph = context.evaluated_depsgraph_get()
        pos_list = []
        col_list = []
        size_list = []
        id_list = []
        
        # 缓存本帧需要的 eval objects
        cache_eval = {}
        cache_uvs = {}
        cache_tex = {} # mat_name -> pixels
        
        # 辅助函数：准备纹理
        def prepare_texture(obj, mesh):
            if not props.use_texture_color: return
            if not mesh.uv_layers.active: return
            
            # 缓存 UV
            if obj.name not in cache_uvs:
                loops = mesh.loops
                uv_layer = mesh.uv_layers.active.data
                
                # 创建一个 (V, 2) 的数组
                v_count = len(mesh.vertices)
                uv_arr = np.zeros((v_count, 2), dtype=np.float32)
                
                # 这里用 Python 循环太慢，使用 foreach
                loop_v_indices = np.zeros(len(loops), dtype=np.int32)
                loops.foreach_get('vertex_index', loop_v_indices)
                
                raw_uvs = np.zeros(len(uv_layer)*2, dtype=np.float32)
                uv_layer.foreach_get('uv', raw_uvs)
                raw_uvs = raw_uvs.reshape(-1, 2)
                
                # 覆盖式写入
                uv_arr[loop_v_indices] = raw_uvs
                cache_uvs[obj.name] = uv_arr
                
            # 缓存图片
            if obj.material_slots:
                mat = obj.material_slots[0].material
                if mat and mat.use_nodes and mat.name not in cache_tex:
                     for n in mat.node_tree.nodes:
                        if n.type == 'TEX_IMAGE' and n.image:
                            img = n.image
                            if len(img.pixels) > 0:
                                pixels = np.array(img.pixels[:]).reshape(img.size[1], img.size[0], img.channels)
                                cache_tex[mat.name] = pixels
                            break

        for task in tasks:
            name = task['obj_name']
            if name not in cache_eval:
                obj = bpy.data.objects.get(name)
                if obj: cache_eval[name] = obj.evaluated_get(depsgraph)
            
            eval_obj = cache_eval.get(name)
            if not eval_obj: continue
            
            # --- 处理 A: 粒子 ---
            if task['type'] == 'PARTICLE':
                if task['sys_name'] in eval_obj.particle_systems:
                    ps = eval_obj.particle_systems[task['sys_name']]
                    count = len(ps.particles)
                    if count > 0:
                        # 读取位置
                        locs = np.zeros(count * 3, dtype=np.float32)
                        ps.particles.foreach_get('location', locs)
                        locs = locs.reshape(-1, 3)
                        
                        # 转换坐标系: Blender(XYZ) -> MC(XZY) * Scale
                        mc_pos = np.zeros_like(locs)
                        mc_pos[:, 0] = locs[:, 0] * props.scale
                        mc_pos[:, 1] = locs[:, 2] * props.scale # Y = Z
                        mc_pos[:, 2] = locs[:, 1] * props.scale # Z = Y
                        
                        # 读取大小
                        sizes = np.zeros(count, dtype=np.float32)
                        ps.particles.foreach_get('size', sizes)
                        
                        # 过滤死粒子 (size ~= 0)
                        mask = sizes > 0.001
                        
                        valid_pos = mc_pos[mask]
                        valid_sizes = (sizes[mask] * 100).astype(np.uint16) # NBL size is val/100
                        
                        num = len(valid_pos)
                        if num > 0:
                            # 颜色
                            cols = np.full((num, 4), 255, dtype=np.uint8)
                            if props.color_source == 'FIXED':
                                c = props.fixed_color
                                cols[:, 0] = int(c[0]*255)
                                cols[:, 1] = int(c[1]*255)
                                cols[:, 2] = int(c[2]*255)
                                
                            # ID
                            ids = np.arange(task['start_id'], task['start_id'] + num, dtype=np.int32)
                            
                            pos_list.append(valid_pos)
                            col_list.append(cols)
                            size_list.append(valid_sizes)
                            id_list.append(ids)

            # --- 处理 B: MMD ---
            elif task['type'] == 'MMD':
                mesh = eval_obj.to_mesh()
                prepare_texture(bpy.data.objects[name], mesh)
                
                indices = task['indices']
                
                # 读取顶点
                v_count = len(mesh.vertices)
                co = np.zeros(v_count * 3, dtype=np.float32)
                mesh.vertices.foreach_get('co', co)
                co = co.reshape(-1, 3)
                
                # 应用世界矩阵
                mat = eval_obj.matrix_world
                
                # 提取目标顶点
                if len(indices) > 0 and indices.max() < len(co):
                    target_co = co[indices]
                    
                    # 变换
                    # Apply rotation/scale
                    rot = np.array(mat.to_3x3())
                    trans = np.array(mat.translation)
                    world_co = target_co @ rot.T + trans
                    
                    # 转 MC 坐标
                    mc_pos = np.zeros_like(world_co)
                    mc_pos[:, 0] = world_co[:, 0] * props.scale
                    mc_pos[:, 1] = world_co[:, 2] * props.scale
                    mc_pos[:, 2] = world_co[:, 1] * props.scale
                    
                    num = len(mc_pos)
                    
                    # 颜色处理
                    cols = np.full((num, 4), 255, dtype=np.uint8)
                    if props.color_source == 'FIXED':
                        c = props.fixed_color
                        cols[:] = [int(c[0]*255), int(c[1]*255), int(c[2]*255), 255]
                    elif props.use_texture_color and name in cache_uvs:
                        # 采样纹理
                        uv_arr = cache_uvs[name]
                        mat_name = ""
                        if bpy.data.objects[name].material_slots:
                            mat_name = bpy.data.objects[name].material_slots[0].material.name
                        
                        if mat_name in cache_tex:
                            pixels = cache_tex[mat_name]
                            h, w, _ = pixels.shape
                            
                            target_uvs = uv_arr[indices]
                            
                            # 向量化采样
                            us = target_uvs[:, 0] % 1.0
                            vs = 1.0 - (target_uvs[:, 1] % 1.0) # Flip Y
                            xs = (us * (w-1)).astype(int)
                            ys = (vs * (h-1)).astype(int)
                            
                            # 边界检查
                            np.clip(xs, 0, w-1, out=xs)
                            np.clip(ys, 0, h-1, out=ys)
                            
                            sampled = pixels[ys, xs] # (N, 3) or (N, 4)
                            cols[:, :3] = (sampled[:, :3] * 255).astype(np.uint8)

                    # 大小 & ID
                    sizes = np.full(num, int(props.particle_size * 100), dtype=np.uint16)
                    ids = np.arange(task['base_id'], task['base_id'] + num, dtype=np.int32)
                    
                    pos_list.append(mc_pos)
                    col_list.append(cols)
                    size_list.append(sizes)
                    id_list.append(ids)
                
                eval_obj.to_mesh_clear()
                
            # --- 处理 C: 刚体 ---
            elif task['type'] == 'RIGID':
                pos = eval_obj.matrix_world.translation
                mc_pos = np.array([[
                    pos.x * props.scale,
                    pos.z * props.scale,
                    pos.y * props.scale
                ]], dtype=np.float32)
                
                cols = np.array([[255, 255, 255, 255]], dtype=np.uint8)
                if props.color_source == 'FIXED':
                    c = props.fixed_color
                    cols[0] = [int(c[0]*255), int(c[1]*255), int(c[2]*255), 255]
                    
                sizes = np.array([int(props.particle_size * 100)], dtype=np.uint16)
                ids = np.array([task['id']], dtype=np.int32)
                
                pos_list.append(mc_pos)
                col_list.append(cols)
                size_list.append(sizes)
                id_list.append(ids)
                
        return pos_list, col_list, size_list, id_list

# ==============================================================================
# UI 面板
# ==============================================================================
class NebulaProps(PropertyGroup):
    # === 修复：补全缺失的属性定义 ===
    target_collection: PointerProperty(
        name="目标集合", 
        type=bpy.types.Collection,
        description="选择包含所有导出对象的集合"
    )
    
    filepath: StringProperty(name="NBL 文件路径", subtype='FILE_PATH', default="//output.nbl")
    
    texture_path: StringProperty(
        name="材质路径 (MC)", 
        default="minecraft:textures/particle/white_ash.png",
        description="游戏内读取的纹理资源路径"
    )
    
    scale: FloatProperty(name="缩放比例", default=10.0, min=0.1)
    
    # 颜色设置
    color_source: EnumProperty(
        name="颜色模式",
        items=[('TEXTURE', "使用纹理颜色 (MMD)", ""), ('FIXED', "固定颜色", "")],
        default='TEXTURE'
    )
    
    fixed_color: FloatVectorProperty(
        name="颜色", subtype='COLOR', default=(1, 1, 1), min=0, max=1
    )
    
    # 混合开关
    enable_mmd: BoolProperty(name="导出 MMD/蒙皮", default=True)
    mmd_density: FloatProperty(name="MMD 采样密度", default=0.5, min=0.01, max=1.0)
    
    enable_particles: BoolProperty(name="导出 粒子系统", default=True)
    enable_rigid: BoolProperty(name="导出 刚体", default=True)
    
    particle_size: FloatProperty(name="默认大小", default=0.15)
    
    # 辅助属性用于代码逻辑
    use_texture_color: BoolProperty(get=lambda self: self.color_source == 'TEXTURE')

class NEBULA_PT_Panel(Panel):
    bl_label = "NebulaFX NBL Exporter"
    bl_idname = "NEBULA_PT_Panel"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = 'NebulaFX'

    def draw(self, context):
        layout = self.layout
        props = context.scene.nebula_props

        # 依赖检查 (每次绘制时动态检查)
        zstd_ok = check_zstd_available()
        if not zstd_ok:
            box = layout.box()
            box.alert = True
            box.label(text="缺少 zstandard 库", icon='ERROR')
            box.label(text="NBL 格式需要 Zstd 压缩支持")
            box.operator("nebula.install_deps", text="一键安装依赖 (清华镜像)", icon='IMPORT')
            return

        # 文件与材质
        box = layout.box()
        box.label(text="1. 输出设置", icon='FILE_FOLDER')
        box.prop(props, "filepath", text="")
        box.label(text="纹理路径 (Resource Location):", icon='TEXTURE')
        box.prop(props, "texture_path", text="")
        
        # 颜色与外观
        box = layout.box()
        box.label(text="2. 外观", icon='SHADING_RENDERED')
        box.prop(props, "color_source", expand=True)
        
        # 修复：只有在选择固定颜色时才显示颜色选择器
        if props.color_source == 'FIXED':
            box.prop(props, "fixed_color", text="")
            
        box.prop(props, "particle_size", text="基础大小")
        box.prop(props, "scale", text="缩放 (Blender->MC)")

        # 导出范围
        box = layout.box()
        box.label(text="3. 导出内容", icon='OUTLINER_COLLECTION')
        box.prop(context.scene.nebula_props, "target_collection", text="目标集合")
        
        col = box.column(align=True)
        col.prop(props, "enable_mmd", text="骨骼模型 (MMD)")
        if props.enable_mmd:
            sub = col.row()
            sub.prop(props, "mmd_density", slider=True, text="采样密度")
            
        col.prop(props, "enable_particles", text="粒子系统")
        col.prop(props, "enable_rigid", text="刚体物体")

        # 导出按钮
        layout.separator()
        layout.operator("nebula.export_nbl", text="导出 .nbl 文件", icon='EXPORT')

# ==============================================================================
# 注册
# ==============================================================================
classes = (
    NBLWriter, # 仅作类定义，不需要注册
    NebulaProps,
    NEBULA_OT_InstallDeps,
    NEBULA_OT_Export,
    NEBULA_PT_Panel
)

def register():
    # 注册 PropertyGroup
    bpy.utils.register_class(NebulaProps)
    bpy.utils.register_class(NEBULA_OT_InstallDeps)
    bpy.utils.register_class(NEBULA_OT_Export)
    bpy.utils.register_class(NEBULA_PT_Panel)
    
    bpy.types.Scene.nebula_props = PointerProperty(type=NebulaProps)

def unregister():
    del bpy.types.Scene.nebula_props
    bpy.utils.unregister_class(NEBULA_PT_Panel)
    bpy.utils.unregister_class(NEBULA_OT_Export)
    bpy.utils.unregister_class(NEBULA_OT_InstallDeps)
    bpy.utils.unregister_class(NebulaProps)

if __name__ == "__main__":
    register()