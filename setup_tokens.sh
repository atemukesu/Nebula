#!/bin/bash

# =============================================================================
# Nebula Mod - Token 获取指南
# =============================================================================
# 这个脚本会逐步指导你获取所需的 API Token
# =============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}  Nebula Mod - Token 获取指南${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""

# 检查 jq
echo "检查环境依赖..."
if ! command -v jq &> /dev/null; then
    echo -e "${RED}错误：未检测到 jq 工具${NC}"
    echo "jq 用于构建合法的 JSON 数据以调用 Modrinth API"
    echo ""
    echo "请安装 jq:"
    echo "  Ubuntu/Debian: sudo apt install jq"
    echo "  Fedora/RHEL: sudo dnf install jq"
    echo "  Arch Linux: sudo pacman -S jq"
    echo "  macOS: brew install jq"
    exit 1
fi
echo -e "${GREEN}✓ jq 已安装${NC}"
echo ""

# 检查是否已存在 .env 文件
if [ -f ".env" ]; then
    echo -e "${YELLOW}警告：.env 文件已存在！${NC}"
    echo "是否要覆盖现有配置？(y/N)"
    read -p "> " confirm
    if [[ ! $confirm =~ ^[Yy]$ ]]; then
        echo "已取消操作"
        exit 0
    fi
fi

echo ""
echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}第一步：获取 GitHub Token${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""
echo "请按照以下步骤获取 GitHub Token:"
echo ""
echo "1. 访问：https://github.com/settings/tokens/new"
echo "2. 选择 'Classic' token 类型"
echo "3. Note 填写：Nebula Build Script"
echo "4. 勾选权限:"
echo "   - 公开仓库：勾选 public_repo"
echo "   - 私有仓库：勾选 repo (Full control of private repositories)"
echo "5. 点击 'Generate token'"
echo "6. 复制生成的 token (以 ghp_ 开头)"
echo ""

read -p "按回车键继续..." 

echo ""
read -p "请输入你的 GitHub Token: " GITHUB_TOKEN

if [ -z "$GITHUB_TOKEN" ]; then
    echo -e "${YELLOW}跳过 GitHub Token 设置${NC}"
else
    echo -e "${GREEN}✓ GitHub Token 已保存${NC}"
fi

echo ""
read -p "请输入你的 GitHub 仓库 (格式：username/repo): " GITHUB_REPOSITORY

if [ -z "$GITHUB_REPOSITORY" ]; then
    echo -e "${YELLOW}未设置仓库信息，将跳过 GitHub Releases 上传${NC}"
else
    echo -e "${GREEN}✓ 仓库信息已保存：$GITHUB_REPOSITORY${NC}"
fi

echo ""
echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}第二步：获取 Modrinth Token${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""
echo "请按照以下步骤获取 Modrinth Token:"
echo ""
echo "1. 访问：https://modrinth.com/settings/account"
echo "2. 向下滚动到 'Access tokens' 部分"
echo "3. 点击 'Create new token'"
echo "4. Name 填写：Nebula Build Script"
echo "5. Permissions 保持默认即可"
echo "6. 点击 'Create token'"
echo "7. 复制生成的 token (一串随机字符)"
echo ""

read -p "按回车键继续..."

echo ""
read -p "请输入你的 Modrinth Token: " MODRINTH_TOKEN

if [ -z "$MODRINTH_TOKEN" ]; then
    echo -e "${YELLOW}跳过 Modrinth Token 设置${NC}"
else
    echo -e "${GREEN}✓ Modrinth Token 已保存${NC}"
fi

echo ""
echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}第三步：创建 .env 配置文件${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""

# 创建 .env 文件
cat > .env << EOF
# Nebula Mod 构建与发布配置
# 生成日期：$(date +"%Y-%m-%d %H:%M:%S")

# GitHub 配置
EOF

if [ -n "$GITHUB_TOKEN" ]; then
    echo "GITHUB_TOKEN=$GITHUB_TOKEN" >> .env
fi

if [ -n "$GITHUB_REPOSITORY" ]; then
    echo "GITHUB_REPOSITORY=$GITHUB_REPOSITORY" >> .env
fi

if [ -n "$MODRINTH_TOKEN" ]; then
    echo "" >> .env
    echo "# Modrinth 配置" >> .env
    echo "MODRINTH_TOKEN=$MODRINTH_TOKEN" >> .env
fi

echo "" >> .env
echo "# 可选配置" >> .env
echo "VERBOSE=false" >> .env
echo "SKIP_TESTS=true" >> .env

echo -e "${GREEN}✓ .env 文件已创建${NC}"
echo ""
echo -e "${BLUE}配置文件内容:${NC}"
echo "----------------------------------------"
cat .env
echo "----------------------------------------"
echo ""
echo -e "${GREEN}==============================================${NC}"
echo -e "${GREEN}  配置完成!${NC}"
echo -e "${GREEN}==============================================${NC}"
echo ""
echo "现在你可以运行以下命令开始构建:"
echo -e "  ${YELLOW}./build_and_release.sh${NC}"
echo ""
echo "或者指定版本号:"
echo -e "  ${YELLOW}./build_and_release.sh 1.0.6${NC}"
echo ""
echo -e "${YELLOW}提示:${NC} .env 文件不会被提交到 Git，请妥善保管"
echo ""
