# =============================================================================
# Nebula Mod 自动化构建与发布脚本 (PowerShell 版本)
# =============================================================================
# 功能:
#   1. 自动编译 1.20.1 和 1.21.1 双版本
#   2. 上传构建产物到 GitHub Releases
#   3. 上传构建产物到 Modrinth
#   4. 自动填写版本号和依赖要求
#
# 使用方法:
#   .\build_and_release.ps1 [版本号]
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

# 遇到错误立即退出
$ErrorActionPreference = "Stop"

# --------------------------- 颜色输出 --------------------------------------
function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

# --------------------------- 加载环境变量 ----------------------------------
function Load-EnvFile {
    $envFile = ".env"
    
    if (Test-Path $envFile) {
        Write-Info "检测到 .env 文件，正在加载..."
        
        # 读取 .env 文件并设置环境变量
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            
            # 跳过注释和空行
            if ($line -match "^#" -or [string]::IsNullOrEmpty($line)) {
                return
            }
            
            # 解析键值对
            if ($line -match '^([^=]+)=(.*)$') {
                $key = $matches[1].Trim()
                $value = $matches[2].Trim()
                
                # 去除引号
                $value = $value -replace '^["'']|["'']$', ''
                
                # 设置环境变量
                [Environment]::SetEnvironmentVariable($key, $value, "Process")
                Write-Info "已加载：$key"
            }
        }
        
        Write-Success ".env 文件加载完成"
    } else {
        Write-Info "未找到 .env 文件，将使用系统环境变量"
    }
}

# --------------------------- 环境检查 --------------------------------------
function Check-Environment {
    Write-Info "检查环境依赖..."
    
    # 首先尝试加载 .env 文件
    Load-EnvFile
    
    # 检查 Java
    try {
        $javaVersion = java -version 2>&1 | Select-String "version" | Select-Object -First 1
        if ($javaVersion) {
            Write-Info "Java 版本：$javaVersion"
        } else {
            Write-Error-Custom "Java 未安装，请安装 Java 21"
            exit 1
        }
    } catch {
        Write-Error-Custom "Java 未安装，请安装 Java 21"
        exit 1
    }
    
    # 检查 Gradle
    if (-not (Test-Path ".\gradlew.bat")) {
        Write-Error-Custom "gradlew.bat 不存在，请确保在项目根目录运行此脚本"
        exit 1
    }
    
    # 检查 Git
    try {
        git --version | Out-Null
    } catch {
        Write-Error-Custom "Git 未安装"
        exit 1
    }
    
    # 检查必要的环境变量
    if ([string]::IsNullOrEmpty($env:GITHUB_TOKEN)) {
        Write-Warning "GITHUB_TOKEN 未设置，将跳过 GitHub Releases 上传"
    }
    
    if ([string]::IsNullOrEmpty($env:MODRINTH_TOKEN)) {
        Write-Warning "MODRINTH_TOKEN 未设置，将跳过 Modrinth 上传"
    }
    
    if ([string]::IsNullOrEmpty($env:GITHUB_REPOSITORY)) {
        Write-Warning "GITHUB_REPOSITORY 未设置，将跳过 GitHub Releases 上传"
    }
    
    Write-Success "环境检查完成"
}

# --------------------------- 获取版本信息 ----------------------------------
function Get-VersionInfo {
    param([string]$CustomVersion)
    
    # 从 gradle.properties 读取基础版本号
    $gradleProps = Get-Content "gradle.properties"
    $baseVersion = ($gradleProps | Where-Object { $_ -match "^mod.version=" }) -replace "^mod.version=", ""
    
    # 如果提供了命令行参数，使用提供的版本号
    if (-not [string]::IsNullOrEmpty($CustomVersion)) {
        $script:VERSION = $CustomVersion
        Write-Info "使用指定的版本号：$script:VERSION"
    } else {
        $script:VERSION = $baseVersion
        Write-Info "使用默认版本号：$script:VERSION"
    }
    
    # 获取当前 Git 提交哈希
    $script:GIT_HASH = git rev-parse --short HEAD
    $script:BUILD_DATE = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
    
    Write-Info "构建日期：$script:BUILD_DATE"
    Write-Info "Git 提交：$script:GIT_HASH"
}

