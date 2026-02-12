![Nebula Banner](/docs/assets/banner.png)

# Nebula FX

![License](https://img.shields.io/badge/LICENSE-GPL_v3-green?style=for-the-badge)
![OpenGL](https://img.shields.io/badge/OpenGL-4.4%2B-blue?style=for-the-badge&logo=opengl&logoColor=white)
![Mac Compatibility](https://img.shields.io/badge/MAC-非対応-red?style=for-the-badge&logo=apple)

[English](/README.md) | [简体中文](/docs/README_zh.md) | **日本語**

**Nebula** は、高性能なGPUインスタンスレンダリングエンジンと最適化されたNBLストリーミングを搭載したパーティクルMODで、流動的な大規模アニメーションを実現します。


## 主な機能：

### 高性能レンダリング

* **GPU駆動：** **OpenGL 4.4+ コンピュートシェーダー** と **SSBO** 技術で構築。
* **ゼロアロケーション：** 実行時にオブジェクトの割り当てが発生しないことを論理的に保証し、GC圧力を完全に排除。
* **PMB（永続的マップドバッファ）：** 永続的マップドバッファ技術を利用して、CPUからGPUへのゼロコピーデータ転送を実現。
* **OIT（順序非依存透明度）：** 重み付けブレンドOITをサポートし、パーティクルの重なり時のソート問題を完璧に解決。

### 高度なアーキテクチャ設計

* **マルチスレッドストリーミング：** `NblStreamer` はバックグラウンドスレッドでZstd解凍と状態計算を実行し、メインレンダリングスレッドが決してブロックされないことを保証。
* **リソース参照カウント：** インテリジェントな `TextureCacheSystem` がVRAMリソースを管理し、複数インスタンスの再利用と自動ガベージコレクションをサポート。
* **不変スナップショット：** `TextureAtlasMap` を使用してマルチスレッド環境での絶対的なスレッドセーフティを保証。

### 互換性

* **Iris Shaders** とシームレスに互換。
* **ReplayMod** の録画とレンダリングをサポート。

### .nblファイル形式

Nebulaは、ストリーミング専用に設計されたカスタム `.nbl` バイナリ形式を使用：

* **Zstd圧縮：** 高圧縮率と超高速解凍速度を両立。
* **SoAレイアウト：** データは **Structure of Arrays** レイアウトでメモリに配置され、CPUキャッシュヒット率を最大化。
* **I/Pフレーム構造：** ビデオエンコーディングと同様、キーフレーム（Iフレーム）と予測フレーム（Pフレーム）を活用してファイルサイズを大幅に削減。

##  インストールと使用方法

### 必要条件

* Minecraft 1.20.1 (Fabric)
* Fabric API
* ThreatenGL
* ModMenu
* Yet Another Config Lib
* OpenGL 4.4+対応GPU

### リソースパス

* **アニメーションファイル：** `.minecraft/nebula/animations/*.nbl`

### アニメーションの再生

以下のコマンドでNBLアニメーションを再生できます。

```
/nebula play <アニメーション名> [原点_x] [原点_y] [原点_z]
```


## 貢献

あらゆる種類の貢献を歓迎します！以下の方法で参加できます：

* **Issueの提出**：バグ報告、新機能の提案、フィードバックの提供。
* **Pull Requestの提出**：既知の問題の修正、コードの最適化、ドキュメントの改善。
* **ローカライゼーション**：MODをより多くの言語に翻訳するお手伝い。

PRを提出する前に、コードがプロジェクトのコーディングスタイルに従っていることを確認してください。

## ライセンス
このプロジェクトは [GPL-v3 ライセンス](/LICENSE) の下でライセンスされています。

---

Atemukesuが ❤️ を込めて制作