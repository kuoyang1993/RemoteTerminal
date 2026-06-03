# ============================================================
#  RemoteTerminal - Windows 安装程序构建脚本
#  1. mvn package  →  thin jar + target/lib/ 平铺依赖
#  2. jlink        →  自定义运行时 (JDK + JavaFX 模块)
#  3. jpackage     →  MSI 标准安装包 (WiX)
#
#  前提: JDK 17+, Maven, WiX Toolset 3.x
#        JavaFX SDK 已解压到 lib/javafx-sdk-17.0.14/
# ============================================================

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  RemoteTerminal - Build Windows Installer" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ============================================================
# 0. 环境检测
# ============================================================
Write-Host "[0/4] Checking environment..." -ForegroundColor Yellow

# --- JAVA_HOME ---
if (-not $env:JAVA_HOME) {
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($javaExe) {
        $env:JAVA_HOME = Split-Path (Split-Path $javaExe)
    } else {
        Write-Host "  ERROR: JAVA_HOME not set and java not found!" -ForegroundColor Red
        exit 1
    }
}
Write-Host "  JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green

$javaVer = & "$env:JAVA_HOME\bin\java" -version 2>&1 | Out-String
if ($javaVer -match 'version "([\d._]+)"') { $javaVer = $matches[1] }
Write-Host "  Java      = $javaVer" -ForegroundColor Green
$Error.Clear()

# --- jpackage ---
$jpackageExe = "$env:JAVA_HOME\bin\jpackage.exe"
if (-not (Test-Path $jpackageExe)) {
    Write-Host "  ERROR: jpackage not found (need JDK 16+)" -ForegroundColor Red
    exit 1
}
Write-Host "  jpackage  = OK" -ForegroundColor Green

# --- WiX ---
$wixOk = $true
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) { $wixOk = $false }
if (-not (Get-Command light.exe   -ErrorAction SilentlyContinue)) { $wixOk = $false }
if (-not $wixOk) {
    Write-Host "  WiX       = NOT FOUND in PATH!" -ForegroundColor Red
    Write-Host "  => jpackage --type msi requires WiX Toolset 3.x" -ForegroundColor Red
    Write-Host "  => Try --type exe instead (edit line with '--type')" -ForegroundColor Red
    exit 1
}
Write-Host "  WiX       = OK" -ForegroundColor Green

# --- JavaFX SDK ---
$JFX_SDK = "lib\javafx-sdk-17.0.14"
if (-not (Test-Path "$JFX_SDK\lib\javafx.base.jar")) {
    Write-Host "  JavaFX SDK = NOT FOUND at $JFX_SDK" -ForegroundColor Red
    Write-Host "  Please run run.bat first, or download JavaFX SDK manually." -ForegroundColor Red
    exit 1
}
Write-Host "  JavaFX SDK = $JFX_SDK" -ForegroundColor Green

# --- Maven ---
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "  Maven     = NOT FOUND!" -ForegroundColor Red
    exit 1
}
Write-Host "  Maven      = OK" -ForegroundColor Green

# --- Icon ---
$ICON_FILE = "ssh.ico"
$hasIcon = Test-Path $ICON_FILE
Write-Host "  Icon       = $(if ($hasIcon) { $ICON_FILE } else { '(not found, will use default)' })" -ForegroundColor $(if ($hasIcon) { 'Green' } else { 'Yellow' })

Write-Host ""

# ============================================================
# 1. Maven 构建: thin jar + target/lib/ 平铺依赖
# ============================================================
Write-Host "[1/4] Maven package (thin jar + flat lib)..." -ForegroundColor Yellow

mvn clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ERROR: Maven build failed!" -ForegroundColor Red
    exit 1
}

$thinJar = Get-ChildItem "target\RemoteTerminal-*.jar" | Where-Object { $_.Name -notmatch 'sources|javadoc|shaded|original' } | Select-Object -First 1
if (-not $thinJar) {
    Write-Host "  ERROR: Thin jar not found in target/" -ForegroundColor Red
    exit 1
}
Write-Host "  Thin jar : $($thinJar.Name)" -ForegroundColor Green

$libCount = @(Get-ChildItem "target\lib\*.jar" -ErrorAction SilentlyContinue).Count
Write-Host "  Dep jars : $libCount jars in target/lib/" -ForegroundColor Green

# ============================================================
# 2. jlink: 自定义运行时 (JDK + JavaFX)
#    用 JavaFX SDK 的 modular JARs 做 module-path
#    jlink 兼容 modular JAR，不需要单独下载 jmods
# ============================================================
Write-Host "[2/4] jlink runtime (JDK + JavaFX)..." -ForegroundColor Yellow

$RUNTIME_DIR = "target\runtime"
if (Test-Path $RUNTIME_DIR) { Remove-Item -Recurse -Force $RUNTIME_DIR }

$JFX_LIB = "$JFX_SDK\lib"
$JDK_JMOS = "$env:JAVA_HOME\jmods"

