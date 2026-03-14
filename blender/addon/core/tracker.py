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
    """P3: Mesh Surface Scatter — dynamic density adjustment for stretching"""

    def __init__(self, name):
        super().__init__(name)
        self.loop_tri_verts = None       # (tri_count, 3) int32
        self.density_per_area = 0.0      # particles per unit area
        self.next_pid = 0
        self.rng = None

        # Flat arrays for all current particles
        self.all_tri_idx = None          # (N,) int32 — which triangle
        self.all_bary = None             # (N, 3) float32
        self.all_colors = None           # (N, 4) uint8
        self.all_tex_ids = None          # (N,) uint8
        self.all_pids = None             # (N,) int32

        # Per-triangle topology (from initial mesh, constant across frames)
        self.tri_mat_indices_all = None  # (tri_count,) int32
        self.tri_loops = None            # (tri_count, 3) int32
        self.all_uvs = None              # (total_loops, 2) float32
        self.has_uvs = False

        # Per-material colour / tex sampling tables
        self.per_mat_image_data = {}     # mat_idx -> (pixels, w, h) | None
        self.per_mat_fallback_color = {} # mat_idx -> (4,) uint8
        self.per_mat_tex_id = {}         # mat_idx -> uint8

    # ------------------------------------------------------------------
    # prepare  (called once at start-frame)
    # ------------------------------------------------------------------
    def prepare(
        self, eval_obj, settings, mat_map, image_cache, report_fn=None, mesh=None
    ):
        own_mesh = False
        if mesh is None:
            mesh = eval_obj.to_mesh()
            own_mesh = True

        try:
            self._precompute(mesh, settings.sampling_density)
            if self.valid:
                self._prepare_color_data(mesh.materials, image_cache, report_fn)
                self._prepare_tex_id_data(mesh.materials, mat_map)
                # Bake initial colours & tex-ids
                self.all_colors = self._sample_colors(self.all_tri_idx, self.all_bary)
                self.all_tex_ids = self._sample_tex_ids(self.all_tri_idx)
        finally:
            if own_mesh:
                eval_obj.to_mesh_clear()

    # ------------------------------------------------------------------
    # _precompute
    # ------------------------------------------------------------------
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

        # Loop indices (for UV lookup of new particles)
        self.tri_loops = np.zeros((tri_count, 3), dtype=np.int32)
        loop_tris.foreach_get("loops", self.tri_loops.ravel())

        # UV data
        if mesh.uv_layers.active:
            uv_layer = mesh.uv_layers.active.data
            self.all_uvs = np.zeros((len(uv_layer), 2), dtype=np.float32)
            uv_layer.foreach_get("uv", self.all_uvs.ravel())
            self.has_uvs = True

        # Material index per triangle (all tris, not just chosen ones)
        self.tri_mat_indices_all = np.zeros(tri_count, dtype=np.int32)
        loop_tris.foreach_get("material_index", self.tri_mat_indices_all)

        # ---- Areas ----
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

        raw_count = total_area * density * 10
        target_count = int(raw_count)

        if target_count < 1:
            if raw_count > 0.001:
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

        # ---- density & RNG ----
        self.density_per_area = target_count / total_area
        self.rng = np.random.default_rng(seed)

        # Distribute particles proportionally across triangles
        per_tri_float = areas * self.density_per_area
        per_tri_count = np.floor(per_tri_float).astype(np.int32)

        deficit = target_count - per_tri_count.sum()
        if deficit > 0:
            remainder = per_tri_float - per_tri_count
            r_sum = remainder.sum()
            probs = remainder / r_sum if r_sum > 0 else np.ones(tri_count) / tri_count
            bonus = self.rng.choice(tri_count, size=deficit, replace=True, p=probs)
            for ti in bonus:
                per_tri_count[ti] += 1

        # Build flat arrays
        total_particles = int(per_tri_count.sum())
        self.all_tri_idx = np.repeat(
            np.arange(tri_count, dtype=np.int32), per_tri_count
        )

        r1 = self.rng.random(total_particles).astype(np.float32)
        r2 = self.rng.random(total_particles).astype(np.float32)
        sqrt_r1 = np.sqrt(r1)
        self.all_bary = np.stack(
            (1.0 - sqrt_r1, sqrt_r1 * (1.0 - r2), sqrt_r1 * r2), axis=1
        )

        self.all_pids = np.arange(total_particles, dtype=np.int32)
        self.next_pid = total_particles
        self.valid = True

    # ------------------------------------------------------------------
    # Material / colour helpers  (store lookup tables, don't bake yet)
    # ------------------------------------------------------------------
    def _prepare_color_data(self, materials, image_cache, report_fn):
        """Build per-material colour sampling lookup tables."""

        def find_image_node(node, depth=0):
            if depth > 5:
                return None
            if node.type == "TEX_IMAGE":
                return node
            for inp in node.inputs:
                if inp.is_linked:
                    res = find_image_node(inp.links[0].from_node, depth + 1)
                    if res:
                        return res
            return None

        unique_mats = np.unique(self.tri_mat_indices_all)
        for m_idx in unique_mats:
            if m_idx < 0 or m_idx >= len(materials):
                self.per_mat_fallback_color[m_idx] = np.array(
                    [255, 255, 255, 255], dtype=np.uint8
                )
                continue
            mat = materials[m_idx]
            if not mat:
                self.per_mat_fallback_color[m_idx] = np.array(
                    [255, 255, 255, 255], dtype=np.uint8
                )
                continue

            target_img = None
            found_method = "Default (White)"
            fallback_color = [1.0, 1.0, 1.0, 1.0]

            if mat.use_nodes and mat.node_tree:
                targets = []
                for node in mat.node_tree.nodes:
                    if node.type == "BSDF_PRINCIPLED":
                        targets.append(node.inputs.get("Base Color"))
                    elif node.type == "EMISSION":
                        targets.append(node.inputs.get("Color"))
                    elif node.type == "BSDF_DIFFUSE":
                        targets.append(node.inputs.get("Color"))
                    elif node.type in ["BSDF_TOON", "BSDF_HAIR_PRINCIPLED"]:
                        if len(node.inputs) > 0:
                            targets.append(node.inputs[0])

                output_node = next(
                    (n for n in mat.node_tree.nodes if n.type == "OUTPUT_MATERIAL"),
                    None,
                )
                if output_node:
                    targets.append(output_node.inputs.get("Surface"))

                found_fallback = False
                for input_socket in targets:
                    if not input_socket:
                        continue
                    if input_socket.is_linked:
                        img_node = find_image_node(input_socket.links[0].from_node)
                        if img_node:
                            target_img = img_node.image
                            found_method = "Node Tree Scan"
                            break
                    elif not found_fallback:
                        dv = getattr(input_socket, "default_value", None)
                        if dv is not None and hasattr(dv, "__len__") and len(dv) >= 3:
                            fallback_color = [
                                float(dv[0]),
                                float(dv[1]),
                                float(dv[2]),
                                1.0,
                            ]
                            if len(dv) >= 4:
                                fallback_color[3] = float(dv[3])
                            found_fallback = True
                            found_method = "Base Color (Value)"

                if not target_img:
                    for node in mat.node_tree.nodes:
                        if node.type == "TEX_IMAGE" and node.image:
                            if node == mat.node_tree.nodes.active:
                                target_img = node.image
                                found_method = "Active Image Node"
                                break
                            if not target_img:
                                target_img = node.image
                                found_method = "First Image Node"
            else:
                fallback_color = list(mat.diffuse_color)
                found_method = "Diffuse Color"

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
                self.per_mat_image_data[m_idx] = image_cache[target_img.name]
            else:
                self.per_mat_image_data[m_idx] = None

            self.per_mat_fallback_color[m_idx] = (
                np.array(fallback_color[:4]) * 255
            ).astype(np.uint8)

    def _prepare_tex_id_data(self, materials, mat_map):
        for i, mat in enumerate(materials):
            if mat and mat.name in mat_map:
                self.per_mat_tex_id[i] = mat_map[mat.name]
            else:
                self.per_mat_tex_id[i] = 0

    # ------------------------------------------------------------------
    # Sampling helpers  (used both at init and per-frame for new particles)
    # ------------------------------------------------------------------
    def _sample_colors(self, tri_indices, bary_weights):
        count = len(tri_indices)
        if count == 0:
            return np.empty((0, 4), dtype=np.uint8)

        colors = np.full((count, 4), 255, dtype=np.uint8)
        mat_indices = self.tri_mat_indices_all[tri_indices]

        for m_idx in np.unique(mat_indices):
            mask = mat_indices == m_idx
            img_data = self.per_mat_image_data.get(m_idx)

            if img_data is not None:
                pixels, w, h = img_data
                if self.has_uvs:
                    sub_tri = tri_indices[mask]
                    sub_bary = bary_weights[mask]
                    chosen_loops = self.tri_loops[sub_tri]
                    uv0 = self.all_uvs[chosen_loops[:, 0]]
                    uv1 = self.all_uvs[chosen_loops[:, 1]]
                    uv2 = self.all_uvs[chosen_loops[:, 2]]
                    uvs = (
                        uv0 * sub_bary[:, 0:1]
                        + uv1 * sub_bary[:, 1:2]
                        + uv2 * sub_bary[:, 2:3]
                    )
                else:
                    uvs = np.zeros((mask.sum(), 2), dtype=np.float32)

                u = uvs[:, 0] % 1.0
                v = uvs[:, 1] % 1.0
                x = (u * w).astype(np.int32)
                y = (v * h).astype(np.int32)
                np.clip(x, 0, w - 1, out=x)
                np.clip(y, 0, h - 1, out=y)
                colors[mask] = (pixels[y, x] * 255).astype(np.uint8)
            elif m_idx in self.per_mat_fallback_color:
                colors[mask] = self.per_mat_fallback_color[m_idx]

        return colors

    def _sample_tex_ids(self, tri_indices):
        count = len(tri_indices)
        if count == 0:
            return np.empty(0, dtype=np.uint8)

        tex_ids = np.zeros(count, dtype=np.uint8)
        mat_indices = self.tri_mat_indices_all[tri_indices]
        for m_idx in np.unique(mat_indices):
            if m_idx in self.per_mat_tex_id:
                tex_ids[mat_indices == m_idx] = self.per_mat_tex_id[m_idx]
        return tex_ids

    # ------------------------------------------------------------------
    # _adjust_particles  (called every frame in get_data)
    # ------------------------------------------------------------------
    def _adjust_particles(self, current_areas):
        """Add / remove particles so that density stays uniform.

        Uses a hysteresis dead-zone to avoid flickering when areas
        oscillate near a rounding boundary (e.g. 1.00 → 1.01 → 0.99).
        A triangle must differ by ≥20 % (and at least 2 particles)
        before any spawn / despawn is triggered.
        """
        tri_count = len(current_areas)

        raw_desired = current_areas * self.density_per_area
        desired = np.round(raw_desired).astype(np.int32)
        desired = np.maximum(desired, 0)

        current_counts = np.bincount(
            self.all_tri_idx, minlength=tri_count
        ).astype(np.int32)

        diff = desired - current_counts

        # ---- Early exit: nothing changed ----
        if np.all(diff == 0):
            return

        # ---- Hysteresis dead-zone ----
        # Only act when change exceeds 20% of current count AND ≥2 particles
        threshold = np.maximum(current_counts * 0.2, 2).astype(np.int32)
        diff = np.where(np.abs(diff) >= threshold, diff, 0)

        if np.all(diff == 0):
            return

        # ---- Spawn: triangles that grew ----
        add_mask = diff > 0
        if np.any(add_mask):
            add_tris = np.where(add_mask)[0]
            add_counts = diff[add_tris]
            total_new = int(add_counts.sum())

            new_tri = np.repeat(add_tris, add_counts).astype(np.int32)

            r1 = self.rng.random(total_new).astype(np.float32)
            r2 = self.rng.random(total_new).astype(np.float32)
            sqrt_r1 = np.sqrt(r1)
            new_bary = np.stack(
                (1.0 - sqrt_r1, sqrt_r1 * (1.0 - r2), sqrt_r1 * r2), axis=1
            )

            new_colors = self._sample_colors(new_tri, new_bary)
            new_tex = self._sample_tex_ids(new_tri)
            new_pids = np.arange(
                self.next_pid, self.next_pid + total_new, dtype=np.int32
            )
            self.next_pid += total_new

            self.all_tri_idx = np.concatenate([self.all_tri_idx, new_tri])
            self.all_bary = np.concatenate([self.all_bary, new_bary])
            self.all_colors = np.concatenate([self.all_colors, new_colors])
            self.all_tex_ids = np.concatenate([self.all_tex_ids, new_tex])
            self.all_pids = np.concatenate([self.all_pids, new_pids])

        # ---- Despawn: triangles that shrank ----
        remove_mask = diff < 0
        if np.any(remove_mask):
            n = len(self.all_tri_idx)
            if n == 0:
                return

            # Vectorised rank-based removal:
            # sort by tri, keep the first desired[tri] particles per group
            sort_idx = np.argsort(self.all_tri_idx, kind="stable")
            sorted_tri = self.all_tri_idx[sort_idx]

            # Rank within each tri-group
            group_change = np.empty(n, dtype=bool)
            group_change[0] = True
            group_change[1:] = sorted_tri[1:] != sorted_tri[:-1]
            group_starts = np.where(group_change)[0]
            group_ids = np.cumsum(group_change) - 1
            ranks = np.arange(n, dtype=np.int32) - group_starts[group_ids]

            keep_sorted = ranks < desired[sorted_tri]

            keep_full = np.zeros(n, dtype=bool)
            keep_full[sort_idx] = keep_sorted

            self.all_tri_idx = self.all_tri_idx[keep_full]
            self.all_bary = self.all_bary[keep_full]
            self.all_colors = self.all_colors[keep_full]
            self.all_tex_ids = self.all_tex_ids[keep_full]
            self.all_pids = self.all_pids[keep_full]

    # ------------------------------------------------------------------
    # get_data  (called every frame)
    # ------------------------------------------------------------------
    def get_data(self, eval_obj, mesh=None):
        if not self.valid or mesh is None:
            return None, None, None, None, None

        verts = np.zeros((len(mesh.vertices), 3), dtype=np.float32)
        mesh.vertices.foreach_get("co", verts.ravel())

        # Current per-triangle areas
        v0 = verts[self.loop_tri_verts[:, 0]]
        v1 = verts[self.loop_tri_verts[:, 1]]
        v2 = verts[self.loop_tri_verts[:, 2]]
        cross = np.cross(v1 - v0, v2 - v0)
        current_areas = np.sqrt(np.sum(cross**2, axis=1)) * 0.5

        # Adjust particle distribution
        self._adjust_particles(current_areas)

        if len(self.all_tri_idx) == 0:
            return None, None, None, None, None

        # Barycentric interpolation → world positions
        chosen = self.loop_tri_verts[self.all_tri_idx]
        p0 = verts[chosen[:, 0]]
        p1 = verts[chosen[:, 1]]
        p2 = verts[chosen[:, 2]]

        pos = (
            p0 * self.all_bary[:, 0:1]
            + p1 * self.all_bary[:, 1:2]
            + p2 * self.all_bary[:, 2:3]
        )

        return pos, self.all_colors, None, self.all_tex_ids, self.all_pids


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
