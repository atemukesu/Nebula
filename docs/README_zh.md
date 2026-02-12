![Nebula Banner](/docs/assets/banner.png)

# Nebula FX

![License](https://img.shields.io/badge/LICENSE-GPL_v3-green?style=for-the-badge)
![OpenGL](https://img.shields.io/badge/OpenGL-4.4%2B-blue?style=for-the-badge&logo=opengl&logoColor=white)
![Mac Compatibility](https://img.shields.io/badge/MAC-不支持-red?style=for-the-badge&logo=apple)

[English](/README.md) | **简体中文** | [日本語](/docs/README_ja.md)

**Nebula** 是一个粒子模组，采用高性能 GPU 实例化渲染引擎和优化的 NBL 流式传输技术，实现流畅的大规模动画效果。


## 核心特性：

### 高性能渲染

* **GPU 驱动：** 基于 **OpenGL 4.4+ 计算着色器** 和 **SSBO** 技术构建。
* **零分配：** 从逻辑上确保运行时无对象分配，完全消除 GC 压力。
* **PMB（持久映射缓冲区）：** 利用持久映射缓冲区技术实现从 CPU 到 GPU 的零拷贝数据传输。
* **OIT（顺序无关透明）：** 支持加权混合 OIT，完美解决粒子重叠时的排序问题。

### 先进的架构设计

* **多线程流式传输：** `NblStreamer` 在后台线程中执行 Zstd 解压缩和状态计算，确保主渲染线程永不阻塞。
* **资源引用计数：** 智能 `TextureCacheSystem` 管理显存资源，支持多实例复用和自动垃圾回收。
* **不可变快照：** 使用 `TextureAtlasMap` 保证多线程环境下的绝对线程安全。

### 兼容性

* 与 **Iris Shaders** 无缝兼容。
* 支持 **ReplayMod** 录制和渲染。

### .nbl 文件格式

Nebula 使用专为流式传输设计的自定义 `.nbl` 二进制格式：

* **Zstd 压缩：** 高压缩比配合超快解压速度。
* **SoA 布局：** 数据在内存中采用 **数组结构体（Structure of Arrays）** 布局，最大化 CPU 缓存命中率。
* **I/P 帧结构：** 类似视频编码；利用关键帧（I-Frames）和预测帧（P-Frames）大幅减小文件大小。

##  安装与使用

### 前置要求

* Minecraft 1.20.1 (Fabric)
* Fabric API
* ThreatenGL
* ModMenu
* Yet Another Config Lib
* 支持 OpenGL 4.4+ 的 GPU

### 资源路径

* **动画文件：** `.minecraft/nebula/animations/*.nbl`

### 播放动画

您可以使用以下命令播放 NBL 动画。
```
/nebula play <动画名称> [起点_x] [起点_y] [起点_z]
```


## 贡献

欢迎任何形式的贡献！您可以通过以下方式参与：

* **提交 Issue**：报告 bug、建议新功能或提供反馈。
* **提交 Pull Request**：修复已知问题、优化代码或改进文档。
* **本地化**：帮助我们将模组翻译成更多语言。

提交 PR 前，请确保您的代码遵循项目的编码规范。

## 许可证
本项目采用 [GPL-v3 许可证](/LICENSE)。