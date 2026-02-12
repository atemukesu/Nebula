import bpy
import numpy as np

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
