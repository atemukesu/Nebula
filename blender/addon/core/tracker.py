import bpy
import numpy as np


class BaseTracker:
    def __init__(self, name):
        self.name = name
        self.valid = False
        self.is_world_space = False

    def prepare(
        self, eval_obj, settings, mat_map, image_cache, report_fn=None, mesh=None
    ):
        self.valid = True

    def get_data(self, eval_obj, mesh=None):
        """
        Returns:
            pos: np.array (N, 3) float32
            col: np.array (N, 4) uint8
            size: np.array (N,) float32 OR None (use default)
            tex: np.array (N,) uint8
            pid: np.array (N,) int32
        """
        return None, None, None, None, None


class MeshScatterTracker(BaseTracker):
    """P3: Mesh Surface Scatter"""

    def __init__(self, name):
        super().__init__(name)
        self.tri_indices = None
        self.bary_weights = None
        self.static_colors = None
        self.static_tex_ids = None
        self.loop_tri_verts = None

    def prepare(
        self, eval_obj, settings, mat_map, image_cache, report_fn=None, mesh=None
    ):
        # 如果外部传入了 mesh，就使用外部的，并不由本函数负责清理
        # 否则自己创建并负责清理
        own_mesh = False
        if mesh is None:
            mesh = eval_obj.to_mesh()
            own_mesh = True

        try:
            self._precompute(mesh, settings.sampling_density)
            if self.valid:
                self._bake_colors(mesh.materials, image_cache, report_fn)
                self._bake_tex_ids(mesh.materials, mat_map)
        finally:
            if own_mesh:
                eval_obj.to_mesh_clear()

    def _precompute(self, mesh, density, seed=0):
        mesh.calc_loop_triangles()
        loop_tris = mesh.loop_triangles
        verts = mesh.vertices

        tri_count = len(loop_tris)
        if tri_count == 0:
            print(
                f"[Nebula] {self.name}: "
                + bpy.app.translations.pgettext("No triangles found.")
            )
            self.valid = False
            return

        self.loop_tri_verts = np.zeros((tri_count, 3), dtype=np.int32)
        loop_tris.foreach_get("vertices", self.loop_tri_verts.ravel())

        # Calculate Areas
        mesh_verts = np.zeros((len(verts), 3), dtype=np.float32)
        verts.foreach_get("co", mesh_verts.ravel())

        v0 = mesh_verts[self.loop_tri_verts[:, 0]]
        v1 = mesh_verts[self.loop_tri_verts[:, 1]]
        v2 = mesh_verts[self.loop_tri_verts[:, 2]]
        cross = np.cross(v1 - v0, v2 - v0)
        areas = np.sqrt(np.sum(cross**2, axis=1)) * 0.5
        total_area = np.sum(areas)

        if total_area <= 0:
            print(
                f"[Nebula] {self.name}: "
                + bpy.app.translations.pgettext("Total area is <= 0")
                + f" ({total_area})."
            )
            self.valid = False
            return

        # Relaxed Particle Count Calculation
        raw_count = total_area * density * 10
        # Ensure at least 1 particle if raw_count is close to 1 or density is > 0 but area is small
        # But if raw_count is extremely small (e.g. 0.0001), maybe user intended 0?
        # Let's say: if density > 0 and area > 0 ...
        target_count = int(raw_count)

        if target_count < 1:
            if (
                raw_count > 0.001
            ):  # Threshold to force at least 1 particle for small meshes
                print(
                    f"[Nebula] {self.name}: "
                    + bpy.app.translations.pgettext(
                        "Count too low, forcing 1 particle."
                    )
                    + f" ({target_count}, Raw={raw_count:.4f})"
                )
                target_count = 1
            else:
                print(
                    f"[Nebula] {self.name}: "
                    + bpy.app.translations.pgettext("Count too low, valid=False.")
                    + f" ({target_count}, Raw={raw_count:.4f})"
                )
                self.valid = False
                return
        else:
            print(
                f"[Nebula] {self.name}: "
                + bpy.app.translations.pgettext("Generating particles")
                + f": {target_count} (Area={total_area:.4f}, Density={density})"
            )

        probs = areas / total_area
        rng = np.random.default_rng(seed)

        self.tri_indices = rng.choice(tri_count, size=target_count, p=probs)

        # Barycentric
        r1 = rng.random(target_count).astype(np.float32)
        r2 = rng.random(target_count).astype(np.float32)
        sqrt_r1 = np.sqrt(r1)
        w_u = 1.0 - sqrt_r1
        w_v = sqrt_r1 * (1.0 - r2)
        w_w = sqrt_r1 * r2
        self.bary_weights = np.stack((w_u, w_v, w_w), axis=1)

        # UVs
        self.static_uvs = np.zeros((target_count, 2), dtype=np.float32)
        if mesh.uv_layers.active:
            uv_layer = mesh.uv_layers.active.data
            all_uvs = np.zeros((len(uv_layer), 2), dtype=np.float32)
            uv_layer.foreach_get("uv", all_uvs.ravel())

            tri_loops = np.zeros((tri_count, 3), dtype=np.int32)
            loop_tris.foreach_get("loops", tri_loops.ravel())

            chosen_loops = tri_loops[self.tri_indices]
            uv0 = all_uvs[chosen_loops[:, 0]]
            uv1 = all_uvs[chosen_loops[:, 1]]
            uv2 = all_uvs[chosen_loops[:, 2]]

            self.static_uvs = (
                uv0 * w_u[:, None] + uv1 * w_v[:, None] + uv2 * w_w[:, None]
            )

        # Material Indices
        all_mat_indices = np.zeros(tri_count, dtype=np.int32)
        loop_tris.foreach_get("material_index", all_mat_indices)
        self.mat_indices = all_mat_indices[self.tri_indices]

        self.valid = True

    def _bake_colors(self, materials, image_cache, report_fn):
        count = len(self.tri_indices)
        self.static_colors = np.full((count, 4), 255, dtype=np.uint8)

        unique_mats = np.unique(self.mat_indices)
        for m_idx in unique_mats:
            if m_idx < 0 or m_idx >= len(materials):
                continue
            mat = materials[m_idx]
            if not mat:
                continue

            target_img = None
            found_method = "Default (White)"

            # Helper to find image node recursively
            def find_image_node(node, depth=0):
                if depth > 5:
                    return None
                if node.type == "TEX_IMAGE":
                    return node

                # Check inputs
                for input in node.inputs:
                    if input.is_linked:
                        link = input.links[0]
                        res = find_image_node(link.from_node, depth + 1)
                        if res:
                            return res
                return None

            if mat.use_nodes and mat.node_tree:
                # 1. Try common shader inputs
                # Principled BSDF -> Base Color
                # Emission -> Color
                # Diffuse BSDF -> Color
                # Background -> Color
                # Toon shaders often use "MainTex" or similar, but we can't guess names easily.
                # Just look for shader nodes.

                targets = []
                for node in mat.node_tree.nodes:
                    if node.type == "BSDF_PRINCIPLED":
                        targets.append(node.inputs.get("Base Color"))
                    elif node.type == "EMISSION":
                        targets.append(node.inputs.get("Color"))
                    elif node.type == "BSDF_DIFFUSE":
                        targets.append(node.inputs.get("Color"))
                    elif node.type in ["BSDF_TOON", "BSDF_HAIR_PRINCIPLED"]:
                        # Try first input usually color
                        if len(node.inputs) > 0:
                            targets.append(node.inputs[0])

                # Also check Output node inputs if no shader found or custom group
                output_node = next(
                    (n for n in mat.node_tree.nodes if n.type == "OUTPUT_MATERIAL"),
                    None,
                )
                if output_node:
                    targets.append(output_node.inputs.get("Surface"))

                for input_socket in targets:
                    if input_socket and input_socket.is_linked:
                        # Walk up
                        img_node = find_image_node(input_socket.links[0].from_node)
                        if img_node:
                            target_img = img_node.image
                            found_method = "Node Tree Scan"
                            break

                # 2. Fallback: Search for ANY Image Texture node with an image
                if not target_img:
                    for node in mat.node_tree.nodes:
                        if node.type == "TEX_IMAGE" and node.image:
                            # Prefer one that is selected/active
                            if node == mat.node_tree.nodes.active:
                                target_img = node.image
                                found_method = "Active Image Node"
                                break
                            # Or just take the first one found
                            if not target_img:
                                target_img = node.image
                                found_method = "First Image Node"

            if report_fn:
                img_name = target_img.name if target_img else "None"
                msg = (
                    bpy.app.translations.pgettext("Mat:")
                    + f" {mat.name} | "
                    + bpy.app.translations.pgettext(found_method)
                    + f" | {img_name}"
                )
                report_fn(msg)

            if target_img and target_img.name in image_cache:
                pixels, w, h = image_cache[target_img.name]
                mask = self.mat_indices == m_idx
                sub_uvs = self.static_uvs[mask]

                # Nearest Neighbor Sampling
                u = sub_uvs[:, 0] % 1.0
                v = sub_uvs[:, 1] % 1.0
                x = (u * w).astype(np.int32)
                y = (v * h).astype(np.int32)
                np.clip(x, 0, w - 1, out=x)
                np.clip(y, 0, h - 1, out=y)

                self.static_colors[mask] = (pixels[y, x] * 255).astype(np.uint8)

    def _bake_tex_ids(self, materials, mat_map):
        count = len(self.tri_indices)
        self.static_tex_ids = np.zeros(count, dtype=np.uint8)
        for i, mat in enumerate(materials):
            if mat and mat.name in mat_map:
                tid = mat_map[mat.name]
                self.static_tex_ids[self.mat_indices == i] = tid

    def get_data(self, eval_obj, mesh=None):
        if not self.valid or mesh is None:
            return None

        verts = np.zeros((len(mesh.vertices), 3), dtype=np.float32)
        mesh.vertices.foreach_get("co", verts.ravel())

        chosen_tri_verts = self.loop_tri_verts[self.tri_indices]
        p0 = verts[chosen_tri_verts[:, 0]]
        p1 = verts[chosen_tri_verts[:, 1]]
        p2 = verts[chosen_tri_verts[:, 2]]

        pos = (
            p0 * self.bary_weights[:, 0:1]
            + p1 * self.bary_weights[:, 1:2]
            + p2 * self.bary_weights[:, 2:3]
        )

        count = len(pos)
        pid = np.arange(count, dtype=np.int32)

        return pos, self.static_colors, None, self.static_tex_ids, pid


