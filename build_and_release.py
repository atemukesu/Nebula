#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
=============================================================================
Nebula Mod 自动化构建与发布脚本 (Python 版)
=============================================================================
功能:
  1. 自动编译 1.20.1 和 1.21.1 双版本
  2. 自动填写版本号和依赖要求并生成 Markdown 更新日志
  3. 可选：上传构建产物到 GitHub Releases [Y/n]
  4. 可选：上传构建产物到 Modrinth [Y/n]

使用方法:
  python build_and_release.py [版本号]
=============================================================================
"""

import os
import sys
import glob
import json
import shutil
import hashlib
import subprocess
from datetime import datetime

# --------------------------- 颜色输出 --------------------------------------
class Colors:
    RED = '\033[0;31m'
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    NC = '\033[0m' # No Color

def log_info(msg):    print(f"{Colors.BLUE}[INFO]{Colors.NC} {msg}")
def log_success(msg): print(f"{Colors.GREEN}[SUCCESS]{Colors.NC} {msg}")
def log_warning(msg): print(f"{Colors.YELLOW}[WARNING]{Colors.NC} {msg}")
def log_error(msg):   print(f"{Colors.RED}[ERROR]{Colors.NC} {msg}")

# --------------------------- 辅助函数 --------------------------------------
def ask_yes_no(prompt: str, default_yes=True) -> bool:
    """交互式询问 [Y/n]"""
    choices = "[Y/n]" if default_yes else "[y/N]"
    reply = input(f"\n{Colors.YELLOW}>>> {prompt} {choices}:{Colors.NC} ").strip().lower()
    if not reply:
        return default_yes
    return reply.startswith('y')

def run_cmd(cmd_list, capture=False, check=True):
    """运行命令行命令"""
    try:
        if capture:
            result = subprocess.run(cmd_list, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check)
            return result.stdout.strip()
        else:
            subprocess.run(cmd_list, check=check)
            return ""
    except subprocess.CalledProcessError as e:
        if check:
            log_error(f"命令执行失败: {' '.join(cmd_list)}")
            if capture:
                log_error(f"错误输出: {e.stderr}")
            sys.exit(1)
        return ""

def get_file_hash(filepath, algo='md5'):
    """计算文件的哈希值"""
    h = hashlib.new(algo)
    with open(filepath, 'rb') as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def get_file_size_mb(filepath):
    """获取文件大小 (MB)"""
    size_bytes = os.path.getsize(filepath)
    return f"{size_bytes / (1024 * 1024):.2f}M"

# --------------------------- 核心流程 --------------------------------------

def load_env_file():
    """加载 .env 文件到环境变量"""
    env_file = ".env"
    if os.path.isfile(env_file):
        log_info("检测到 .env 文件，正在加载...")
        with open(env_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                if '=' in line:
                    key, value = line.split('=', 1)
                    value = value.strip(' "\'')
                    os.environ[key.strip()] = value
                    log_info(f"已加载：{key.strip()}")
        log_success(".env 文件加载完成")
    else:
        log_info("未找到 .env 文件，将使用系统环境变量")

def check_environment():
    """检查环境依赖"""
    log_info("检查环境依赖...")
    load_env_file()

    # 检查 Java
    if not shutil.which("java"):
        log_error("Java 未安装")
        sys.exit(1)
    
    java_ver_out = subprocess.run(["java", "-version"], stderr=subprocess.PIPE, text=True).stderr
    first_line = java_ver_out.splitlines()[0] if java_ver_out else ""
    log_info(f"Java 版本：{first_line}")

    # 检查 Gradle
    global GRADLEW
    GRADLEW = "./gradlew" if os.name != 'nt' else "gradlew.bat"
    if not os.path.isfile(GRADLEW):
        log_error(f"{GRADLEW} 不存在，请确保在项目根目录运行此脚本")
        sys.exit(1)

    # 检查 Git
    if not shutil.which("git"):
        log_error("Git 未安装")
        sys.exit(1)

    log_success("环境检查完成")

def get_version_info():
    """获取版本、日期和 Git 提交信息"""
    global VERSION, BASE_VERSION, GIT_HASH, BUILD_DATE
    
    # 从 gradle.properties 读取基础版本号
    BASE_VERSION = "unknown"
    if os.path.isfile("gradle.properties"):
        with open("gradle.properties", "r", encoding='utf-8') as f:
            for line in f:
                if line.startswith("mod.version="):
                    BASE_VERSION = line.split("=", 1)[1].strip()
                    break

    # 命令行参数覆盖
    if len(sys.argv) > 1:
        VERSION = sys.argv[1]
        log_info(f"使用指定的版本号：{VERSION}")
    else:
        VERSION = BASE_VERSION
        log_info(f"使用默认版本号：{VERSION}")

    GIT_HASH = run_cmd(["git", "rev-parse", "--short", "HEAD"], capture=True)
    BUILD_DATE = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    
    log_info(f"构建日期：{BUILD_DATE}")
    log_info(f"Git 提交：{GIT_HASH}")

def collect_changelog_input():
    """收集用户手动输入的更新描述"""
    changelog_file = os.path.join("build", "manual_changelog.md")
    os.makedirs("build", exist_ok=True)
    
    if not ask_yes_no("是否要手动输入更新描述？", default_yes=False):
        return
        
    print("\n请输入本版本的主要更新内容（每行一条，直接按回车结束输入）:")
    print("例如：")
    print("  - 修复了粒子渲染的 bug")
    print("  - 优化了 NBL 流式加载性能\n")
    
    updates = []
    while True:
        line = input("> ").strip()
        if not line:
            break
        updates.append(line)
        
    if not updates:
        log_info("未输入更新内容，将仅使用 Git 提交记录")
        return
        
    with open(changelog_file, "w", encoding='utf-8') as f:
        f.write("## 主要更新\n\n")
        for u in updates:
            f.write(f"{u}\n")
            
    log_success(f"已记录 {len(updates)} 条更新内容")

def build_version(mc_version):
    """执行单个版本的 Gradle 构建"""
    log_info("==========================================")
    log_info(f"开始构建 Minecraft {mc_version} 版本")
    log_info("==========================================")
    
    # 清理并构建
    log_info("清理旧的构建文件...")
    run_cmd([GRADLEW, "clean", "--no-daemon"])
    
    log_info(f"编译 {mc_version} 版本...")
    run_cmd([GRADLEW, f":{mc_version}:build", "--no-daemon"])
    
    # 复制构建产物
    out_dir = os.path.join("build", "releases", mc_version)
    os.makedirs(out_dir, exist_ok=True)
    
    source_dir = os.path.join("versions", mc_version, "build", "libs")
    jars_found = glob.glob(os.path.join(source_dir, "*.jar"))
    
    for jar in jars_found:
        shutil.copy(jar, out_dir)
        
    target_jar = os.path.join(out_dir, f"nebula-{VERSION}+{mc_version}.jar")
    if os.path.isfile(target_jar):
        log_success(f"{mc_version} 版本构建成功: {os.path.basename(target_jar)}")
    else:
        log_error(f"{mc_version} 版本构建失败，找不到预期的输出文件: {target_jar}")
        sys.exit(1)

def generate_changelog():
    """生成最终的 Markdown 更新日志"""
    log_info("生成更新日志...")
    changelog_file = os.path.join("build", "changelog.md")
    manual_changelog = os.path.join("build", "manual_changelog.md")
    
    prev_tag = run_cmd(["git", "describe", "--tags", "--abbrev=0"], capture=True, check=False)
    
    with open(changelog_file, "w", encoding='utf-8') as f:
        f.write(f"# Nebula v{VERSION}\n\n")
        f.write("## 发布信息\n")
        f.write(f"- **发布日期**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write(f"- **Git 提交**: {GIT_HASH}\n")
        if prev_tag:
            f.write(f"- **上一版本**: {prev_tag}\n")
        f.write("\n")
        
        # 手动更新内容
        if os.path.isfile(manual_changelog):
            with open(manual_changelog, "r", encoding='utf-8') as mf:
                f.write(mf.read())
            f.write("\n")
            
        # Git Commit 记录
        f.write("## 详细提交记录\n\n")
        if prev_tag:
            f.write(f"### 自上版本 {prev_tag} 以来的变更\n")
            commits = run_cmd(["git", "log", f"--pretty=format:- %s (%h)", f"{prev_tag}..HEAD"], capture=True, check=False)
        else:
            f.write("### 首次发布\n")
            commits = run_cmd(["git", "log", "--pretty=format:- %s (%h)", "-20"], capture=True, check=False)
        f.write((commits if commits else "- 暂无详细记录") + "\n\n")
        
        # PR 记录
        if shutil.which("gh"):
            prs = run_cmd(["gh", "pr", "list", "--state", "merged", "--limit", "10", 
                           "--json", "number,title,author", 
                           "--template", "{{range .}}- #{{.number}} {{.title}} (by @{{.author.login}})\n{{end}}"], 
                           capture=True, check=False)
            if prs.strip():
                f.write("### 合并的 Pull Requests\n")
                f.write(prs + "\n\n")
                
        # 兼容性说明
        f.write("""---

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
""")
        # 文件列表
        for mc_ver in ["1.20.1", "1.21.1"]:
            jar_file = os.path.join("build", "releases", mc_ver, f"nebula-{VERSION}+{mc_ver}.jar")
            if os.path.isfile(jar_file):
                size = get_file_size_mb(jar_file)
                md5 = get_file_hash(jar_file, 'md5')
                sha256 = get_file_hash(jar_file, 'sha256')
                f.write(f"- `nebula-{VERSION}+{mc_ver}.jar` ({size})\n")
                f.write(f"  - MD5: `{md5}`\n")
                f.write(f"  - SHA256: `{sha256}`\n")
                
    log_success(f"更新日志已生成：{changelog_file}")

def upload_to_github():
    """发布到 GitHub Releases"""
    if not ask_yes_no("是否要将构建产物发布到 GitHub Releases？", default_yes=True):
        log_info("已跳过 GitHub Releases 上传。")
        return

    gh_repo = os.environ.get("GITHUB_REPOSITORY")
    if not os.environ.get("GITHUB_TOKEN") or not gh_repo:
        log_warning("缺少 GITHUB_TOKEN 或 GITHUB_REPOSITORY 环境变量，无法上传。")
        return
        
    if not shutil.which("gh"):
        log_warning("未安装 GitHub CLI (gh)，跳过上传。")
        return

    log_info("==========================================")
    log_info("开始上传到 GitHub Releases")
    log_info("==========================================")
    
    release_tag = f"v{VERSION}"
    changelog_file = os.path.join("build", "changelog.md")
    
    # 检查删除旧 Release
    check_exists = run_cmd(["gh", "release", "view", release_tag], capture=True, check=False)
    if "title:" in check_exists:
        log_warning(f"Release {release_tag} 已存在，正在删除旧版本...")
        run_cmd(["gh", "release", "delete", release_tag, "--cleanup-tag", "--yes"], check=False)
        
    # 收集需要上传的文件
    upload_files = []
    for jar_file in glob.glob(os.path.join("build", "releases", "*", "nebula-*.jar")):
        upload_files.append(jar_file)
        
    # 创建 Release
    cmd = ["gh", "release", "create", release_tag, 
           "--repo", gh_repo, 
           "--title", f"Nebula {release_tag}",
           "--notes-file", changelog_file] + upload_files
           
    run_cmd(cmd)
    log_success("成功上传到 GitHub Releases")
    log_info(f"Release 链接：https://github.com/{gh_repo}/releases/tag/{release_tag}")

def upload_to_modrinth():
    """发布到 Modrinth"""
    if not ask_yes_no("是否要将构建产物发布到 Modrinth？", default_yes=True):
        log_info("已跳过 Modrinth 上传。")
        return

    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        log_warning("缺少 MODRINTH_TOKEN 环境变量，无法上传。")
        return
        
    if not shutil.which("curl"):
        log_warning("系统未安装 curl，跳过上传。")
        return

    log_info("==========================================")
    log_info("开始上传到 Modrinth")
    log_info("==========================================")
    
    # 获取 Modrinth ID
    modrinth_id = ""
    if os.path.isfile("gradle.properties"):
        with open("gradle.properties", "r", encoding='utf-8') as f:
            for line in f:
                if line.startswith("publish.modrinth="):
                    modrinth_id = line.split("=", 1)[1].strip()
                    break
                    
    if not modrinth_id or modrinth_id == "# Modrinth mod slug":
        log_error("未在 gradle.properties 中设置 publish.modrinth (项目ID)")
        return
        
    log_info(f"Modrinth 项目 ID: {modrinth_id}")
    with open(os.path.join("build", "changelog.md"), "r", encoding='utf-8') as f:
        changelog_content = f.read()

    for mc_version in ["1.20.1", "1.21.1"]:
        jar_file = os.path.join("build", "releases", mc_version, f"nebula-{VERSION}+{mc_version}.jar")
        if not os.path.isfile(jar_file):
            continue
            
        version_name = f"{VERSION}-{mc_version}"
        log_info(f"上传 {mc_version} 版本到 Modrinth...")
        
        # 构建 Modrinth 所需的 JSON
        data_dict = {
            "name": version_name,
            "version_number": version_name,
            "version_type": "release",
            "project_id": modrinth_id,
            "file_parts": ["file"],
            "primary_file": "file",
            "dependencies": [
                {"project_id": "P7dR8mSH", "dependency_type": "required"},
                {"project_id": "mOgUt4GM", "dependency_type": "required"},
                {"project_id": "1eAoo2KR", "dependency_type": "required"},
                {"project_id": "RSFrpoou", "dependency_type": "required"}
            ],
            "loaders": ["fabric"],
            "game_versions": [mc_version],
            "featured": True,
            "status": "listed",
            "description": f"Nebula {VERSION} for Minecraft {mc_version}",
            "changelog": changelog_content
        }
        
        # 将 JSON 转为紧凑的字符串格式
        json_str = json.dumps(data_dict, separators=(',', ':'))
        
        # 使用 curl 进行多表单上传 (等同于 bash 中的实现，无需安装 requests)
        curl_cmd = [
            "curl", "-s", "-X", "POST",
            "-H", f"Authorization: {token}",
            "-F", f"data={json_str}",
            "-F", f"file=@{jar_file}",
            "https://api.modrinth.com/v2/version"
        ]
        
        response = run_cmd(curl_cmd, capture=True, check=False)
        
        try:
            resp_json = json.loads(response)
            if "id" in resp_json:
                log_success(f"成功上传 {mc_version} 版本到 Modrinth (ID: {resp_json['id']})")
            else:
                error_msg = resp_json.get("error", resp_json.get("message", "未知错误"))
                log_error(f"上传失败：{error_msg}")
        except json.JSONDecodeError:
            log_error(f"解析 Modrinth 响应失败: {response}")

def cleanup():
    """清理临时文件"""
    log_info("清理临时文件...")
    for f in glob.glob("build/publish_*.gradle.kts"):
        try:
            os.remove(f)
        except OSError:
            pass
            
    run_cmd(["git", "checkout", "stonecutter.gradle.kts"], check=False)
    log_success("清理完成")

# --------------------------- 主程序 ----------------------------------------
def main():
    print("==============================================")
    print("  Nebula Mod 自动化构建与发布工具 (Python)")
    print("==============================================")
    
    # 确保在脚本所在目录执行
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    check_environment()
    get_version_info()
    collect_changelog_input()
    generate_changelog()
    
    # 构建
    build_version("1.20.1")
    build_version("1.21.1")
    log_success("所有版本构建完成!")
    
    # 交互式发布
    print("\n==============================================")
    log_info("即将进入发布阶段")
    print("==============================================")
    upload_to_github()
    upload_to_modrinth()
    
    cleanup()
    
    print("\n==============================================")
    log_success("所有任务完成!")
    print("==============================================\n")
    print("构建产物汇总:")
    
    gh_repo = os.environ.get("GITHUB_REPOSITORY")
    if gh_repo:
        print(f"  - GitHub Releases: https://github.com/{gh_repo}/releases/tag/v{VERSION}")
        
    print(f"  - 本地文件：{os.path.abspath(os.path.join('build', 'releases'))}")
    print(f"  - 更新日志：{os.path.abspath(os.path.join('build', 'changelog.md'))}\n")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n用户取消了操作。")
        sys.exit(0)