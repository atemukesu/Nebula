#!/bin/bash

# =============================================================================
# Nebula Mod 自动化构建与发布脚本
# =============================================================================
# 功能:
#   1. 自动编译 1.20.1 和 1.21.1 双版本
#   2. 上传构建产物到 GitHub Releases
#   3. 上传构建产物到 Modrinth
#   4. 自动填写版本号和依赖要求
#
# 使用方法:
#   ./build_and_release.sh [版本号]
#
# 环境变量要求 (可通过 .env 文件设置):
#   - GITHUB_TOKEN: GitHub Personal Access Token (需要 repo 权限)
#   - MODRINTH_TOKEN: Modrinth API Token
#   - GITHUB_REPOSITORY: GitHub 仓库 (格式：username/repo)
#
# .env 文件示例:
#   GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
#   MODRINTH_TOKEN=xxxxxxxxxxxxxxxxxxxx
#   GITHUB_REPOSITORY=atemukesu/nebula
# =============================================================================

set -e  # 遇到错误立即退出

# --------------------------- 颜色输出 --------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# --------------------------- 加载环境变量 ----------------------------------
load_env_file() {
    local env_file=".env"
    
    if [ -f "$env_file" ]; then
        log_info "检测到 .env 文件，正在加载..."
        
        # 读取 .env 文件并导出变量
        while IFS='=' read -r key value; do
            # 跳过注释和空行
            [[ "$key" =~ ^#.*$ ]] && continue
            [[ -z "$key" ]] && continue
            
            # 去除引号
            value=$(echo "$value" | sed 's/^["'\'']//' | sed 's/["'\'']*$//')
            
            # 导出变量
            export "$key=$value"
            log_info "已加载：$key"
        done < "$env_file"
        
        log_success ".env 文件加载完成"
    else
        log_info "未找到 .env 文件，将使用系统环境变量"
    fi
}

# --------------------------- 环境检查 --------------------------------------
check_environment() {
    log_info "检查环境依赖..."
    
    # 首先尝试加载 .env 文件
    load_env_file
    
    # 检查 Java
    if ! command -v java &> /dev/null; then
        log_error "Java 未安装，请安装 Java 21"
        exit 1
    fi
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    log_info "Java 版本：$JAVA_VERSION"
    
    # 检查 Gradle
    if [ ! -f "./gradlew" ]; then
        log_error "gradlew 不存在，请确保在项目根目录运行此脚本"
        exit 1
    fi
    
    # 检查 Git
    if ! command -v git &> /dev/null; then
        log_error "Git 未安装"
        exit 1
    fi
    
    # 检查必要的环境变量
    if [ -z "$GITHUB_TOKEN" ]; then
        log_warning "GITHUB_TOKEN 未设置，将跳过 GitHub Releases 上传"
    fi
    
    if [ -z "$MODRINTH_TOKEN" ]; then
        log_warning "MODRINTH_TOKEN 未设置，将跳过 Modrinth 上传"
    fi
    
    if [ -z "$GITHUB_REPOSITORY" ]; then
        log_warning "GITHUB_REPOSITORY 未设置，将跳过 GitHub Releases 上传"
    fi
    
    log_success "环境检查完成"
}

# --------------------------- 获取版本信息 ----------------------------------
get_version_info() {
    # 从 gradle.properties 读取基础版本号
    BASE_VERSION=$(grep "^mod.version=" gradle.properties | cut -d'=' -f2)
    
    # 如果提供了命令行参数，使用提供的版本号
    if [ -n "$1" ]; then
        VERSION="$1"
        log_info "使用指定的版本号：$VERSION"
    else
        VERSION="$BASE_VERSION"
        log_info "使用默认版本号：$VERSION"
    fi
    
    # 获取当前 Git 提交哈希
    GIT_HASH=$(git rev-parse --short HEAD)
    BUILD_DATE=$(date +"%Y-%m-%d_%H-%M-%S")
    
    log_info "构建日期：$BUILD_DATE"
    log_info "Git 提交：$GIT_HASH"
}

# --------------------------- 收集更新描述 ----------------------------------
collect_changelog_input() {
    local changelog_file="build/manual_changelog.md"
    
    log_info "=========================================="
    log_info "请输入更新内容（可选）"
    log_info "=========================================="
    echo ""
    echo "请输入本版本的主要更新内容（每行一条，输入空行结束）:"
    echo "例如："
    echo "  - 修复了粒子渲染的 bug"
    echo "  - 优化了 NBL 流式加载性能"
    echo "  - 添加了新的粒子效果类型"
    echo ""
    
    local updates=()
    while true; do
        # 加上 -e 参数，开启 readline 支持，完美解决中文输入法兼容问题
        read -e -p "> " line
        if [ -z "$line" ]; then
            break
        fi
        updates+=("$line")
    done
    
    if [ ${#updates[@]} -eq 0 ]; then
        log_info "未输入更新内容，将使用 Git 提交记录自动生成"
        return 1
    fi
    
    # 写入临时文件
    mkdir -p build
    cat > "$changelog_file" << EOF
## 主要更新

EOF
    
    for update in "${updates[@]}"; do
        echo "$update" >> "$changelog_file"
    done
    
    echo ""
    log_success "已记录 ${#updates[@]} 条更新内容"
    return 0
}

# --------------------------- 构建指定版本 ----------------------------------
build_version() {
    local mc_version=$1
    local version_name=$2
    
    log_info "=========================================="
    log_info "开始构建 Minecraft $mc_version 版本"
    log_info "=========================================="
    
    # 激活对应版本
    log_info "激活 Stonecutter 版本：$mc_version"

    # 清理之前的构建
    log_info "清理旧的构建文件..."
    ./gradlew clean --no-daemon
    
    # 执行构建
    log_info "编译 $mc_version 版本..."
    ./gradlew :$version_name:build --no-daemon
    
    # 复制构建产物
    log_info "复制构建产物..."
    mkdir -p "build/releases/$mc_version"
    cp "versions/$mc_version/build/libs/"*".jar" "build/releases/$mc_version/" 2>/dev/null || true
    
    # 验证构建产物
    if [ -f "build/releases/$mc_version/nebula-${VERSION}+${mc_version}.jar" ]; then
        log_success "$mc_version 版本构建成功"
        ls -lh "build/releases/$mc_version/"*.jar
    else
        log_error "$mc_version 版本构建失败，找不到输出文件"
        exit 1
    fi
}

# --------------------------- 构建所有版本 ----------------------------------
build_all_versions() {
    log_info "=========================================="
    log_info "开始构建所有版本"
    log_info "=========================================="
    
    # 构建 1.20.1 版本
    build_version "1.20.1" "1.20.1"
    
    # 构建 1.21.1 版本
    build_version "1.21.1" "1.21.1"
    
    log_success "所有版本构建完成!"
    log_info "构建产物位置:"
    find build/releases -name "*.jar" -type f
}

# --------------------------- 生成更新日志 ----------------------------------
generate_changelog() {
    local changelog_file="build/changelog.md"
    local manual_changelog="build/manual_changelog.md"
    
    log_info "生成更新日志..."
    
    # 尝试获取上一个版本的 tag
    local previous_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    
    # 创建更新日志头部
    cat > "$changelog_file" << EOF
# Nebula v${VERSION}

## 发布信息
- **发布日期**: $(date +"%Y-%m-%d %H:%M:%S")
- **Git 提交**: ${GIT_HASH}
EOF

    if [ -n "$previous_tag" ]; then
        echo "- **上一版本**: ${previous_tag}" >> "$changelog_file"
    fi
    
    echo "" >> "$changelog_file"
    
    # 如果有手动输入的更新内容，优先使用
    if [ -f "$manual_changelog" ]; then
        cat "$manual_changelog" >> "$changelog_file"
        echo "" >> "$changelog_file"
    fi
    
    # 添加 Git 提交记录
    echo "## 详细提交记录" >> "$changelog_file"
    echo "" >> "$changelog_file"
    
    if [ -n "$previous_tag" ]; then
        echo "### 自上版本 ${previous_tag} 以来的变更" >> "$changelog_file"
        git log --pretty=format:"- %s (%h)" "${previous_tag}..HEAD" >> "$changelog_file" 2>/dev/null || echo "- 暂无详细记录" >> "$changelog_file"
    else
        echo "### 首次发布" >> "$changelog_file"
        git log --pretty=format:"- %s (%h)" -20 >> "$changelog_file" 2>/dev/null || echo "- 暂无详细记录" >> "$changelog_file"
    fi
    
    echo "" >> "$changelog_file"
    echo "" >> "$changelog_file"
    
    # 添加 PR 信息（如果有 gh 工具）
    if command -v gh &> /dev/null; then
        local pr_count=$(gh pr list --state merged --limit 10 2>/dev/null | wc -l)
        if [ "$pr_count" -gt 0 ]; then
            echo "### 合并的 Pull Requests" >> "$changelog_file"
            gh pr list --state merged --limit 10 --json number,title,author --template '{{range .}}- #{{.number}} {{.title}} (by @{{.author.login}}){{end}}' >> "$changelog_file" 2>/dev/null || true
            echo "" >> "$changelog_file"
        fi
    fi
    
    # 添加版本兼容性信息
    cat >> "$changelog_file" << EOF
---

## 兼容性说明

### Minecraft 1.20.1 版本
**支持范围**: Minecraft 1.20 ~ 1.20.1

**必需依赖**:
- Fabric Loader >=0.17
- Fabric API >=0.92.2+1.20.1
- Java >=21
- Mod Menu >=7.2.2
- YACL >=3.5.0+1.20.1-fabric
- ThreatenGL >=2.0.4 (光影和渲染支持)

**可选依赖**:
- Iris >=1.6.17+1.20.1 (备用光影支持)
- ReplayMod >=1.20.1-2.6.23 (录像支持)

### Minecraft 1.21.1 版本
**支持范围**: Minecraft 1.21 ~ 1.21.1

**必需依赖**:
- Fabric Loader >=0.17
- Fabric API >=0.110.0+1.21.1
- Java >=21
- Mod Menu >=11.0.3
- YACL >=3.8.1+1.21.1-fabric
- ThreatenGL >=2.0.4 (光影和渲染支持)

**可选依赖**:
- Iris >=1.8.8+1.21.1-fabric (备用光影支持)
- ReplayMod >=1.21-2.6.23 (录像支持)

---

## 技术规格

- **渲染引擎**: GPU-instanced 高性能渲染
- **粒子格式**: NBL 流式压缩传输
- **OpenGL 要求**: 4.4+
- **着色器支持**: Compute Shader, SSBO
- **OIT 支持**: 顺序独立透明度渲染

---

## 文件清单

本次发布包含以下文件：
EOF
    
    # 列出将要上传的文件
    for mc_version in "1.20.1" "1.21.1"; do
        local jar_file="build/releases/${mc_version}/nebula-${VERSION}+${mc_version}.jar"
        if [ -f "$jar_file" ]; then
            local file_size=$(ls -lh "$jar_file" | awk '{print $5}')
            local md5_hash=$(md5sum "$jar_file" | cut -d' ' -f1)
            local sha256_hash=$(sha256sum "$jar_file" | cut -d' ' -f1)
            echo "- \`nebula-${VERSION}+${mc_version}.jar\` (${file_size})" >> "$changelog_file"
            echo "  - MD5: \`${md5_hash}\`" >> "$changelog_file"
            echo "  - SHA256: \`${sha256_hash}\`" >> "$changelog_file"
        fi
    done
    
    log_success "更新日志已生成：$changelog_file"
    log_info "更新日志预览（前 40 行）:"
    echo "=========================================="
    head -n 40 "$changelog_file"
    echo "=========================================="
}

# --------------------------- 上传到 GitHub Releases ------------------------
upload_to_github() {
    if [ -z "$GITHUB_TOKEN" ] || [ -z "$GITHUB_REPOSITORY" ]; then
        log_warning "缺少 GITHUB_TOKEN 或 GITHUB_REPOSITORY，跳过 GitHub 上传"
        return
    fi
    
    log_info "=========================================="
    log_info "开始上传到 GitHub Releases"
    log_info "=========================================="
    
    local release_tag="v${VERSION}"
    local release_name="Nebula v${VERSION}"
    local changelog_file="build/changelog.md"
    
    # 创建 Release
    log_info "创建 GitHub Release: $release_tag"
    
    # 检查 Release 是否已存在
    if gh release view "$release_tag" &> /dev/null; then
        log_warning "Release $release_tag 已存在，删除旧版本..."
        gh release delete "$release_tag" --cleanup-tag --yes || true
    fi
    
    # 准备上传的文件列表
    local files_to_upload=()
    for jar_file in build/releases/*/nebula-*.jar; do
        if [ -f "$jar_file" ]; then
            files_to_upload+=("$jar_file")
        fi
    done
    
    # 创建 Release 并上传文件
    gh release create "$release_tag" \
        --repo "$GITHUB_REPOSITORY" \
        --title "$release_name" \
        --notes-file "$changelog_file" \
        "${files_to_upload[@]}" \
        || {
            log_error "GitHub Release 创建失败"
            return 1
        }
    
    log_success "成功上传到 GitHub Releases"
    log_info "Release 链接：https://github.com/${GITHUB_REPOSITORY}/releases/tag/${release_tag}"
}
# --------------------------- 上传到 Modrinth -------------------------------
upload_to_modrinth() {
    if [ -z "$MODRINTH_TOKEN" ]; then
        log_warning "缺少 MODRINTH_TOKEN，跳过 Modrinth 上传"
        return
    fi

    log_info "=========================================="
    log_info "开始上传到 Modrinth"
    log_info "=========================================="

    # 修复 2: 严格清除可能存在的 Windows \r 换行符
    local modrinth_id=$(grep "^publish.modrinth=" gradle.properties | cut -d'=' -f2 | tr -d '\r')

    if [ -z "$modrinth_id" ] || [ "$modrinth_id" = "# Modrinth mod slug" ]; then
        log_error "未在 gradle.properties 中设置 publish.modrinth"
        exit 1
    fi

    log_info "Modrinth 项目 ID: $modrinth_id"
    local changelog_file="build/changelog.md"
    local changelog_content=$(cat "$changelog_file")

    for mc_version in "1.20.1" "1.21.1"; do
        local jar_file="build/releases/${mc_version}/nebula-${VERSION}+${mc_version}.jar"

        if [ ! -f "$jar_file" ]; then
            log_error "找不到文件：$jar_file"
            continue
        fi

        log_info "上传 $mc_version 版本到 Modrinth..."
        local version_name="${VERSION}-${mc_version}"

        # 修复 3: 使用 -c 参数将 JSON 压缩为安全的单行字符串，并移除冗余的 null 字段
        local json_data=$(jq -n -c \
            --arg name "${version_name}" \
            --arg version "${version_name}" \
            --arg project_id "${modrinth_id}" \
            --arg mc_version "${mc_version}" \
            --arg desc "Nebula ${VERSION} for Minecraft ${mc_version}" \
            --arg changelog "${changelog_content}" \
            '{
                name: $name,
                version_number: $version,
                version_type: "release",
                project_id: $project_id,
                file_parts: ["file"],
                primary_file: "file",
                dependencies: [
                    {
                        project_id: "P7dR8mSH",
                        dependency_type: "required"
                    },
                    {
                        project_id: "mOgUt4GM",
                        dependency_type: "required"
                    },
                    {
                        project_id: "1eAoo2KR",
                        dependency_type: "required"
                    },
                    {
                        project_id: "RSFrpoou",
                        dependency_type: "required"
                    }
                ],
                loaders: ["fabric"],
                game_versions: [$mc_version],
                featured: true,
                status: "listed",
                description: $desc,
                changelog: $changelog
            }')

        log_info "发送请求到 Modrinth API..."

        # 修复 1: 使用固定的 "file" 作为 form part name，避免因文件名中的 + 号导致解析崩溃
        local response=$(curl -s -X POST \
            -H "Authorization: ${MODRINTH_TOKEN}" \
            -F "data=${json_data}" \
            -F "file=@${jar_file}" \
            "https://api.modrinth.com/v2/version")

        # 检查响应
        if echo "$response" | grep -q '"id"'; then
            local version_id=$(echo "$response" | jq -r '.id // empty')
            log_success "成功上传 $mc_version 版本到 Modrinth (ID: $version_id)"
        else
            local error_message=$(echo "$response" | jq -r '.error // .message // "未知错误"' 2>/dev/null || echo "$response")
            log_error "上传到 Modrinth 失败：$error_message"
            log_error "API 完整响应：$response"
        fi
    done

    log_success "Modrinth 上传流程结束"
}
# --------------------------- 清理临时文件 ----------------------------------
cleanup() {
    log_info "清理临时文件..."
    rm -f build/publish_*.gradle.kts
    
    # 恢复 stonecutter 配置
    git checkout stonecutter.gradle.kts 2>/dev/null || true
    
    log_success "清理完成"
}

# --------------------------- 主函数 ----------------------------------------
main() {
    echo "=============================================="
    echo "  Nebula Mod 自动化构建与发布工具"
    echo "=============================================="
    echo ""
    
    # 切换到脚本所在目录（假设脚本在项目根目录）
    cd "$(dirname "$0")" || exit 1
    
    # 初始化
    check_environment
    get_version_info "$1"
    
    # 询问是否手动输入更新描述
    if [ -t 0 ]; then  # 如果是交互模式
        echo ""
        read -p "是否要手动输入更新描述？[y/N] " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            collect_changelog_input || true
        fi
    fi
    
    generate_changelog
    
    # 构建
    build_all_versions
    
    # 发布
    upload_to_github
    upload_to_modrinth
    
    # 清理
    cleanup
    
    echo ""
    echo "=============================================="
    log_success "所有任务完成!"
    echo "=============================================="
    echo ""
    echo "构建产物汇总:"
    if [ -n "$GITHUB_REPOSITORY" ]; then
        echo "  - GitHub Releases: https://github.com/${GITHUB_REPOSITORY}/releases/tag/v${VERSION}"
    fi
    local modrinth_id=$(grep "^publish.modrinth=" gradle.properties | cut -d'=' -f2)
    if [ -n "$modrinth_id" ] && [ "$modrinth_id" != "# Modrinth mod slug" ]; then
        echo "  - Modrinth: https://modrinth.com/mod/${modrinth_id}/version/${VERSION}"
    fi
    echo "  - 本地文件：$(pwd)/build/releases/"
    echo "  - 更新日志：$(pwd)/build/changelog.md"
    echo ""
}

# 执行主函数
main "$@"