# --------------------------- 收集更新描述 ----------------------------------
function Collect-ChangelogInput {
    $changelogFile = "build/manual_changelog.md"
    
    Write-Info "=========================================="
    Write-Info "请输入更新内容（可选）"
    Write-Info "=========================================="
    Write-Host ""
    Write-Host "请输入本版本的主要更新内容（每行一条，输入空行结束）:"
    Write-Host "例如："
    Write-Host "  - 修复了粒子渲染的 bug"
    Write-Host "  - 优化了 NBL 流式加载性能"
    Write-Host "  - 添加了新的粒子效果类型"
    Write-Host ""
    
    $updates = @()
    while ($true) {
        $line = Read-Host ">"
        if ([string]::IsNullOrEmpty($line)) {
            break
        }
        $updates += $line
    }
    
    if ($updates.Count -eq 0) {
        Write-Info "未输入更新内容，将使用 Git 提交记录自动生成"
        return $false
    }
    
    # 写入临时文件
    New-Item -ItemType Directory -Force -Path "build" | Out-Null
    $content = @"
## 主要更新

"@
    $updates | ForEach-Object { $content += "$_`n" }
    Set-Content -Path $changelogFile -Value $content -Encoding UTF8
    
    Write-Success "已记录 $($updates.Count) 条更新内容"
    return $true
}

# --------------------------- 构建指定版本 ----------------------------------
function Build-Version {
    param(
        [string]$McVersion,
        [string]$VersionName
    )
    
    Write-Info "=========================================="
    Write-Info "开始构建 Minecraft $McVersion 版本"
    Write-Info "=========================================="
    
    # 激活对应版本
    Write-Info "激活 Stonecutter 版本：$McVersion"
    
    # 清理之前的构建
    Write-Info "清理旧的构建文件..."
    & .\gradlew.bat clean --no-daemon
    
    # 执行构建
    Write-Info "编译 $McVersion 版本..."
    & .\gradlew.bat ":${VersionName}:build" --no-daemon
    
    # 复制构建产物
    Write-Info "复制构建产物..."
    New-Item -ItemType Directory -Force -Path "build/releases/$McVersion" | Out-Null
    Get-ChildItem "versions/$McVersion/build/libs/*.jar" | Copy-Item -Destination "build/releases/$McVersion/" -ErrorAction SilentlyContinue
    
    # 验证构建产物
    $expectedFile = "build/releases/$McVersion/nebula-${script:VERSION}+${McVersion}.jar"
    if (Test-Path $expectedFile) {
        Write-Success "$McVersion 版本构建成功"
        Get-ChildItem "build/releases/$McVersion/*.jar" | Format-Table Name, Length -AutoSize
    } else {
        Write-Error-Custom "$McVersion 版本构建失败，找不到输出文件"
        exit 1
    }
}

# --------------------------- 构建所有版本 ----------------------------------
function Build-AllVersions {
    Write-Info "=========================================="
    Write-Info "开始构建所有版本"
    Write-Info "=========================================="
    
    # 构建 1.20.1 版本
    Build-Version -McVersion "1.20.1" -VersionName "1.20.1"
    
    # 构建 1.21.1 版本
    Build-Version -McVersion "1.21.1" -VersionName "1.21.1"
    
    Write-Success "所有版本构建完成!"
    Write-Info "构建产物位置:"
    Get-ChildItem -Path "build/releases" -Filter "*.jar" -Recurse | Select-Object FullName
}

