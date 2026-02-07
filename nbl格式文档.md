# **NBL 文件格式规范 (v1.0 Final)**

### **全局标准 (Global Standards)**

1. **字节序 (Endianness):** 所有多字节数值强制使用 **Little-Endian (小端序)**。
2. **字符串编码:** 所有字符串使用 **UTF-8** 编码，前置 2 字节 `uint16` 表示长度。
3. **压缩算法:** 数据块强制使用 **Zstd (Zstandard)** 算法。每一帧必须独立压缩（无上下文依赖），以便支持随机跳转。
4. **坐标系:** Minecraft 原生坐标系 (1.0 = 1 block)。
5. **对齐:** 数据紧凑排列，无强制字节对齐（No Padding）。

---

### **1. 文件头 (File Header)**

*固定长度: 48 Bytes*
*位于文件起始位置，包含回放所需的元数据。*

| 偏移 (Offset) | 字段名 (Field) | 类型 (Type) | 值/描述 (Description) |
| --- | --- | --- | --- |
| 0x00 | `Magic` | `char[8]` | ASCII 固定值: **`NEBULAFX`** |
| 0x08 | `Version` | `uint16` | 固定值: **`1`** |
| 0x0A | `TargetFPS` | `uint16` | 录制时的帧率 (推荐 30 或 60) |
| 0x0C | `TotalFrames` | `uint32` | 动画总帧数 |
| 0x10 | `TextureCount` | `uint16` | 纹理贴图总数 (N) |
| 0x12 | `Attributes` | `uint16` | 位掩码: `0x01`=含Alpha, `0x02`=含Size (当前版本默认开启，填 3) |
| 0x14 | `BBoxMin` | `float[3]` | 整个动画的 AABB 包围盒最小点 (x, y, z)，用于视锥剔除 |
| 0x20 | `BBoxMax` | `float[3]` | 整个动画的 AABB 包围盒最大点 (x, y, z)，用于视锥剔除 |
| 0x2C | `Reserved` | `byte[4]` | 保留位，强制填 0 |

---

### **2. 纹理定义块 (Texture Block)**

*紧接在文件头之后。用于告诉渲染器需要加载哪些贴图到 `Texture2DArray`。*

**结构:** 依次循环读取 `TextureCount` 次。

```c
struct TextureEntry {
    uint16 pathLength;       // 路径字符串字节长度
    char   path[pathLength]; // 贴图路径 (如 "minecraft:textures/particle/flame.png")
    uint8  rows;             // 序列帧行数 (单图填 1)
    uint8  cols;             // 序列帧列数 (单图填 1)
}

```

---

### **3. 帧索引表 (Frame Index Table)**

*紧接在纹理定义块之后。*
*播放器必须在初始化时将此表完整读入内存，用于流式读取每一帧的数据。*

**结构:** 依次循环读取 `TotalFrames` 次。

| 字段名 | 类型 | 描述 |
| --- | --- | --- |
| `ChunkOffset` | `uint64` | 该帧压缩数据块相对于**文件起始处**的字节偏移量 |
| `ChunkSize` | `uint32` | 该帧压缩数据块的字节大小 |

### **4. 帧数据块 (Frame Data Chunk)**

*位于文件剩余区域。通过 Seek Table 定位。*

> **⚠️ 关键实现细节 (Critical Implementation Note):**
> 1. **压缩边界:** Zstd 压缩的输入必须是 **`[Chunk Header] + [Payload]`** 的完整拼接数据。
> * **错误做法:** `Header + Zstd(Payload)` (会导致解码器读不到 Magic Number 报错)。
> * **正确做法:** `Zstd(Header + Payload)`。解压后的第 1 个字节必须是 `FrameType`。
> 
> 
> 2. **独立性:** 每一帧的压缩必须使用**全新的上下文 (Clean Context)**，禁止使用流式压缩依赖上一帧的字典状态。
> 
> 

**解压后的数据结构** (Total Size = 5 + PayloadSize):

| 偏移 (Offset) | 字段名 | 类型 | 描述 |
| --- | --- | --- | --- |
| 0x00 | `FrameType` | `uint8` | **0 = I-Frame**; **1 = P-Frame** |
| 0x01 | `ParticleCount` | `uint32` | 当前帧粒子数 (N) |
| 0x05 | `Payload` | `bytes` | 数据体 (根据 FrameType 解析) |

---

### **4.2 数据体: Type 0 (I-Frame 全量帧)**

