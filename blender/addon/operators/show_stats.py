import bpy
import numpy as np
from bpy.types import Operator
from mathutils import Vector
from ..core.tracker import MeshScatterTracker, NativeParticleTracker, PointCloudTracker


class NEBULA_OT_ShowStats(Operator):
    bl_idname = "nebula.show_stats"
    bl_label = "View Estimated Statistics"

    def execute(self, context):
        props = context.scene.nebula_props
        depsgraph = context.evaluated_depsgraph_get()
        target_col = props.target_collection

        if not target_col:
            self.report(
                {"ERROR"},
                bpy.app.translations.pgettext("Target collection not selected"),
            )
            return {"CANCELLED"}

        # 1. Dummy Cache
        dummy_cache = {}
        for img in bpy.data.images:
            dummy_cache[img.name] = (np.zeros((1, 1, 4), dtype=np.float32), 1, 1)

        total_particles = 0
        min_pos = np.array([float("inf")] * 3)
        max_pos = np.array([float("-inf")] * 3)

        texture_reports = []
        reported_mats = set()

        def collect_report(msg):
            if msg not in reported_mats:
                texture_reports.append(msg)
                reported_mats.add(msg)

        # Prepare trackers based on current selection
        trackers = []

        mat_map = {item.material_name: i for i, item in enumerate(props.texture_list)}

        for obj in target_col.all_objects:
            if not obj.visible_get():
                continue
            eval_obj = obj.evaluated_get(depsgraph)

            # P3 Mesh Scatter
            if obj.type == "MESH" and props.use_mesh_scatter:
                t = MeshScatterTracker("mesh")
                trackers.append((eval_obj, t, True))  # True = needs Mesh

            # P1 Particles
            if props.use_particle_system and len(obj.particle_systems) > 0:
                for psys in obj.particle_systems:
                    t = NativeParticleTracker("psys", psys.name)
                    trackers.append((eval_obj, t, False))

            # P2 Point Cloud
            if obj.type == "POINTCLOUD" and props.use_point_cloud:
                t = PointCloudTracker("pcl")
                trackers.append((eval_obj, t, False))

        # Run Estimation
        for eval_obj, tracker, needs_mesh in trackers:
            mesh = eval_obj.to_mesh() if needs_mesh else None
            try:
                # Prepare (Calculate Distribution)
                tracker.prepare(
                    eval_obj, props, mat_map, dummy_cache, collect_report, mesh=mesh
                )

                if tracker.valid:
                    # Get Data for one frame (Current Frame)
                    pos, _, _, _, _ = tracker.get_data(eval_obj, mesh)

                    if pos is not None:
                        count = len(pos)
                        total_particles += count

                        # Calculate BBox for this batch
                        if count > 0:
                            # Transform
                            world_pos = pos
                            if not tracker.is_world_space:
                                mat_world = eval_obj.matrix_world
                                rot = np.array(mat_world.to_3x3()).T
                                loc = np.array(mat_world.translation)
                                world_pos = pos @ rot + loc

                            mc_pos = np.empty_like(world_pos)
                            mc_pos[:, 0] = world_pos[:, 0] * props.scale
                            mc_pos[:, 1] = world_pos[:, 2] * props.scale
                            mc_pos[:, 2] = world_pos[:, 1] * props.scale

                            min_pos = np.minimum(min_pos, mc_pos.min(axis=0))
                            max_pos = np.maximum(max_pos, mc_pos.max(axis=0))

            except Exception as e:
                print(f"Stats Error: {e}")
            finally:
                if mesh:
                    eval_obj.to_mesh_clear()

        if total_particles == 0:
            self.report(
                {"WARNING"},
                bpy.app.translations.pgettext(
                    "No particles generated, please check density or model"
                ),
            )
            return {"CANCELLED"}

        dims = max_pos - min_pos
        # Handle case where min_pos is still inf
        if np.isinf(dims).any():
            dims = np.zeros(3)

        def show_msg(self, context):
            layout = self.layout
            layout.label(
                text=bpy.app.translations.pgettext("Estimated Total Particles: ")
                + f"{total_particles:,}"
            )
            layout.label(
                text=bpy.app.translations.pgettext("MC Dimensions: ")
                + f"{dims[0]:.1f} x {dims[2]:.1f} x {dims[1]:.1f}"
            )
            layout.separator()
            layout.label(text=bpy.app.translations.pgettext("Material Check Report:"))
            if not texture_reports:
                layout.label(text="No texture issues found", icon="CHECKMARK")
            for msg in texture_reports:
                if "Default" in msg:
                    layout.label(text=msg, icon="ERROR")
                else:
                    layout.label(text=msg, icon="CHECKMARK")

        context.window_manager.popup_menu(
            show_msg,
            title=bpy.app.translations.pgettext("NebulaFX Stats & Material Check"),
            icon="INFO",
        )

        return {"FINISHED"}