class NativeParticleTracker(BaseTracker):
    """P1: Particle System"""

    def __init__(self, name, psys_name):
        super().__init__(name)
        self.psys_name = psys_name
        self.is_world_space = True
        self.tex_id = 0

    def prepare(
        self, eval_obj, settings, mat_map, image_cache, report_fn=None, mesh=None
    ):
        self.valid = True
        psys = eval_obj.particle_systems.get(self.psys_name)
        if psys:
            slot_idx = psys.settings.material - 1
            if slot_idx >= 0 and slot_idx < len(eval_obj.material_slots):
                mat = eval_obj.material_slots[slot_idx].material
                if mat and mat.name in mat_map:
                    self.tex_id = mat_map[mat.name]

    def get_data(self, eval_obj, mesh=None):
        psys = eval_obj.particle_systems.get(self.psys_name)
        if not psys:
            return None

        particles = psys.particles
        count = len(particles)
        if count == 0:
            return None

        pos = np.zeros((count, 3), dtype=np.float32)
        particles.foreach_get("location", pos.ravel())

        size = np.zeros(count, dtype=np.float32)
        particles.foreach_get("size", size)

        col = np.full((count, 4), 255, dtype=np.uint8)
        tex = np.full(count, self.tex_id, dtype=np.uint8)
        pid = np.arange(count, dtype=np.int32)

        return pos, col, size, tex, pid


