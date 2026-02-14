import bpy
import numpy as np
import traceback
from bpy.types import Operator
from ..core.tracker import MeshScatterTracker, NativeParticleTracker, PointCloudTracker
from ..core.writer import NBLWriter
from ..utils.dependencies import HAS_ZSTD


class NEBULA_OT_ExportFast(Operator):
    bl_idname = "nebula.export_nbl"
    bl_label = "Export NBL"

    @classmethod
    def description(cls, context, properties):
        return bpy.app.translations.pgettext("Export NBL")

    _timer = None
    _writer = None

    def invoke(self, context, event):
        if not HAS_ZSTD:
            self.report({"ERROR"}, bpy.app.translations.pgettext("zstandard required"))
            return {"CANCELLED"}

        # 1. 准备数据
        self.props = context.scene.nebula_props
        self.depsgraph = context.evaluated_depsgraph_get()
        self.trackers_list = []  # List of (obj_name, tracker)

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
            self.report(
                {"ERROR"},
                bpy.app.translations.pgettext("Target collection not selected"),
            )
            return {"CANCELLED"}

        # 2. 初始化 Tracker (在起始帧进行)
        scene.frame_set(self.start_frame)
        self.depsgraph.update()  # Ensure T-pose/Start pos

        def report_fn(msg):
            self.report({"INFO"}, msg)

        for obj in target_col.all_objects:
            if not obj.visible_get():
                continue

            eval_obj = obj.evaluated_get(self.depsgraph)

            # P3: Mesh Scatter
            if obj.type == "MESH" and self.props.use_mesh_scatter:
                t = MeshScatterTracker(f"{obj.name}_mesh")
                t.prepare(
                    eval_obj,
                    self.props,
                    self.mat_to_tex_id,
                    self.image_cache,
                    report_fn,
                )
                if t.valid:
                    self.trackers_list.append((obj.name, t))

            # P1: Native Particles
            if self.props.use_particle_system and len(obj.particle_systems) > 0:
                for psys in obj.particle_systems:
                    # check settings if needed
                    t = NativeParticleTracker(f"{obj.name}_{psys.name}", psys.name)
                    t.prepare(
                        eval_obj, self.props, self.mat_to_tex_id, self.image_cache
                    )
                    self.trackers_list.append((obj.name, t))

            # P2: Point Cloud
            # Blender < 3.0: No pointcloud object type usually exposed easily to python in standard build without geometry nodes usage becoming common.
            # But Blender 3.4+ has 'POINTCLOUD' type.
            if obj.type == "POINTCLOUD" and self.props.use_point_cloud:
                t = PointCloudTracker(f"{obj.name}_pcl")
                t.prepare(eval_obj, self.props, self.mat_to_tex_id, self.image_cache)
                self.trackers_list.append((obj.name, t))

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
        self.props.export_message = bpy.app.translations.pgettext(
            "Preparing for export..."
        )

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

        for obj_name, tracker in self.trackers_list:
            obj = bpy.data.objects.get(obj_name)
            if not obj:
                continue

            eval_obj = obj.evaluated_get(self.depsgraph)

            # Only MeshScatter needs explicit to_mesh here
            mesh = None
            if isinstance(tracker, MeshScatterTracker):
                mesh = eval_obj.to_mesh()

            try:
                pos, col, size, tex, pid = tracker.get_data(eval_obj, mesh)
                if pos is not None:
                    count = len(pos)
                    current_frame_particles += count

                    # Transform to World & MC
                    world_pos = pos
                    if not tracker.is_world_space:
                        mat_world = eval_obj.matrix_world
                        # Apply matrix: (N,3) @ (3,3) + (3,)
                        rot = np.array(mat_world.to_3x3()).T
                        loc = np.array(mat_world.translation)
                        world_pos = pos @ rot + loc

                    mc_pos = np.empty_like(world_pos)
                    mc_pos[:, 0] = world_pos[:, 0] * self.props.scale
                    mc_pos[:, 1] = world_pos[:, 2] * self.props.scale
                    mc_pos[:, 2] = world_pos[:, 1] * self.props.scale

                    frame_pos.append(mc_pos)

                    # Colors
                    if col is None:
                        col = np.full((count, 4), 255, dtype=np.uint8)
                    frame_col.append(col)

                    # Sizes
                    if size is None:
                        # Use default user setting
                        size_arr = np.full(
                            count, int(self.props.particle_size * 100), dtype=np.uint16
                        )
                    else:
                        # Use raw size * 100 logic (Standard NBL unit assumption)
                        size_arr = (size * 100).astype(np.uint16)
                    frame_size.append(size_arr)

                    frame_tex.append(tex)
                    frame_pid.append(pid)

            finally:
                if mesh:
                    eval_obj.to_mesh_clear()

        if frame_pos:
            # Seq ID currently all 0 for now (future feature)
            all_pos = np.vstack(frame_pos)
            count = len(all_pos)
            all_seq = np.zeros(count, dtype=np.uint8)

            self._writer.write_frame(
                all_pos,
                np.vstack(frame_col),
                np.concatenate(frame_size),
                np.concatenate(frame_tex),
                all_seq,
                np.concatenate(frame_pid),
            )
        else:
            self._writer.write_frame(
                np.array([]),
                np.array([]),
                np.array([]),
                np.array([]),
                np.array([]),
                np.array([]),
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
            + bpy.app.translations.pgettext(" Particles")
        )

    def cancel(self, context):
        if self._writer:
            self._writer.__exit__(None, None, None)
        context.window_manager.event_timer_remove(self._timer)
        context.window_manager.progress_end()
        self.props.is_exporting = False
        self.props.export_message = bpy.app.translations.pgettext("Cancelled")
        self.report({"WARNING"}, bpy.app.translations.pgettext("Export Cancelled"))
        print("\n导出已取消")

    def finish(self, context):
        if self._writer:
            self._writer.__exit__(None, None, None)
        context.window_manager.event_timer_remove(self._timer)
        context.window_manager.progress_end()
        self.props.is_exporting = False
        self.props.export_progress = 100.0
        self.props.export_message = bpy.app.translations.pgettext("Finished!")
        self.report({"INFO"}, bpy.app.translations.pgettext("Fast Export Finished!"))
        print("\n导出完成！")