# --------------------------- 生成更新日志 ----------------------------------
function Generate-Changelog {
    $changelogFile = "build/changelog.md"
    $manualChangelog = "build/manual_changelog.md"
    
    Write-Info "生成更新日志..."
    
    # 尝试获取上一个版本的 tag
    $previousTag = git describe --tags --abbrev=0 2>$null
    if (-not $?) {
        $previousTag = ""
    }
    
    # 创建更新日志头部
    $content = @"
# Nebula v${script:VERSION}

## 发布信息
- **发布日期**: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
- **Git 提交**: ${script:GIT_HASH}
"@
    
    if (-not [string]::IsNullOrEmpty($previousTag)) {
        $content += "`n- **上一版本**: ${previousTag}"
    }
    
    $content += "`n"
    
    # 如果有手动输入的更新内容，优先使用
    if (Test-Path $manualChangelog) {
        $content += Get-Content $manualChangelog -Raw
        $content += "`n"
    }
    
    # 添加 Git 提交记录
    $content += @"

## 详细提交记录

"@
    
    if (-not [string]::IsNullOrEmpty($previousTag)) {
        $content += "### 自上版本 ${previousTag} 以来的变更`n"
        $gitLog = git log --pretty=format:"- %s (%h)" "${previousTag}..HEAD" 2>$null
        if ($?) {
            $content += $gitLog + "`n"
        } else {
            $content += "- 暂无详细记录`n"
        }
    } else {
        $content += "### 首次发布`n"
        $gitLog = git log --pretty=format:"- %s (%h)" -20 2>$null
        if ($?) {
            $content += $gitLog + "`n"
        } else {
            $content += "- 暂无详细记录`n"
        }
    }
    
    $content += "`n`n"
    
    # 添加 PR 信息（如果有 gh 工具）
    try {
        $prList = gh pr list --state merged --limit 10 --json number,title,author 2>$null
        if ($prList) {
            $content += "### 合并的 Pull Requests`n"
            $prs = $prList | ConvertFrom-Json
            foreach ($pr in $prs) {
                $content += "- #$( $pr.number ) $( $pr.title ) (by @$( $pr.author.login ))`n"
            }
            $content += "`n"
        }
    } catch {
        # gh 工具不可用或没有 PR
    }
    
    # 添加版本兼容性信息
    $content += @"
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
"@
    
    # 列出将要上传的文件
    foreach ($mcVersion in @("1.20.1", "1.21.1")) {
        $jarFile = "build/releases/${mcVersion}/nebula-${script:VERSION}+${mcVersion}.jar"
        if (Test-Path $jarFile) {
            $fileInfo = Get-Item $jarFile
            $fileSize = "{0:N2} MB" -f ($fileInfo.Length / 1MB)
            
            # 计算 MD5
            $md5Hash = (Get-FileHash $jarFile -Algorithm MD5).Hash.ToLower()
            # 计算 SHA256
            $sha256Hash = (Get-FileHash $jarFile -Algorithm SHA256).Hash.ToLower()
            
            $content += "- \`nebula-${script:VERSION}+${mcVersion}.jar\` (${fileSize})`n"
            $content += "  - MD5: \`${md5Hash}\``n"
            $content += "  - SHA256: \`${sha256Hash}\``n"
        }
    }
    
    Set-Content -Path $changelogFile -Value $content -Encoding UTF8
    
    Write-Success "更新日志已生成：$changelogFile"
    Write-Info "更新日志预览（前 40 行）:"
    Write-Host "=========================================="
    Get-Content $changelogFile | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
    Write-Host "=========================================="
}