class PointCloudTracker(BaseTracker):
    """P2: Point Cloud"""

    def __init__(self, name):
        super().__init__(name)
        self.is_world_space = False

    def prepare(
        self, eval_obj, settings, mat_map, image_cache, report_fn=None, mesh=None
    ):
        self.valid = True

    def get_data(self, eval_obj, mesh=None):
        # 兼容 PointCloud 对象和 Geometry Nodes 输出的 Mesh (如果被认为是点)
        # 但通常 Point Cloud 分离为 PointCloud 类型
        data = eval_obj.data
        if not data or not hasattr(data, "points"):
            return None

        count = len(data.points)
        if count == 0:
            return None

        pos = np.zeros((count, 3), dtype=np.float32)
        data.points.foreach_get("co", pos.ravel())

        radius = np.zeros(count, dtype=np.float32)
        data.points.foreach_get("radius", radius)

        col = np.full((count, 4), 255, dtype=np.uint8)
        tex = np.zeros(count, dtype=np.uint8)

        # Attribute reading (Blender 3.0+)
        if "color" in data.attributes:
            att = data.attributes["color"]
            if att.data_type == "FLOAT_COLOR":
                c_data = np.zeros(count * 4, dtype=np.float32)
                att.data.foreach_get("color", c_data)
                col = (c_data.reshape(count, 4) * 255).astype(np.uint8)
        elif "Color" in data.attributes:
            att = data.attributes["Color"]
            if att.data_type == "FLOAT_COLOR":
                c_data = np.zeros(count * 4, dtype=np.float32)
                att.data.foreach_get("color", c_data)
                col = (c_data.reshape(count, 4) * 255).astype(np.uint8)

        if "material_index" in data.attributes:
            att = data.attributes["material_index"]
            if att.data_type == "INT":
                m_data = np.zeros(count, dtype=np.int32)
                att.data.foreach_get("value", m_data)
                # Ensure mapping later if needed, but here we just pass raw index
                # or we need to map to tex_id using logic similar to mesh.
                # Since mat_map is available in prepare(), we could map if we knew the material list.
                # For now assume material_index corresponds to texture ID directly or use 0
                tex = m_data.astype(np.uint8)

        pid = np.arange(count, dtype=np.int32)

        return pos, col, radius, tex, pid