$MODULES = @(
    "java.base",
    "java.desktop",
    "java.logging",
    "java.management",
    "java.naming",
    "java.security.jgss",
    "java.security.sasl",
    "java.sql",
    "java.xml",
    "java.xml.crypto",
    "java.net.http",
    "java.scripting",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "javafx.controls",
    "javafx.web",
    "javafx.base",
    "javafx.graphics",
    "javafx.fxml"
)
$moduleList = $MODULES -join ","

Write-Host "  jlink --module-path JDK-jmods + JavaFX-lib" -ForegroundColor Gray

& "$env:JAVA_HOME\bin\jlink.exe" `
    --output $RUNTIME_DIR `
    --module-path "$JDK_JMOS;$JFX_LIB" `
    --add-modules $moduleList `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 2>&1 | Out-Null

if ($LASTEXITCODE -ne 0) {
    Write-Host "  ERROR: jlink failed!" -ForegroundColor Red
    exit 1
}

# jlink 用 modular JAR 不会自动复制原生 DLL
# JavaFX 的 .dll 在 SDK/bin/ 下，需要手动搬到 runtime/bin/
Write-Host "  Copying JavaFX native DLLs..." -ForegroundColor Gray
$dllCount = 0
Get-ChildItem "$JFX_SDK\bin\*.dll" | ForEach-Object {
    Copy-Item $_.FullName -Destination "$RUNTIME_DIR\bin\" -Force
    $dllCount++
}
Write-Host "  Runtime ready ($dllCount native DLLs copied)" -ForegroundColor Green

# ============================================================
# 3. 准备 jpackage input 目录
# ============================================================
Write-Host "[3/4] Preparing jpackage input..." -ForegroundColor Yellow

$STAGING_DIR = "target\jpackage-input"
if (Test-Path $STAGING_DIR) { Remove-Item -Recurse -Force $STAGING_DIR }
New-Item -ItemType Directory -Path $STAGING_DIR -Force | Out-Null

# 复制 thin jar
Copy-Item $thinJar.FullName -Destination $STAGING_DIR

# 复制依赖 lib/ 目录 (不包含 JavaFX jars — 那些已在 runtime 里)
$STAGING_LIB = "$STAGING_DIR\lib"
New-Item -ItemType Directory -Path $STAGING_LIB -Force | Out-Null

Get-ChildItem "target\lib\*.jar" | ForEach-Object {
    # 跳过 JavaFX 自身的 jar (已在 jlink runtime 中)
    if ($_.Name -notmatch 'javafx-') {
        Copy-Item $_.FullName -Destination $STAGING_LIB
    }
}
$stagingCount = @(Get-ChildItem "$STAGING_LIB\*.jar").Count
Write-Host "  Input ready: thin jar + $stagingCount dep jars" -ForegroundColor Green

# ============================================================
# 4. jpackage: MSI 标准安装程序
# ============================================================
Write-Host "[4/4] jpackage --type msi (WiX)..." -ForegroundColor Yellow

$DIST_DIR = "target\dist"
if (Test-Path $DIST_DIR) { Remove-Item -Recurse -Force $DIST_DIR }

$jpackageArgs = @(
    "--name",             "RemoteTerminal",
    "--app-version",      "1.0.0",
    "--vendor",           "RemoteTerminal",
    "--description",      "SSH Remote Terminal Client",
    "--input",            $STAGING_DIR,
    "--main-jar",         $thinJar.Name,
    "--main-class",       "com.remoteterminal.Main",
    "--type",             "msi",
    "--runtime-image",    $RUNTIME_DIR,
    "--dest",             $DIST_DIR,
    "--win-dir-chooser",
    "--win-menu",
    "--win-menu-group",   "RemoteTerminal",
    "--win-shortcut",
    "--win-upgrade-uuid", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "--win-per-user-install"
)

if ($hasIcon) {
    $jpackageArgs += @("--icon", (Resolve-Path $ICON_FILE))
}

Write-Host "  Running jpackage..." -ForegroundColor Gray
& $jpackageExe @jpackageArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "  ERROR: jpackage failed!" -ForegroundColor Red
    exit 1
}

# ============================================================
# 完成
# ============================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  BUILD SUCCESS!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan

$msiFile = Get-ChildItem -Path $DIST_DIR -Filter "*.msi" | Select-Object -First 1
if ($msiFile) {
    $sizeMB = [math]::Round($msiFile.Length / 1MB, 2)
    Write-Host ""
    Write-Host "  Installer : $($msiFile.FullName)" -ForegroundColor White
    Write-Host "  Size      : $sizeMB MB" -ForegroundColor White
    Write-Host ""
    Write-Host "  Install with:" -ForegroundColor Gray
    Write-Host "    msiexec /i `"$($msiFile.FullName)`"" -ForegroundColor Gray
}

Write-Host ""
exit 0
