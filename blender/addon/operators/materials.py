from bpy.types import Operator


class NEBULA_OT_RefreshMaterials(Operator):
    bl_idname = "nebula.refresh_materials"
    bl_label = "刷新"

    def execute(self, context):
        props = context.scene.nebula_props
        if not props.target_collection:
            return {"CANCELLED"}
        existing = {item.material_name for item in props.texture_list}
        for obj in props.target_collection.all_objects:
            if obj.type == "MESH":
                for slot in obj.material_slots:
                    if slot.material and slot.material.name not in existing:
                        item = props.texture_list.add()
                        item.material_name = slot.material.name
                        existing.add(slot.material.name)
        return {"FINISHED"}
