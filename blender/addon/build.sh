#!/bin/bash

# =================================================================
# NebulaFX NBL Exporter Build Script (Compatibility Version)
# =================================================================

# 1. 从 __init__.py 提取版本号
VERSION=$(grep '"version":' __init__.py | sed -E 's/.*\(([0-9, ]+)\).*/\1/' | sed 's/,[[:space:]]*/./g')

if [ -z "$VERSION" ]; then
    echo "❌ 错误: 无法在 __init__.py 中找到版本号。"
    exit 1
fi

ZIP_NAME="NebulaFX.NBL.Exporter.$VERSION.zip"
ADDON_FOLDER_NAME="addon"
TEMP_DIR="build_release_tmp"

echo "🚀 开始制作打包: $ZIP_NAME"

# 2. 创建临时目录结构
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR/$ADDON_FOLDER_NAME"

# 3. 复制项目文件 (排除构建无关项)
echo "📂 正在准备文件..."
# 使用 find 寻找除临时目录和 .zip 以外的所有一级文件/文件夹
for item in $(ls -A); do
    if [[ "$item" != "$TEMP_DIR" && "$item" != "$ZIP_NAME" && "$item" != "build.sh" && "$item" != ".git" && "$item" != "__pycache__" ]]; then
        cp -r "$item" "$TEMP_DIR/$ADDON_FOLDER_NAME/"
    fi
done

# 4. 使用 Python 进行打包 (解决环境中可能缺失 zip 命令的问题)
echo "📦 正在生成 ZIP 压缩包..."
python3 -c "
import zipfile
import os

zip_name = '$ZIP_NAME'
source_dir = '$TEMP_DIR'

with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, dirs, files in os.walk(source_dir):
        for file in files:
            file_path = os.path.join(root, file)
            # 保持内部结构: addon/xxx
            arcname = os.path.relpath(file_path, source_dir)
            zipf.write(file_path, arcname)
"

# 5. 清理临时文件
rm -rf "$TEMP_DIR"

if [ -f "$ZIP_NAME" ]; then
    echo "✅ 打包完成！"
    echo "📄 文件位置: $(pwd)/$ZIP_NAME"
else
    echo "❌ 错误: 打包失败，请检查 Python3 环境。"
    exit 1
fi