*仅当 `FrameType == 0` 时使用。*
*采用 **SoA (Structure of Arrays)** 纯数组布局，数据紧密排列，无额外 Padding。*

**物理内存布局 (Memory Layout):**
所有数组依次首尾相连。读取时需根据 `N` 计算偏移量。

| 顺序 | 数据块名称 | 数据类型 | 字节长度 | 详细内存排布 (必须严格遵守) |
| --- | --- | --- | --- | --- |
| 1 | `PosArrays` | `float32` | `3 * N * 4` | **非交错存储 (Non-interleaved):**<br>

<br>1. 先存 `N` 个 X 坐标 (`float32` x N)<br>

<br>2. 再存 `N` 个 Y 坐标 (`float32` x N)<br>

<br>3. 再存 `N` 个 Z 坐标 (`float32` x N) |
| 2 | `ColArrays` | `uint8` | `4 * N * 1` | **非交错存储:**<br>

<br>1. `N` 个 R 分量 (`uint8` x N)<br>

<br>2. `N` 个 G 分量<br>

<br>3. `N` 个 B 分量<br>

<br>4. `N` 个 A 分量 |
| 3 | `Sizes` | `uint16` | `N * 2` | `N` 个大小值紧密排列 |
| 4 | `TextureIDs` | `uint8` | `N * 1` | `N` 个贴图 ID |
| 5 | `SeqIndices` | `uint8` | `N * 1` | `N` 个序列帧索引 |
| 6 | `ParticleIDs` | `int32` | `N * 4` | `N` 个粒子唯一 ID |

> **示例偏移量计算:**
> * `OFFSET_X = 5` (Header后)
> * `OFFSET_Y = OFFSET_X + (N * 4)`
> * `OFFSET_Z = OFFSET_Y + (N * 4)`
> * `OFFSET_R = OFFSET_Z + (N * 4)`
> 
> 

---

### **4.3 数据体: Type 1 (P-Frame 差分帧)**

*仅当 `FrameType == 1` 时使用。*
*同样遵循 SoA 布局，顺序与 I-Frame 逻辑一致。*

| 顺序 | 数据块名称 | 数据类型 | 字节长度 | 详细内存排布 |
| --- | --- | --- | --- | --- |
| 1 | `PosDeltas` | `int16` | `3 * N * 2` | 1. `N` 个 dX (`int16`)<br>

<br>2. `N` 个 dY<br>

<br>3. `N` 个 dZ |
| 2 | `ColDeltas` | `int8` | `4 * N * 1` | 1. `N` 个 dR (`int8`)<br>

<br>2. `N` 个 dG<br>

<br>3. `N` 个 dB<br>

<br>4. `N` 个 dA |
| 3 | `SizeDeltas` | `int16` | `N * 2` | `N` 个 dSize |
| 4 | `TexIDDeltas` | `int8` | `N * 1` | `N` 个 dTexID |
| 5 | `SeqDeltas` | `int8` | `N * 1` | `N` 个 dSeq |
| 6 | `ParticleIDs` | `int32` | `N * 4` | `N` 个粒子 ID (用于匹配上一帧状态) |

### **5. 开发者实现必读 (The Rules)**

#### **A. 粒子生命周期逻辑 (Lifecycle Logic)**

在解析 **P-Frame** 时，根据 `ParticleIDs` 处理三种情况：

1. **更新 (Update):**
* 当前帧有 ID `100`，上一帧也有 ID `100`。
* 操作: `State[100] += Delta`。


2. **生成 (Spawn):**
* 当前帧有 ID `101`，上一帧**没有** ID `101`。
* 操作: **零基准原则**。假设 `PrevState[101]` 的所有属性均为 0。
* 即: `State[101].x = 0.0 + (dx / 1000.0)`。
* *生成器注意:* 当新粒子产生时，P-Frame 里的 Delta 实际上就是它的初始绝对值（乘上量化系数）。


3. **消亡 (Despawn):**
* 当前帧**没有** ID `99`，上一帧有 ID `99`。
* 操作: 从渲染列表中移除 ID `99`。


#### **B. 量化精度警示**

* **位置:** `int16` + `1000x` 缩放意味着单帧最大移动速度不能超过 **32.7 个方块**。
* 如果粒子瞬移超过 32 格，生成器**必须**强制将该帧标记为 I-Frame，或者将该粒子销毁并以新 ID 重新生成。


* **大小:** `int16` + `100x` 缩放意味着大小变化范围为 ±327.67。足够覆盖绝大多数需求。