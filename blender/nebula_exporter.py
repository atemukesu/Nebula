bl_info = {
    "name": "NebulaFX NBL Exporter",
    "author": "Atemukesu",
    "version": (3, 0, 0),
    "blender": (3, 0, 0),
    "location": "3D视图 > 侧边栏 > NebulaFX",
    "description": "极速版 NBL 导出：使用重心坐标追踪技术，大幅提升导出速度。",
    "category": "Import-Export",
}

import bpy
import struct
import numpy as np
from bpy.types import Panel, Operator, PropertyGroup, UIList
from bpy.props import StringProperty, FloatProperty, PointerProperty, BoolProperty, IntProperty, CollectionProperty
from mathutils import Vector

# ==============================================================================
# 依赖检查 (Zstd)
# ==============================================================================
HAS_ZSTD = False
try:
    import zstandard
    HAS_ZSTD = True
except ImportError:
    pass

# ==============================================================================
# 核心算法：重心追踪器 (一次计算，多帧复用)
# ==============================================================================
class ParticleTracker:
    def __init__(self):
        self.initialized = False
        # 静态数据 (只算一次)
        self.tri_indices = None  # (N,) 粒子属于哪个三角形索引
        self.bary_weights = None # (N, 3) 重心权重 (u, v, w)
        self.static_colors = None # (N, 4) 预计算的颜色
        self.static_uvs = None    # (N, 2) 预计算的 UV
        self.mat_indices = None   # (N,) 材质ID
        
        # 拓扑缓存
        self.loop_tri_verts = None # (T, 3) 三角形的三个顶点索引
        self.vertex_count = 0

    def precompute_distribution(self, mesh, density, seed=0):
        """第一帧调用：计算分布"""
        mesh.calc_loop_triangles()
        loop_tris = mesh.loop_triangles
        
        # 1. 获取基础数据
        self.vertex_count = len(mesh.vertices)
        tri_count = len(loop_tris)
        
        # 缓存三角形顶点索引 (T, 3)
        self.loop_tri_verts = np.zeros((tri_count, 3), dtype=np.int32)
        loop_tris.foreach_get("vertices", self.loop_tri_verts.ravel())
        
        # 缓存材质索引
        tri_mat_indices = np.zeros(tri_count, dtype=np.int32)
        loop_tris.foreach_get("material_index", tri_mat_indices)

        # 2. 计算面积 (Vectorized)
        verts = np.zeros((self.vertex_count, 3), dtype=np.float32)
        mesh.vertices.foreach_get("co", verts.ravel())
        
        v0 = verts[self.loop_tri_verts[:, 0]]
        v1 = verts[self.loop_tri_verts[:, 1]]
        v2 = verts[self.loop_tri_verts[:, 2]]
        cross = np.cross(v1 - v0, v2 - v0)
        areas = np.sqrt(np.sum(cross**2, axis=1)) * 0.5
        
        total_area = np.sum(areas)
        if total_area <= 0: return False
        
        # 3. 随机选取三角形 (Weighted Sampling)
        target_count = int(total_area * density * 10)
        if target_count < 1: return False
        
        probs = areas / total_area
        rng = np.random.default_rng(seed)
        
        # 选出的三角形 ID
        self.tri_indices = rng.choice(tri_count, size=target_count, p=probs)
        self.mat_indices = tri_mat_indices[self.tri_indices]
        
        # 4. 生成重心坐标 (Barycentric Weights)
        # r1, r2 用于生成均匀分布在三角形内的点
        r1 = rng.random(target_count).astype(np.float32)
        r2 = rng.random(target_count).astype(np.float32)
        sqrt_r1 = np.sqrt(r1)
        
        w_u = 1.0 - sqrt_r1
        w_v = sqrt_r1 * (1.0 - r2)
        w_w = sqrt_r1 * r2
        
        self.bary_weights = np.stack((w_u, w_v, w_w), axis=1) # (N, 3)
        
        # 5. 计算 UV (用于颜色采样)
        if mesh.uv_layers.active:
            # 获取 UV 数据
            uv_layer = mesh.uv_layers.active.data
            all_uvs = np.zeros((len(uv_layer), 2), dtype=np.float32)
            uv_layer.foreach_get("uv", all_uvs.ravel())
            
            # 获取三角形对应的 Loop Index
            # 注意：mesh.loop_triangles.loops 存储的是 loop index
            tri_loops = np.zeros((tri_count, 3), dtype=np.int32)
            loop_tris.foreach_get("loops", tri_loops.ravel())
            
            # 选中的 Loop Indices
            chosen_loops = tri_loops[self.tri_indices] # (N, 3)
            
            uv0 = all_uvs[chosen_loops[:, 0]]
            uv1 = all_uvs[chosen_loops[:, 1]]
            uv2 = all_uvs[chosen_loops[:, 2]]
            
            # 插值 UV = u*UV0 + v*UV1 + w*UV2
            self.static_uvs = (uv0 * w_u[:, None] + uv1 * w_v[:, None] + uv2 * w_w[:, None])
        else:
            self.static_uvs = np.zeros((target_count, 2), dtype=np.float32)
            
        self.initialized = True
        return True

    def compute_positions(self, mesh):
        """后续帧调用：极速计算位置"""
        if not self.initialized: return None
        
        # 1. 快速读取当前帧顶点 (即使变形了，只要拓扑不变，索引就不变)
        current_verts = np.zeros((len(mesh.vertices), 3), dtype=np.float32)
        mesh.vertices.foreach_get("co", current_verts.ravel())
        
        # 2. 获取选中三角形的三个顶点坐标
        # self.tri_indices 是选中的三角形 ID
        # self.loop_tri_verts 是所有三角形的顶点 ID Map
        
        # 获取 N 个粒子对应的三个顶点索引
        chosen_tri_verts = self.loop_tri_verts[self.tri_indices] # (N, 3)
        
        p0 = current_verts[chosen_tri_verts[:, 0]]
        p1 = current_verts[chosen_tri_verts[:, 1]]
        p2 = current_verts[chosen_tri_verts[:, 2]]
        
        # 3. 矩阵乘法计算最终位置
        # Pos = p0*w0 + p1*w1 + p2*w2
        final_pos = (p0 * self.bary_weights[:, 0:1] + 
                     p1 * self.bary_weights[:, 1:2] + 
                     p2 * self.bary_weights[:, 2:3])
                     
        return final_pos

    def bake_colors(self, obj_materials, image_cache, report_fn=None):
        """改进版：优先查找连接到 Base Color 的图片"""
        if not self.initialized: return
        
        count = len(self.tri_indices)
        self.static_colors = np.full((count, 4), 255, dtype=np.uint8)
        
        unique_mats = np.unique(self.mat_indices)
        
        for m_idx in unique_mats:
            if m_idx >= len(obj_materials) or m_idx < 0: continue
            mat = obj_materials[m_idx]
            if not mat: continue
             
            target_img = None
            found_method = "Default (White)"

            if mat.use_nodes:
                # 1. 尝试找到 Principled BSDF 节点
                bsdf = next((n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
                if bsdf and bsdf.inputs['Base Color'].is_linked:
                    # 获取连接到 Base Color 的节点
                    link_node = bsdf.inputs['Base Color'].links[0].from_node
                    if link_node.type == 'TEX_IMAGE':
                        target_img = link_node.image
                        found_method = "Base Color"
                
                # 2. 如果没找到，回退到旧方法
                if not target_img:
                    nodes = mat.node_tree.nodes
                    # 优先找 active 节点
                    if nodes.active and nodes.active.type == 'TEX_IMAGE':
                        target_img = nodes.active.image
                        found_method = "Active Node"
                    else:
                        for node in nodes:
                            if node.type == 'TEX_IMAGE' and node.image:
                                target_img = node.image
                                found_method = "First Image Node"
                                break
            
            if report_fn:
                img_name = target_img.name if target_img else "None"
                report_fn(f"Mat: {mat.name} | {found_method} | {img_name}")

            if not target_img or target_img.name not in image_cache: continue
            
            # 采样
            pixels, w, h = image_cache[target_img.name]
            mask = (self.mat_indices == m_idx)
            sub_uvs = self.static_uvs[mask]
            
            u = sub_uvs[:, 0] % 1.0
            v = sub_uvs[:, 1] % 1.0
            
            x = (u * w).astype(np.int32)
            y = (v * h).astype(np.int32)
            np.clip(x, 0, w-1, out=x)
            np.clip(y, 0, h-1, out=y)
            
            # 注意：Blender 图片数据是平铺的
            sampled = pixels[y, x]
            self.static_colors[mask] = (sampled * 255).astype(np.uint8)

# ==============================================================================
# NBL Writer (轻微优化)
# ==============================================================================
class NBLWriter:
    # ... (保持之前的 NBLWriter 类不变，只负责写入二进制) ...
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
        import zstandard as zstd
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
        cctx = zstd.ZstdCompressor(level=1) # Level 1 is fastest
        chunk = cctx.compress(header + payload)
        
        # Write
        offset = self.file.tell()
        self.file.write(chunk)
        self.frames_index.append((offset, len(chunk)))
        
        # 记录关键帧 (当前版本所有非空帧默认都是 I-Frame，即 Type 0)
        # 这里虽然我们硬编码了 0，但在逻辑上记录一下
        # 注意：这里需要传入当前是第几帧，或者依赖 self.frames_index 的长度
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

# ==============================================================================
# 导出 Operator (重构版)
# ==============================================================================
class NEBULA_OT_ShowStats(Operator):
    bl_idname = "nebula.show_stats"
    bl_label = "查看预估统计"
    
    def execute(self, context):
        props = context.scene.nebula_props
        depsgraph = context.evaluated_depsgraph_get()
        target_col = props.target_collection
        
        if not target_col:
            self.report({'ERROR'}, "未选择目标集合")
            return {'CANCELLED'}
        
        # 1. 准备 Dummy Cache 用于检测贴图 (不实际读取像素，只为跑通逻辑)
        dummy_cache = {}
        for img in bpy.data.images:
            # 使用 1x1 的像素数据模拟
            dummy_cache[img.name] = (np.zeros((1, 1, 4), dtype=np.float32), 1, 1)

        total_particles = 0
        min_pos = np.array([float('inf')] * 3)
        max_pos = np.array([float('-inf')] * 3)
        
        texture_reports = []
        reported_mats = set()

        def collect_report(msg):
            # 去重：同名材质只报一次
            if msg not in reported_mats:
                texture_reports.append(msg)
                reported_mats.add(msg)

        # 临时计算第一帧
        for obj in target_col.all_objects:
            if obj.type == 'MESH' and obj.visible_get():
                eval_obj = obj.evaluated_get(depsgraph)
                mesh = eval_obj.to_mesh()
                
                # 模拟 Tracker 计算
                tracker = ParticleTracker()
                if tracker.precompute_distribution(mesh, props.sampling_density):
                    count = len(tracker.tri_indices)
                    total_particles += count
                    
                    # 运行颜色烘焙逻辑来检测贴图
                    tracker.bake_colors(mesh.materials, dummy_cache, collect_report)

                    # 估算 BBox (基于 Object BBox 变换)
                    bbox = np.array([v[:] for v in eval_obj.bound_box])
                    mat = eval_obj.matrix_world
                    
                    # 变换 BBox 到世界坐标
                    world_bbox = np.array([mat @ Vector(v) for v in bbox])
                    
                    # 转换到 MC 坐标系并缩放
                    mc_bbox = np.zeros_like(world_bbox)
                    mc_bbox[:, 0] = world_bbox[:, 0] * props.scale
                    mc_bbox[:, 1] = world_bbox[:, 2] * props.scale # YZ 翻转
                    mc_bbox[:, 2] = world_bbox[:, 1] * props.scale
                    
                    min_pos = np.minimum(min_pos, mc_bbox.min(axis=0))
                    max_pos = np.maximum(max_pos, mc_bbox.max(axis=0))
                
                eval_obj.to_mesh_clear()
        
        if total_particles == 0:
            self.report({'WARNING'}, "没有生成任何粒子，请检查密度或模型")
            return {'CANCELLED'}
            
        dims = max_pos - min_pos
        
        def show_msg(self, context):
            layout = self.layout
            layout.label(text=f"预估粒子总数: {total_particles:,}")
            layout.label(text=f"MC 尺寸: {dims[0]:.1f} x {dims[2]:.1f} x {dims[1]:.1f}")
            layout.separator()
            layout.label(text="材质检查报告:")
            for msg in texture_reports:
                # 简单的颜色区分
                if "Default" in msg:
                    layout.label(text=msg, icon='ERROR')
                else:
                    layout.label(text=msg, icon='CHECKMARK')

        context.window_manager.popup_menu(show_msg, title="NebulaFX 统计 & 材质检查", icon='INFO')
        
        return {'FINISHED'}
class NEBULA_OT_ExportFast(Operator):
    bl_idname = "nebula.export_nbl"
    bl_label = "极速导出 NBL"
    
    def invoke(self, context, event):
        if not HAS_ZSTD:
            self.report({'ERROR'}, "需安装 zstandard")
            return {'CANCELLED'}
        
        # 准备数据
        self.props = context.scene.nebula_props
        self.depsgraph = context.evaluated_depsgraph_get()
        self.trackers = {} # obj_name -> ParticleTracker
        
        # 材质映射
        self.mat_to_tex_id = {}
        self.tex_paths = []
        for i, item in enumerate(self.props.texture_list):
            self.tex_paths.append(item.texture_path)
            self.mat_to_tex_id[item.material_name] = i
            
        # 预加载图像 (只做一次)
        self.image_cache = {}
        for img in bpy.data.images:
            if img.size[0] > 0:
                arr = np.array(img.pixels[:], dtype=np.float32)
                arr = arr.reshape(img.size[1], img.size[0], img.channels)
                if img.channels == 3:
                    arr = np.dstack((arr, np.ones((img.size[1], img.size[0], 1))))
                self.image_cache[img.name] = (arr, img.size[0], img.size[1])
                
        self.execute_export(context)
        return {'FINISHED'}

    def execute_export(self, context):
        scene = context.scene
        start = scene.frame_start
        end = scene.frame_end
        target_col = self.props.target_collection
        
        # 1. 初始化 Tracker (在起始帧进行)
        scene.frame_set(start)
        for obj in target_col.all_objects:
            if obj.type == 'MESH' and obj.visible_get():
                # 必须强制更新 depsgraph 确保拿到的是变形后的网格
                # 但第一帧通常是 T-Pose 或初始动作
                eval_obj = obj.evaluated_get(self.depsgraph)
                mesh = eval_obj.to_mesh()
                
                tracker = ParticleTracker()
                success = tracker.precompute_distribution(mesh, self.props.sampling_density)
                if success:
                    tracker.bake_colors(mesh.materials, self.image_cache, lambda msg: self.report({'INFO'}, msg))
                    self.trackers[obj.name] = tracker
                
                eval_obj.to_mesh_clear()
        
        # 2. 逐帧导出
        writer = NBLWriter(
            bpy.path.abspath(self.props.filepath),
            scene.render.fps,
            end - start + 1,
            self.tex_paths,
            self.props.scale
        )
        
        context.window_manager.progress_begin(0, end - start)
        
        with writer:
            for frame in range(start, end + 1):
                scene.frame_set(frame)
                # 这一步是关键：必须通知 Blender 更新 Depsgraph
                # 否则 evaluated_get 拿到的可能是旧数据
                self.depsgraph.update() 
                
                # --- 新增：强制刷新 UI，防止未响应 ---
                bpy.ops.wm.redraw_timer(type='DRAW_WIN_SWAP', iterations=1)
                # ----------------------------------- 
                
                frame_pos = []
                frame_col = []
                frame_size = []
                frame_tex = []
                frame_pid = []
                
                for obj_name, tracker in self.trackers.items():
                    obj = bpy.data.objects.get(obj_name)
                    if not obj: continue
                    
                    eval_obj = obj.evaluated_get(self.depsgraph)
                    mesh = eval_obj.to_mesh() # 这里依然需要 to_mesh 获取当前帧顶点
                    
                    # 极速计算位置
                    local_pos = tracker.compute_positions(mesh)
                    if local_pos is not None:
                        # World Transform
                        mat_world = eval_obj.matrix_world
                        # 批量矩阵变换 (N, 3) @ (3, 3) + (3,)
                        # numpy broadcasting
                        rot = np.array(mat_world.to_3x3()).T # Transpose for multiplication
                        loc = np.array(mat_world.translation)
                        
                        world_pos = local_pos @ rot + loc
                        
                        # MC Space
                        mc_pos = np.empty_like(world_pos)
                        mc_pos[:, 0] = world_pos[:, 0] * self.props.scale
                        mc_pos[:, 1] = world_pos[:, 2] * self.props.scale
                        mc_pos[:, 2] = world_pos[:, 1] * self.props.scale
                        
                        # 组装数据
                        count = len(mc_pos)
                        
                        # 材质 ID 转换
                        t_ids = np.zeros(count, dtype=np.uint8)
                        for m_idx, tex_idx in self.mat_to_tex_id.items():
                            # 这种反向查找有点慢，优化一下：
                            # 应该在 tracker 里直接存 tex_id 而不是 material_index
                            # 但为了简单先这样，numpy mask 很快
                            mat_obj = bpy.data.materials.get(m_idx)
                            if mat_obj:
                                # 找到 tracker.mat_indices 中等于 this 材质索引的位置
                                pass
                        
                        # 简单的一步到位 TexID 生成：
                        # 在 tracker 初始化时其实可以把 material_index 映射成 NBL Tex ID
                        # 现场计算：
                        mapped_tex_ids = np.zeros(count, dtype=np.uint8)
                        for i, m_idx in enumerate(mesh.materials):
                             if m_idx and m_idx.name in self.mat_to_tex_id:
                                 target_id = self.mat_to_tex_id[m_idx.name]
                                 mapped_tex_ids[tracker.mat_indices == i] = target_id
                                 
                        frame_pos.append(mc_pos)
                        frame_col.append(tracker.static_colors)
                        frame_size.append(np.full(count, int(self.props.particle_size * 100), dtype=np.uint16))
                        frame_tex.append(mapped_tex_ids)
                        frame_pid.append(np.arange(count, dtype=np.int32)) # 简单的 ID
                    
                    eval_obj.to_mesh_clear()
                
                if frame_pos:
                    writer.write_frame(
                        np.vstack(frame_pos),
                        np.vstack(frame_col),
                        np.concatenate(frame_size),
                        np.concatenate(frame_tex),
                        np.concatenate(frame_pid)
                    )
                else:
                    # Empty frame
                    writer.write_frame(np.array([]), np.array([]), np.array([]), np.array([]), np.array([]))
                
                context.window_manager.progress_update(frame - start + 1)
                
        context.window_manager.progress_end()
        self.report({'INFO'}, f"极速导出完成！")

# ==============================================================================
# UI 相关 (保持不变)
# ==============================================================================
class NBL_TextureItem(PropertyGroup):
    material_name: StringProperty(name="材质名")
    texture_path: StringProperty(name="NBL 纹理路径", default="minecraft:textures/particle/white_ash.png")

class NBL_UL_TextureList(UIList):
    def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
        layout.label(text=item.material_name, icon='MATERIAL')
        layout.prop(item, "texture_path", text="")

class NEBULA_OT_RefreshMaterials(Operator):
    bl_idname = "nebula.refresh_materials"
    bl_label = "刷新"
    def execute(self, context):
        props = context.scene.nebula_props
        if not props.target_collection: return {'CANCELLED'}
        existing = {item.material_name for item in props.texture_list}
        for obj in props.target_collection.all_objects:
            if obj.type == 'MESH':
                for slot in obj.material_slots:
                    if slot.material and slot.material.name not in existing:
                        item = props.texture_list.add()
                        item.material_name = slot.material.name
                        existing.add(slot.material.name)
        return {'FINISHED'}

class NebulaProps(PropertyGroup):
    target_collection: PointerProperty(name="目标集合", type=bpy.types.Collection)
    filepath: StringProperty(name="路径", subtype='FILE_PATH', default="//output.nbl")
    scale: FloatProperty(name="缩放", default=10.0)
    sampling_density: FloatProperty(name="采样密度", default=10.0)
    particle_size: FloatProperty(name="大小", default=0.15)
    texture_list: CollectionProperty(type=NBL_TextureItem)
    texture_list_index: IntProperty()

class NEBULA_PT_Panel(Panel):
    bl_label = "NebulaFX Fast"
    bl_idname = "NEBULA_PT_Panel"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = 'NebulaFX'

    def draw(self, context):
        layout = self.layout
        props = context.scene.nebula_props
        if not HAS_ZSTD:
            layout.label(text="需安装 zstandard", icon='ERROR')
            return
        layout.prop(props, "target_collection")
        layout.prop(props, "filepath")
        layout.prop(props, "scale")
        layout.prop(props, "sampling_density")
        layout.prop(props, "particle_size")
        
        row = layout.row()
        row.template_list("NBL_UL_TextureList", "", props, "texture_list", props, "texture_list_index")
        row.operator("nebula.refresh_materials", icon='FILE_REFRESH', text="")
        
        layout.operator("nebula.show_stats", icon='INFO')
        layout.operator("nebula.export_nbl", icon='EXPORT')

classes = (NBL_TextureItem, NEBULA_OT_RefreshMaterials, NEBULA_OT_ShowStats, NEBULA_OT_ExportFast, NEBULA_PT_Panel, NebulaProps, NBL_UL_TextureList)

def register():
    for cls in classes: bpy.utils.register_class(cls)
    bpy.types.Scene.nebula_props = PointerProperty(type=NebulaProps)

def unregister():
    del bpy.types.Scene.nebula_props
    for cls in reversed(classes): bpy.utils.unregister_class(cls)

if __name__ == "__main__":
    register()
