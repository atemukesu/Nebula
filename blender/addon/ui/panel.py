import bpy
from bpy.types import Panel
from ..utils.dependencies import HAS_ZSTD


class NEBULA_PT_Panel(Panel):
    bl_label = "NebulaFX NBL Exporter"
    bl_idname = "NEBULA_PT_Panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "NebulaFX"

    def draw(self, context):
        layout = self.layout
        props = context.scene.nebula_props
        if not HAS_ZSTD:
            layout.label(
                text=bpy.app.translations.pgettext("zstandard required"), icon="ERROR"
            )
            return
        layout.prop(props, "target_collection")
        layout.prop(props, "filepath")
        layout.prop(props, "scale")
        layout.prop(props, "particle_size")

        layout.separator()
        layout.label(text=bpy.app.translations.pgettext("Data Source"))
        col = layout.column(align=True)
        col.prop(props, "use_mesh_scatter")
        if props.use_mesh_scatter:
            col.prop(props, "sampling_density")
        col.prop(props, "use_particle_system")
        col.prop(props, "use_point_cloud")
        layout.separator()

        row = layout.row()
        row.template_list(
            "NBL_UL_TextureList", "", props, "texture_list", props, "texture_list_index"
        )
        row.operator("nebula.refresh_materials", icon="FILE_REFRESH", text="")

        layout.operator("nebula.show_stats", icon="INFO")

        if props.is_exporting:
            col = layout.column(align=True)
            col.label(text=props.export_message)
            col.prop(
                props,
                "export_progress",
                text=bpy.app.translations.pgettext("Export Progress"),
                slider=True,
                emboss=False,
            )
        else:
            layout.operator("nebula.export_nbl", icon="EXPORT")
