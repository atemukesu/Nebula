# **NBL File Format Specification (v1.0 Final)**

### **Global Standards**

1. **Endianness:** All multi-byte values MUST use **Little-Endian**.
2. **String Encoding:** All strings use **UTF-8** encoding, prefixed by a 2-byte `uint16` representing the length.
3. **Compression Algorithm:** Data blocks MUST use the **Zstd (Zstandard)** algorithm. Each frame must be compressed independently (no context dependency) to support random seeking.
4. **Coordinate System:** Minecraft native coordinate system (1.0 = 1 block).
5. **Alignment:** Data is packed tightly with no padding.

---

### **1. File Header**

*Fixed Length: 48 Bytes*
*Located at the beginning of the file, containing metadata required for playback.*

| Offset | Field | Type | Description |
| --- | --- | --- | --- |
| 0x00 | `Magic` | `char[8]` | ASCII constant: **`NEBULAFX`** |
| 0x08 | `Version` | `uint16` | Constant: **`1`** |
| 0x0A | `TargetFPS` | `uint16` | Recording frame rate (recommended 30 or 60) |
| 0x0C | `TotalFrames` | `uint32` | Total number of frames in the animation |
| 0x10 | `TextureCount` | `uint16` | Total number of textures (N) |
| 0x12 | `Attributes` | `uint16` | Bitmask: `0x01`=Alpha, `0x02`=Size (Default 3 in current version) |
| 0x14 | `BBoxMin` | `float[3]` | AABB bounding box minimum (x, y, z) for frustum culling |
| 0x20 | `BBoxMax` | `float[3]` | AABB bounding box maximum (x, y, z) for frustum culling |
| 0x2C | `Reserved` | `byte[4]` | Reserved bits, must be 0 |

---

### **2. Texture Block**

*Immediately follows the File Header. Tells the renderer which textures to load into the `Texture2DArray`.*

**Structure:** Read `TextureCount` times.

```c
struct TextureEntry {
    uint16 pathLength;       // Byte length of the path string
    char   path[pathLength]; // Texture path (e.g., "minecraft:textures/particle/flame.png")
    uint8  rows;             // Sequence animation rows (1 for single image)
    uint8  cols;             // Sequence animation columns (1 for single image)
}
```

---

### **3. Frame Index Table**

*Immediately follows the Texture Block.*
*The player must read this entire table into memory during initialization for streaming.*

**Structure:** Read `TotalFrames` times.

| Field | Type | Description |
| --- | --- | --- |
| `ChunkOffset` | `uint64` | Byte offset of the compressed frame block relative to the **file start** |
| `ChunkSize` | `uint32` | Byte size of the compressed frame block |

---

### **4. Keyframe Index Table**

*Immediately follows the Frame Index Table.*

**Purpose:** Records the frame indices of all I-Frames (Type 0) for fast seeking.

| Field | Type | Description |
| --- | --- | --- |
| `KeyframeCount` | `uint32` | Total number of keyframes (K) |
| `KeyframeIndices` | `uint32[K]` | Sorted list of keyframe frame numbers (e.g., 0, 60, 120...) |

---

### **5. Frame Data Chunk**

*Located in the remaining area of the file. Located via the Seek Table.*

> **Critical Implementation Note:**
> 1. **Compression Boundary:** Zstd input must be the full concatenation of **`[Chunk Header] + [Payload]`**.
> * **Incorrect:** `Header + Zstd(Payload)` (Decoder will fail to find magic number).
> * **Correct:** `Zstd(Header + Payload)`. The 1st byte after decompression must be `FrameType`.
> 
> 2. **Independence:** Each frame must be compressed using a **Clean Context**. Do not use streaming compression that depends on previous frame dictionary states.

**Decompressed Structure** (Total Size = 5 + PayloadSize):

| Offset | Field | Type | Description |
|-------------|-----------------|----------|----------------------------------|
| 0x00        | `FrameType`     | `uint8`  | **0 = I-Frame**; **1 = P-Frame** |
| 0x01        | `ParticleCount` | `uint32` | Current frame particle count (N) |
| 0x05        | `Payload`       | `bytes`  | Data payload (parsed based on FrameType) |

