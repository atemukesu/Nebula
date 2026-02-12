import bpy
from .materials import NEBULA_OT_RefreshMaterials
from .show_stats import NEBULA_OT_ShowStats
from .export_fast import NEBULA_OT_ExportFast

classes = (NEBULA_OT_RefreshMaterials, NEBULA_OT_ShowStats, NEBULA_OT_ExportFast)


def register():
    for cls in classes:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)