# --------------------------- 上传到 GitHub Releases ------------------------
function Upload-ToGitHub {
    if ([string]::IsNullOrEmpty($env:GITHUB_TOKEN) -or [string]::IsNullOrEmpty($env:GITHUB_REPOSITORY)) {
        Write-Warning "缺少 GITHUB_TOKEN 或 GITHUB_REPOSITORY，跳过 GitHub 上传"
        return
    }
    
    Write-Info "=========================================="
    Write-Info "开始上传到 GitHub Releases"
    Write-Info "=========================================="
    
    $releaseTag = "v${script:VERSION}"
    $releaseName = "Nebula v${script:VERSION}"
    $changelogFile = "build/changelog.md"
    
    # 创建 Release
    Write-Info "创建 GitHub Release: $releaseTag"
    
    # 检查 Release 是否已存在
    try {
        gh release view $releaseTag 2>$null
        if ($?) {
            Write-Warning "Release $releaseTag 已存在，删除旧版本..."
            gh release delete $releaseTag --cleanup-tag --yes 2>$null
        }
    } catch {
        # Release 不存在，继续
    }
    
    # 准备上传的文件列表
    $filesToUpload = Get-ChildItem -Path "build/releases" -Filter "nebula-*.jar" -Recurse | 
                     Select-Object -ExpandProperty FullName
    
    # 创建 Release 并上传文件
    try {
        & gh release create $releaseTag `
            --repo $env:GITHUB_REPOSITORY `
            --title $releaseName `
            --notes-file $changelogFile `
            @filesToUpload
        
        Write-Success "成功上传到 GitHub Releases"
        Write-Info "Release 链接：https://github.com/${env:GITHUB_REPOSITORY}/releases/tag/${releaseTag}"
    } catch {
        Write-Error-Custom "GitHub Release 创建失败"
        Write-Error-Custom $_.Exception.Message
        return
    }
}

# --------------------------- 上传到 Modrinth -------------------------------
function Upload-ToModrinth {
    if ([string]::IsNullOrEmpty($env:MODRINTH_TOKEN)) {
        Write-Warning "缺少 MODRINTH_TOKEN，跳过 Modrinth 上传"
        return
    }
    
    Write-Info "=========================================="
    Write-Info "开始上传到 Modrinth"
    Write-Info "=========================================="
    
    # 严格清除可能存在的 Windows \r 换行符
    $gradleProps = Get-Content "gradle.properties"
    $modrinthId = ($gradleProps | Where-Object { $_ -match "^publish.modrinth=" }) -replace "^publish.modrinth=", ""
    $modrinthId = $modrinthId.Trim("`r`n ")
    
    if ([string]::IsNullOrEmpty($modrinthId) -or $modrinthId -eq "# Modrinth mod slug") {
        Write-Error-Custom "未在 gradle.properties 中设置 publish.modrinth"
        exit 1
    }
    
    Write-Info "Modrinth 项目 ID: $modrinthId"
    $changelogFile = "build/changelog.md"
    $changelogContent = Get-Content $changelogFile -Raw
    
    foreach ($mcVersion in @("1.20.1", "1.21.1")) {
        $jarFile = "build/releases/${mcVersion}/nebula-${script:VERSION}+${mcVersion}.jar"
        
        if (-not (Test-Path $jarFile)) {
            Write-Error-Custom "找不到文件：$jarFile"
            continue
        }
        
        Write-Info "上传 $mcVersion 版本到 Modrinth..."
        $versionName = "${script:VERSION}-${mcVersion}"
        
        # 构建 JSON 数据
        $jsonData = @{
            name = $versionName
            version_number = $versionName
            version_type = "release"
            project_id = $modrinthId
            file_parts = @("file")
            primary_file = "file"
            dependencies = @(
                @{
                    project_id = "P7dR8mSH"
                    dependency_type = "required"
                },
                @{
                    project_id = "mOgUt4GM"
                    dependency_type = "required"
                },
                @{
                    project_id = "1eAoo2KR"
                    dependency_type = "required"
                },
                @{
                    project_id = "RSFrpoou"
                    dependency_type = "required"
                }
            )
            loaders = @("fabric")
            game_versions = @($mcVersion)
            featured = $true
            status = "listed"
            description = "Nebula ${script:VERSION} for Minecraft ${mcVersion}"
            changelog = $changelogContent
        }
        
        $jsonString = $jsonData | ConvertTo-Json -Depth 10 -Compress
        
        Write-Info "发送请求到 Modrinth API..."
        
        try {
            # 使用固定的 "file" 作为 form part name
            $boundary = [System.Guid]::NewGuid().ToString()
            $LF = "`r`n"
            
            $bodyLines = @(
                "--$boundary",
                "Content-Disposition: form-data; name=`"data`"",
                "",
                $jsonString,
                "--$boundary",
                "Content-Disposition: form-data; name=`"file`"; filename=`"$(Split-Path $jarFile -Leaf)`"",
                "Content-Type: application/java-archive",
                "",
                (Get-Content $jarFile -Encoding Byte),
                "--$boundary--"
            )
            
            $body = $bodyLines -join $LF
            
            $headers = @{
                "Authorization" = $env:MODRINTH_TOKEN
                "Content-Type" = "multipart/form-data; boundary=$boundary"
            }
            
            $response = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/version" `
                                         -Method Post `
                                         -Headers $headers `
                                         -Body $body
            
            if ($response.id) {
                Write-Success "成功上传 $mcVersion 版本到 Modrinth (ID: $($response.id))"
            } else {
                Write-Error-Custom "上传到 Modrinth 失败：未知错误"
                Write-Error-Custom "API 完整响应：$response"
            }
        } catch {
            Write-Error-Custom "上传到 Modrinth 失败：$($_.Exception.Message)"
            if ($_.ErrorDetails.Message) {
                Write-Error-Custom "API 完整响应：$($_.ErrorDetails.Message)"
            }
        }
    }
    
    Write-Success "Modrinth 上传流程结束"
}

