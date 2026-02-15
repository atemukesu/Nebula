![Nebula Banner](/docs/assets/banner.png)

# Nebula FX

![License](https://img.shields.io/badge/LICENSE-GPL_v3-green?style=for-the-badge)
![OpenGL](https://img.shields.io/badge/OpenGL-4.4%2B-blue?style=for-the-badge&logo=opengl&logoColor=white)
![Mac Compatibility](https://img.shields.io/badge/MAC-UNSUPPORTED-red?style=for-the-badge&logo=apple)

**English** | [简体中文](/docs/README_zh.md) | [日本語](/docs/README_ja.md)

**Nebula** is a particle mod powered by a high-performance GPU-instanced rendering engine and optimized NBL streaming for fluid, large-scale animations.


## Core Features:

### High-Performance Rendering

* **GPU Driven:** Built on **OpenGL 4.4+ Compute Shaders** and **SSBO** technology.
* **Zero-Allocation:** Logically ensures no object allocation during runtime, completely eliminating GC pressure.
* **PMB (Persistent Mapped Buffer):** Utilizes persistent mapped buffer technology to achieve zero-copy data transfer from CPU to GPU.
* **OIT (Order Independent Transparency):** Supports weighted-blended OIT to perfectly resolve sorting issues during particle overlapping.

### Advanced Architectural Design

* **Multi-threaded Streaming:** `NblStreamer` performs Zstd decompression and state calculation in a background thread, ensuring the main rendering thread is never blocked.
* **Resource Reference Counting:** Intelligent `TextureCacheSystem` manages VRAM resources, supporting multi-instance reuse and automatic garbage collection.
* **Immutable Snapshots:** Uses `TextureAtlasMap` to guarantee absolute thread safety in multi-threaded environments.

### Compatibility

* Seamlessly compatible with **Iris Shaders**.
* Supports **ReplayMod** recording and rendering.

### .nbl File Format

Nebula uses a custom `.nbl` binary format specifically engineered for streaming:

* **Zstd Compression:** High compression ratios coupled with ultra-fast decompression speeds.
* **SoA Layout:** Data is organized in memory using a **Structure of Arrays** layout to maximize CPU cache hit rates.
* **I/P Frame Structure:** Similar to video encoding; utilizes Keyframes (I-Frames) and Predicted Frames (P-Frames) to drastically reduce file size.

For more information about the nbl format, please click [here](/docs/nbl_format_en.md).

##  Installation & Usage

### Prerequisites

* Minecraft 1.20.1 (Fabric)
* Fabric API
* ThreatenGL
* ModMenu
* Yet Another Config Lib
* GPU with OpenGL 4.4+ support

### Resource Paths

* **Animation Files:** `.minecraft/nebula/animations/*.nbl`

### Play Animations

You can use this command to play NBL Animations.

```
/nebula play <animation_name> [origin_x] [origin_y] [origin_z]
```

## Thanks
* [MadParticle](https://github.com/USS-Shenzhou/MadParticle) for the inspiration and reference implementation.

## Contributing

Contributions of any kind are welcome! You can participate by:

* **Submitting Issues**: Report bugs, suggest new features, or provide feedback.
* **Submitting Pull Requests**: Fix known issues, optimize code, or improve documentation.
* **Localization**: Help us translate the mod into more languages.

Please ensure your code follows the project's coding style before submitting a PR.

## License
This project is licensed under the [GPL-v3 License](LICENSE).

---

Made by Atemukesu with ❤️