from bpy.types import Panel
from ..utils.dependencies import HAS_ZSTD


class NEBULA_PT_Panel(Panel):
    bl_label = "NebulaFX Fast"
    bl_idname = "NEBULA_PT_Panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "NebulaFX"

    def draw(self, context):
        layout = self.layout
        props = context.scene.nebula_props
        if not HAS_ZSTD:
            layout.label(text="需安装 zstandard", icon="ERROR")
            return
        layout.prop(props, "target_collection")
        layout.prop(props, "filepath")
        layout.prop(props, "scale")
        layout.prop(props, "sampling_density")
        layout.prop(props, "particle_size")

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
                props, "export_progress", text="导出进度", slider=True, emboss=False
            )
        else:
            layout.operator("nebula.export_nbl", icon="EXPORT")
