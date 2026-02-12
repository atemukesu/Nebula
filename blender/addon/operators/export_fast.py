import bpy
import numpy as np
import traceback
from bpy.types import Operator
from ..core.tracker import ParticleTracker
from ..core.writer import NBLWriter
from ..utils.dependencies import HAS_ZSTD


class NEBULA_OT_ExportFast(Operator):
    bl_idname = "nebula.export_nbl"
    bl_label = "导出 NBL"

    _timer = None
    _writer = None

    def invoke(self, context, event):
        if not HAS_ZSTD:
            self.report({"ERROR"}, "需安装 zstandard")
            return {"CANCELLED"}

        # 1. 准备数据
        self.props = context.scene.nebula_props
        self.depsgraph = context.evaluated_depsgraph_get()
        self.trackers = {}

        # 材质映射
        self.mat_to_tex_id = {}
        self.tex_paths = []
        for i, item in enumerate(self.props.texture_list):
            self.tex_paths.append(item.texture_path)
            self.mat_to_tex_id[item.material_name] = i

        # Image Cache
        self.image_cache = {}
        for img in bpy.data.images:
            if img.size[0] > 0:
                arr = np.array(img.pixels[:], dtype=np.float32)
                arr = arr.reshape(img.size[1], img.size[0], img.channels)
                if img.channels == 3:
                    arr = np.dstack((arr, np.ones((img.size[1], img.size[0], 1))))
                self.image_cache[img.name] = (arr, img.size[0], img.size[1])

        # Scene Setup
        scene = context.scene
        self.start_frame = scene.frame_start
        self.end_frame = scene.frame_end
        self.current_frame = self.start_frame
        target_col = self.props.target_collection

        if not target_col:
            self.report({"ERROR"}, "未选择目标集合")
            return {"CANCELLED"}

        # 2. 初始化 Tracker (在起始帧进行)
        scene.frame_set(self.start_frame)
        self.depsgraph.update()  # Ensure T-pose/Start pos

        for obj in target_col.all_objects:
            if obj.type == "MESH" and obj.visible_get():
                eval_obj = obj.evaluated_get(self.depsgraph)
                mesh = eval_obj.to_mesh()

                tracker = ParticleTracker()
                success = tracker.precompute_distribution(
                    mesh, self.props.sampling_density
                )
                if success:
                    tracker.bake_colors(
                        mesh.materials,
                        self.image_cache,
                        lambda msg: self.report({"INFO"}, msg),
                    )
                    # Tex ID
                    tracker.static_tex_ids = np.zeros(
                        len(tracker.tri_indices), dtype=np.uint8
                    )
                    for i, mat in enumerate(obj.data.materials):
                        if mat and mat.name in self.mat_to_tex_id:
                            target_id = self.mat_to_tex_id[mat.name]
                            tracker.static_tex_ids[tracker.mat_indices == i] = target_id

                    self.trackers[obj.name] = tracker

                eval_obj.to_mesh_clear()

        # 3. Init Writer
        self._writer = NBLWriter(
            bpy.path.abspath(self.props.filepath),
            scene.render.fps,
            self.end_frame - self.start_frame + 1,
            self.tex_paths,
            self.props.scale,
        )
        self._writer.__enter__()  # Open file

        # 4. Start Modal
        context.window_manager.modal_handler_add(self)
        self._timer = context.window_manager.event_timer_add(
            0.001, window=context.window
        )
        context.window_manager.progress_begin(0, self.end_frame - self.start_frame)

        # Init Progress Props
        self.props.is_exporting = True
        self.props.export_progress = 0.0
        self.props.export_message = "准备导出..."

        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        if event.type == "ESC":
            self.cancel(context)
            return {"CANCELLED"}

        if event.type == "TIMER":
            if self.current_frame > self.end_frame:
                self.finish(context)
                return {"FINISHED"}

            try:
                self.process_frame(context)
                self.current_frame += 1
            except Exception as e:
                self.report({"ERROR"}, f"Error: {e}")
                traceback.print_exc()
                self.cancel(context)
                return {"CANCELLED"}

        return {"RUNNING_MODAL"}

    def process_frame(self, context):
        scene = context.scene
        scene.frame_set(self.current_frame)
        self.depsgraph.update()

        frame_pos = []
        frame_col = []
        frame_size = []
        frame_tex = []
        frame_pid = []
        current_frame_particles = 0

        for obj_name, tracker in self.trackers.items():
            obj = bpy.data.objects.get(obj_name)
            if not obj:
                continue

            eval_obj = obj.evaluated_get(self.depsgraph)
            mesh = eval_obj.to_mesh()

            local_pos = tracker.compute_positions(mesh)
            if local_pos is not None:
                count = len(local_pos)
                current_frame_particles += count

                mat_world = eval_obj.matrix_world
                rot = np.array(mat_world.to_3x3()).T
                loc = np.array(mat_world.translation)
                world_pos = local_pos @ rot + loc

                mc_pos = np.empty_like(world_pos)
                mc_pos[:, 0] = world_pos[:, 0] * self.props.scale
                mc_pos[:, 1] = world_pos[:, 2] * self.props.scale
                mc_pos[:, 2] = world_pos[:, 1] * self.props.scale

                frame_pos.append(mc_pos)
                frame_col.append(tracker.static_colors)
                frame_size.append(
                    np.full(count, int(self.props.particle_size * 100), dtype=np.uint16)
                )
                frame_tex.append(tracker.static_tex_ids)
                frame_pid.append(np.arange(count, dtype=np.int32))

            eval_obj.to_mesh_clear()

        if frame_pos:
            self._writer.write_frame(
                np.vstack(frame_pos),
                np.vstack(frame_col),
                np.concatenate(frame_size),
                np.concatenate(frame_tex),
                np.concatenate(frame_pid),
            )
        else:
            self._writer.write_frame(
                np.array([]), np.array([]), np.array([]), np.array([]), np.array([])
            )

        # Progress update
        steps = self.current_frame - self.start_frame
        total = self.end_frame - self.start_frame
        context.window_manager.progress_update(steps)

        progress = steps / total if total > 0 else 1.0
        print(
            f"Export Progress: [{self.current_frame}/{self.end_frame}] ({progress * 100:.1f}%) | Particles: {current_frame_particles:<7}",
            end="\r",
        )

        # Update UI Props
        self.props.export_progress = progress * 100.0
        self.props.export_message = (
            f"[{self.current_frame}/{self.end_frame}] {current_frame_particles}"
            + bpy.app.translations.pgettext(" 粒子")
        )

        # Force UI Redraw to prevent Ghosting/Driver Timeout
        bpy.ops.wm.redraw_timer(type="DRAW_WIN_SWAP", iterations=1)

    def cancel(self, context):
        if self._writer:
            self._writer.__exit__(None, None, None)
        context.window_manager.event_timer_remove(self._timer)
        context.window_manager.progress_end()
        self.props.is_exporting = False
        self.props.export_message = "已取消"
        self.report({"WARNING"}, "导出已取消")
        print("\n导出已取消")

    def finish(self, context):
        if self._writer:
            self._writer.__exit__(None, None, None)
        context.window_manager.event_timer_remove(self._timer)
        context.window_manager.progress_end()
        self.props.is_exporting = False
        self.props.export_progress = 100.0
        self.props.export_message = "完成!"
        self.report({"INFO"}, "极速导出完成！")
        print("\n导出完成！")
