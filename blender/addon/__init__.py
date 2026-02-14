import bpy
from . import properties
from . import operators
from . import ui
from . import i18n

bl_info = {
    "name": "NebulaFX NBL Exporter",
    "author": "Atemukesu",
    "version": (1, 0, 0),
    "blender": (3, 0, 0),
    "location": "View3D > Sidebar > NebulaFX",
    "description": "NBL Exporter: Export your Blender animation to nbl format by sampling.",
    "category": "Import-Export",
}


def register():
    bpy.app.translations.register(__name__, i18n.translation.i18n_dict)
    properties.register()
    operators.register()
    ui.register()


def unregister():
    ui.unregister()
    operators.unregister()
    properties.unregister()
    bpy.app.translations.unregister(__name__)


if __name__ == "__main__":
    register()