---

### **5.2 Payload: Type 0 (I-Frame)**

*Used only when `FrameType == 0`.*
*Uses a **SoA (Structure of Arrays)** layout with tightly packed data and no padding.*

**Physical Memory Layout:**
Arrays are sequential. Calculate offsets based on `N`.

| Order | Block Name | Type | Length | Detailed Memory Layout (Strictly Enforced) |
|----|-------------|-----------|-------------|----------------------------------|
| 1  | `PosArrays` | `float32` | `3 * N * 4` | **Non-interleaved:**<br>1. `N` X-coordinates (`float32` x N)<br>2. `N` Y-coordinates (`float32` x N)<br>3. `N` Z-coordinates (`float32` x N) |
| 2 | `ColArrays` | `uint8` | `4 * N * 1` | **Non-interleaved:**<br>1. `N` R components (`uint8` x N)<br>2. `N` G components<br>3. `N` B components<br>4. `N` A components |
| 3 | `Sizes` | `uint16` | `N * 2` | `N` size values tightly packed |
| 4 | `TextureIDs` | `uint8` | `N * 1` | `N` texture IDs |
| 5 | `SeqIndices` | `uint8` | `N * 1` | `N` sequence indices |
| 6 | `ParticleIDs` | `int32` | `N * 4` | `N` unique particle IDs |

> **Example Offset Calculation:**
> * `OFFSET_X = 5` (After Header)
> * `OFFSET_Y = OFFSET_X + (N * 4)`
> * `OFFSET_Z = OFFSET_Y + (N * 4)`
> * `OFFSET_R = OFFSET_Z + (N * 4)`

---

### **5.3 Payload: Type 1 (P-Frame)**

*Used only when `FrameType == 1`.*
*Follows SoA layout, matching I-Frame logic.*

| Order | Block Name | Type | Length | Detailed Memory Layout |
|----|-------------|---------|-------------|---------------------------|
| 1  | `PosDeltas` | `int16` | `3 * N * 2` | 1. `N` dX (`int16`)<br>2. `N` dY<br>3. `N` dZ |
| 2 | `ColDeltas` | `int8` | `4 * N * 1` | 1. `N` dR (`int8`)<br>2. `N` dG<br>3. `N` dB<br>4. `N` dA |
| 3 | `SizeDeltas` | `int16` | `N * 2` | `N` dSize values |
| 4 | `TexIDDeltas` | `int8` | `N * 1` | `N` dTexID values |
| 5 | `SeqDeltas` | `int8` | `N * 1` | `N` dSeq values |
| 6 | `ParticleIDs` | `int32` | `N * 4` | `N` Particle IDs (matching previous state) |

### **6. Developer Implementation Guidelines**

#### **A. Particle Lifecycle Logic**

When parsing a **P-Frame**, handle three scenarios based on `ParticleIDs`:

1. **Update:**
* ID `100` exists in current and previous frame.
* Action: `State[100] += Delta`.

2. **Spawn:**
* ID `101` exists in current frame but NOT in previous frame.
* Action: **Zero-Basis Principle**. Assume `PrevState[101]` properties are all 0.
* i.e., `State[101].x = 0.0 + (dx / 1000.0)`.
* *Note:* For new particles, the Delta in P-Frame is effectively its absolute initial value (multiplied by quantization scale).

3. **Despawn:**
* ID `99` existed in previous frame but NOT in current frame.
* Action: Remove ID `99` from the render list.

#### **B. Quantization Warning**

* **Position:** `int16` + `1000x` scale means the max movement per frame cannot exceed **32.7 blocks**.
* If a particle teleports more than 32 blocks, the generator **MUST** force an I-Frame or despawn/respawn with a new ID.
* **Size:** `int16` + `100x` scale means size changes range ±327.67, sufficient for most needs.
