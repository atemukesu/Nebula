import bpy
from bpy.types import PropertyGroup
from bpy.props import (
    StringProperty,
    FloatProperty,
    PointerProperty,
    BoolProperty,
    IntProperty,
    CollectionProperty,
)


class NBL_TextureItem(PropertyGroup):
    material_name: StringProperty(name="材质名")
    texture_path: StringProperty(
        name="NBL 纹理路径", default="minecraft:textures/particle/glitter_7.png"
    )


class NebulaProps(PropertyGroup):
    target_collection: PointerProperty(name="目标集合", type=bpy.types.Collection)
    filepath: StringProperty(name="路径", subtype="FILE_PATH", default="//output.nbl")
    scale: FloatProperty(name="缩放", default=10.0)
    sampling_density: FloatProperty(name="采样密度", default=10.0)
    particle_size: FloatProperty(name="大小", default=0.15)
    texture_list: CollectionProperty(type=NBL_TextureItem)
    texture_list_index: IntProperty()

    # 进度条相关
    is_exporting: BoolProperty(default=False)
    export_progress: FloatProperty(
        name="进度", default=0.0, min=0.0, max=100.0, subtype="PERCENTAGE"
    )
    export_message: StringProperty(default="")


def register():
    bpy.utils.register_class(NBL_TextureItem)
    bpy.utils.register_class(NebulaProps)
    bpy.types.Scene.nebula_props = PointerProperty(type=NebulaProps)


def unregister():
    del bpy.types.Scene.nebula_props
    bpy.utils.unregister_class(NebulaProps)
    bpy.utils.unregister_class(NBL_TextureItem)