# --------------------------- 清理临时文件 ----------------------------------
function Cleanup {
    Write-Info "清理临时文件..."
    
    Remove-Item -Path "build/publish_*.gradle.kts" -ErrorAction SilentlyContinue
    
    # 恢复 stonecutter 配置
    git checkout stonecutter.gradle.kts 2>$null
    
    Write-Success "清理完成"
}

# --------------------------- 主函数 ----------------------------------------
function Main {
    param([string]$Version)
    
    Write-Host "==============================================" -ForegroundColor Cyan
    Write-Host "  Nebula Mod 自动化构建与发布工具" -ForegroundColor Cyan
    Write-Host "==============================================" -ForegroundColor Cyan
    Write-Host ""
    
    # 切换到脚本所在目录
    Set-Location $PSScriptRoot
    
    # 初始化
    Check-Environment
    Get-VersionInfo -CustomVersion $Version
    
    # 询问是否手动输入更新描述
    if ($Host.Name -match "Console") {
        Write-Host ""
        $response = Read-Host "是否要手动输入更新描述？[y/N]"
        if ($response -match "^[Yy]$") {
            Collect-ChangelogInput | Out-Null
        }
    }
    
    Generate-Changelog
    
    # 构建
    Build-AllVersions
    
    # 发布
    Upload-ToGitHub
    Upload-ToModrinth
    
    # 清理
    Cleanup
    
    Write-Host ""
    Write-Host "==============================================" -ForegroundColor Green
    Write-Success "所有任务完成!"
    Write-Host "==============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "构建产物汇总:"
    
    if (-not [string]::IsNullOrEmpty($env:GITHUB_REPOSITORY)) {
        Write-Host "  - GitHub Releases: https://github.com/${env:GITHUB_REPOSITORY}/releases/tag/v${script:VERSION}"
    }
    
    $gradleProps = Get-Content "gradle.properties"
    $modrinthId = ($gradleProps | Where-Object { $_ -match "^publish.modrinth=" }) -replace "^publish.modrinth=", ""
    $modrinthId = $modrinthId.Trim("`r`n ")
    
    if (-not [string]::IsNullOrEmpty($modrinthId) -and $modrinthId -ne "# Modrinth mod slug") {
        Write-Host "  - Modrinth: https://modrinth.com/mod/${modrinthId}/version/${script:VERSION}"
    }
    
    Write-Host "  - 本地文件：$(Get-Location)\build\releases\"
    Write-Host "  - 更新日志：$(Get-Location)\build\changelog.md"
    Write-Host ""
}

# 执行主函数
Main -Version $args[0]
