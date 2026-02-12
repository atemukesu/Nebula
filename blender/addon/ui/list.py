from bpy.types import UIList


class NBL_UL_TextureList(UIList):
    def draw_item(
        self, context, layout, data, item, icon, active_data, active_propname, index
    ):
        layout.label(text=item.material_name, icon="MATERIAL")
        layout.prop(item, "texture_path", text="")
