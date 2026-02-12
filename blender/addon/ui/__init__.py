import bpy
from .list import NBL_UL_TextureList
from .panel import NEBULA_PT_Panel

classes = (NBL_UL_TextureList, NEBULA_PT_Panel)


def register():
    for cls in classes:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)
