import bpy
import numpy as np
from bpy.types import Operator
from mathutils import Vector
from ..core.tracker import ParticleTracker


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

        # 1. 准备 Dummy Cache 用于检测贴图 (不实际读取像素，只为跑通逻辑)
        dummy_cache = {}
        for img in bpy.data.images:
            # 使用 1x1 的像素数据模拟
            dummy_cache[img.name] = (np.zeros((1, 1, 4), dtype=np.float32), 1, 1)

        total_particles = 0
        min_pos = np.array([float("inf")] * 3)
        max_pos = np.array([float("-inf")] * 3)

        texture_reports = []
        reported_mats = set()

        def collect_report(msg):
            # 去重：同名材质只报一次
            if msg not in reported_mats:
                texture_reports.append(msg)
                reported_mats.add(msg)

        # 临时计算第一帧
        for obj in target_col.all_objects:
            if obj.type == "MESH" and obj.visible_get():
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
                    mc_bbox[:, 1] = world_bbox[:, 2] * props.scale  # YZ 翻转
                    mc_bbox[:, 2] = world_bbox[:, 1] * props.scale

                    min_pos = np.minimum(min_pos, mc_bbox.min(axis=0))
                    max_pos = np.maximum(max_pos, mc_bbox.max(axis=0))

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
            for msg in texture_reports:
                # 简单的颜色区分
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
