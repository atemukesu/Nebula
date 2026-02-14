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
    material_name: StringProperty(name="Material Name")
    texture_path: StringProperty(
        name="NBL Texture Path", default="minecraft:textures/particle/glitter_7.png"
    )


class NebulaProps(PropertyGroup):
    target_collection: PointerProperty(
        name="Target Collection", type=bpy.types.Collection
    )
    filepath: StringProperty(name="Path", subtype="FILE_PATH", default="//output.nbl")
    scale: FloatProperty(name="Scale", default=10.0)
    sampling_density: FloatProperty(name="Sampling Density", default=10.0)
    particle_size: FloatProperty(name="Size", default=0.15)

    # Data Source Selection
    use_mesh_scatter: BoolProperty(name="Mesh Scatter", default=True)
    use_particle_system: BoolProperty(name="Particle System", default=False)
    use_point_cloud: BoolProperty(name="Point Cloud", default=False)

    texture_list: CollectionProperty(type=NBL_TextureItem)
    texture_list_index: IntProperty()

    # Progress related
    is_exporting: BoolProperty(default=False)
    export_progress: FloatProperty(
        name="Progress", default=0.0, min=0.0, max=100.0, subtype="PERCENTAGE"
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
