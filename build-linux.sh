#!/usr/bin/env bash
# ============================================================
#  RemoteTerminal - Linux 安装包构建脚本
#  1. 自动下载 Linux 版 JavaFX SDK
#  2. mvn package  →  thin jar + target/lib/ 平铺依赖
#  3. jlink        →  自定义运行时 (JDK + JavaFX Linux .so)
#  4. jpackage     →  .deb + .rpm + .tar.gz 通用安装包
#
#  用法:
#    chmod +x build-linux.sh
#    ./build-linux.sh                # 全量构建
#    ./build-linux.sh deb            # 仅构建 .deb
#    ./build-linux.sh rpm            # 仅构建 .rpm
#    ./build-linux.sh zip            # 仅构建 .tar.gz (AppImage)
#    ./build-linux.sh appimage       # 仅构建 AppDir + 启动脚本 (最通用)
#
#  前提: JDK 17+, Maven (或直接 mvnw)
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

APP_NAME="RemoteTerminal"
APP_VERSION="1.0.0"
MAIN_CLASS="com.remoteterminal.Main"
JAVAFX_VER="17.0.14"

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  $APP_NAME - Build Linux Installer${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

# ============================================================
# 0. 环境检测
# ============================================================
echo -e "${YELLOW}[0/5] Checking environment...${NC}"

# --- JAVA_HOME ---
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java 2>/dev/null) 2>/dev/null) 2>/dev/null) 2>/dev/null)
    if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
        echo -e "${RED}  ERROR: JAVA_HOME not set and java not found!${NC}"
        exit 1
    fi
fi
echo -e "${GREEN}  JAVA_HOME = $JAVA_HOME${NC}"

JAVA_VER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oP 'version "\K[\d._]+' || true)
echo -e "${GREEN}  Java      = $JAVA_VER${NC}"

# --- jpackage ---
if [ ! -f "$JAVA_HOME/bin/jpackage" ]; then
    echo -e "${RED}  ERROR: jpackage not found (need JDK 16+)${NC}"
    exit 1
fi
echo -e "${GREEN}  jpackage  = OK${NC}"

# --- Maven (优先 mvnw，其次 mvn) ---
if [ -f "./mvnw" ]; then
    MVN_CMD="./mvnw"
elif command -v mvn &>/dev/null; then
    MVN_CMD="mvn"
else
    echo -e "${RED}  ERROR: Maven not found! Install: sudo apt install maven${NC}"
    exit 1
fi
echo -e "${GREEN}  Maven     = $MVN_CMD${NC}"

# --- dpkg / rpm ---
HAS_DPKG=false
HAS_RPM=false
command -v dpkg-deb &>/dev/null && HAS_DPKG=true
command -v rpmbuild  &>/dev/null && HAS_RPM=true

echo -e "  dpkg      = $($HAS_DPKG && echo 'OK' || echo 'not found (skip .deb)')"
echo -e "  rpmbuild  = $($HAS_RPM && echo 'OK' || echo 'not found (skip .rpm)')"

echo ""

# ============================================================
# 1. 下载 Linux 版 JavaFX SDK（独立目录，不影响 Windows 构建）
# ============================================================
echo -e "${YELLOW}[1/5] JavaFX SDK (Linux) in lib/javafx-linux-sdk-${JAVAFX_VER}/ ...${NC}"

JAVAFX_DIR="lib/javafx-linux-sdk-${JAVAFX_VER}"
JAVAFX_LIB="${JAVAFX_DIR}/lib"

if [ ! -f "${JAVAFX_LIB}/javafx.base.jar" ]; then
    JAVAFX_URL="https://download2.gluonhq.com/openjfx/${JAVAFX_VER}/openjfx-${JAVAFX_VER}_linux-x64_bin-sdk.zip"
    JAVAFX_ZIP="lib/javafx-linux-${JAVAFX_VER}.zip"

    echo "  Downloading ${JAVAFX_URL} ..."
    mkdir -p lib/
    if command -v wget &>/dev/null; then
        wget -q --show-progress -O "$JAVAFX_ZIP" "$JAVAFX_URL" || { echo -e "${RED}  Download failed${NC}"; exit 1; }
    elif command -v curl &>/dev/null; then
        curl -L -o "$JAVAFX_ZIP" "$JAVAFX_URL" || { echo -e "${RED}  Download failed${NC}"; exit 1; }
    else
        echo -e "${RED}  ERROR: wget or curl required${NC}"
        exit 1
    fi

    echo "  Extracting..."
    mkdir -p "$JAVAFX_DIR"
    unzip -qo "$JAVAFX_ZIP" -d lib/
    # GluonHQ zip extracts to 'javafx-sdk-XX.XX' dir, rename to our naming
    EXTRACTED_DIR=$(find lib/ -maxdepth 1 -type d -name "javafx-sdk-*" | head -1)
    if [ -d "$EXTRACTED_DIR" ] && [ "$EXTRACTED_DIR" != "$JAVAFX_DIR" ]; then
        rm -rf "$JAVAFX_DIR"
        mv "$EXTRACTED_DIR" "$JAVAFX_DIR"
    fi
    rm -f "$JAVAFX_ZIP"

    echo -e "${GREEN}  JavaFX Linux SDK ready.${NC}"
else
    echo -e "${GREEN}  JavaFX Linux SDK already exists, skip.${NC}"
fi

# ============================================================
# 2. Maven 构建: thin jar + target/lib/ 平铺依赖
# ============================================================
echo -e "${YELLOW}[2/5] Maven package (thin jar + flat lib)...${NC}"

$MVN_CMD clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo -e "${RED}  ERROR: Maven build failed!${NC}"
    exit 1
fi

THIN_JAR=$(find target/ -maxdepth 1 -name "${APP_NAME}-*.jar" ! -name '*sources*' ! -name '*javadoc*' ! -name '*shaded*' ! -name '*original*' | head -1)
if [ -z "$THIN_JAR" ]; then
    echo -e "${RED}  ERROR: Thin jar not found in target/${NC}"
    exit 1
fi
THIN_JAR_NAME=$(basename "$THIN_JAR")
LIB_COUNT=$(find target/lib/ -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l)
echo -e "${GREEN}  Thin jar : $THIN_JAR_NAME${NC}"
echo -e "${GREEN}  Dep jars : $LIB_COUNT jars in target/lib/${NC}"

# ============================================================
# 3. jlink: 自定义 Linux 运行时 (JDK + JavaFX)
# ============================================================
echo -e "${YELLOW}[3/5] jlink runtime (JDK + JavaFX Linux)...${NC}"

RUNTIME_DIR="target/runtime-linux"
rm -rf "$RUNTIME_DIR"

JDK_JMODS="$JAVA_HOME/jmods"
if [ ! -d "$JDK_JMODS" ]; then
    echo -e "${RED}  ERROR: JDK jmods not found at $JDK_JMODS${NC}"
    echo "  Make sure you're using a full JDK (not JRE)."
    exit 1
fi

MODULES="java.base,java.desktop,java.logging,java.management,java.naming"
MODULES="${MODULES},java.security.jgss,java.security.sasl,java.sql"
MODULES="${MODULES},java.xml,java.xml.crypto,java.net.http,java.scripting"
MODULES="${MODULES},jdk.crypto.ec,jdk.unsupported"
MODULES="${MODULES},javafx.controls,javafx.web,javafx.base,javafx.graphics,javafx.fxml"
# Linux 需要额外的图形后端模块
MODULES="${MODULES},jdk.unsupported.desktop"

echo "  jlink --module-path JDK-jmods + JavaFX-Linux-lib"
"$JAVA_HOME/bin/jlink" \
    --output "$RUNTIME_DIR" \
    --module-path "$JDK_JMODS:${JAVAFX_LIB}" \
    --add-modules "$MODULES" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2 2>&1 | tail -1

if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo -e "${RED}  ERROR: jlink failed!${NC}"
    exit 1
fi

# 复制 JavaFX Linux 原生 .so 文件（jlink 用 modular JAR 不会自动复制）
echo "  Copying JavaFX Linux .so native libs..."
SO_COUNT=0
for f in "${JAVAFX_LIB}"/*.so; do
    [ -f "$f" ] || continue
    cp "$f" "$RUNTIME_DIR/lib/"
    SO_COUNT=$((SO_COUNT + 1))
done
echo -e "${GREEN}  Runtime ready ($SO_COUNT .so native libs)${NC}"

# ============================================================  
# 4. 准备 jpackage input 目录
# ============================================================
echo -e "${YELLOW}[4/5] Preparing jpackage input...${NC}"

STAGING_DIR="target/jpackage-input-linux"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/lib"

# 复制 thin jar
cp "$THIN_JAR" "$STAGING_DIR/"

# 复制依赖 jar（跳过 JavaFX — 已在 runtime 中）
STAGING_COUNT=0
for jar in target/lib/*.jar; do
    jar_name=$(basename "$jar")
    if [[ "$jar_name" != javafx-* ]]; then
        cp "$jar" "$STAGING_DIR/lib/"
        STAGING_COUNT=$((STAGING_COUNT + 1))
    fi
done
echo -e "${GREEN}  Input ready: thin jar + $STAGING_COUNT dep jars${NC}"

# ============================================================
# 5. jpackage：构建 Linux 安装包
# ============================================================
echo -e "${YELLOW}[5/5] jpackage ...${NC}"

DIST_DIR="target/dist-linux"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# 准备图标（.png 格式，Linux 不支持 .ico）
ICON_PNG="$SCRIPT_DIR/ssh.png"
if [ ! -f "$ICON_PNG" ]; then
    # 尝试从 ico 转换，或生成一个默认图标
    if [ -f "$SCRIPT_DIR/ssh.ico" ] && command -v convert &>/dev/null; then
        convert "$SCRIPT_DIR/ssh.ico" "$ICON_PNG" 2>/dev/null || true
    fi
    if [ ! -f "$ICON_PNG" ]; then
        echo -e "${YELLOW}  No ssh.png found, using default Java icon.${NC}"
        echo -e "${YELLOW}  Tip: put a 256x256 ssh.png in project root for a custom icon.${NC}"
        ICON_ARG=()
    else
        ICON_ARG=(--icon "$ICON_PNG")
    fi
else
    ICON_ARG=(--icon "$ICON_PNG")
fi

# --- 公共 jpackage 参数 ---
COMMON_ARGS=(
    --name             "$APP_NAME"
    --app-version      "$APP_VERSION"
    --vendor           "RemoteTerminal"
    --description      "SSH Remote Terminal Client"
    --input            "$STAGING_DIR"
    --main-jar         "$THIN_JAR_NAME"
    --main-class       "$MAIN_CLASS"
    --runtime-image    "$RUNTIME_DIR"
    --dest             "$DIST_DIR"
    --about-url        "https://github.com"
    "${ICON_ARG[@]}"
)

# --- 构建类型选择 ---
BUILD_TYPE="${1:-all}"

build_appimage() {
    echo ""
    echo -e "${YELLOW}  Building AppImage (AppDir + .tar.gz)...${NC}"
    
    APPIMAGE_DIR="$DIST_DIR/appimage"
    rm -rf "$APPIMAGE_DIR"
    
    "$JAVA_HOME/bin/jpackage" \
        "${COMMON_ARGS[@]}" \
        --type app-image \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" 2>&1 | tail -3

    # 创建启动脚本（放在 AppDir 根目录）
    APP_LAUNCH="$DIST_DIR/$APP_NAME/bin/$APP_NAME"
    if [ ! -f "$APP_LAUNCH" ]; then
        # jpackage 创建的 app-image 在子目录中
        APPIMAGE_BIN="$DIST_DIR/$APP_NAME/bin/$APP_NAME"
        # 找到实际位置
        ACTUAL_BIN=$(find "$DIST_DIR/$APP_NAME" -name "$APP_NAME" -type f -not -path "*/runtime/*" | head -1)
    fi

    # 打包为 .tar.gz（最通用的 Linux 安装格式）
    cd "$DIST_DIR"
    TARBALL="${APP_NAME}-${APP_VERSION}-linux-x64.tar.gz"
    tar -czf "$TARBALL" "$APP_NAME"
    cd "$SCRIPT_DIR"
    
    TAR_SIZE=$(du -h "$DIST_DIR/$TARBALL" 2>/dev/null | cut -f1)
    echo -e "${GREEN}  AppImage: $DIST_DIR/$TARBALL ($TAR_SIZE)${NC}"
    echo -e "${GREEN}  Usage: tar -xzf $TARBALL && ./$APP_NAME/bin/$APP_NAME${NC}"
}

build_deb() {
    if ! $HAS_DPKG; then
        echo -e "${YELLOW}  Skipping .deb (dpkg-deb not found). Install: sudo apt install binutils${NC}"
        return
    fi
    echo ""
    echo -e "${YELLOW}  Building .deb package...${NC}"

    "$JAVA_HOME/bin/jpackage" \
        "${COMMON_ARGS[@]}" \
        --type deb \
        --linux-package-name "remoteterminal" \
        --linux-shortcut \
        --linux-menu-group "Network;RemoteAccess;" \
        --linux-package-deps "libgtk-3-0,libglib2.0-0" \
        --license-file "$SCRIPT_DIR/LICENSE" 2>&1 | tail -3

    DEB_FILE=$(find "$DIST_DIR" -name "*.deb" | head -1)
    if [ -n "$DEB_FILE" ]; then
        DEB_SIZE=$(du -h "$DEB_FILE" 2>/dev/null | cut -f1)
        echo -e "${GREEN}  Package : $DEB_FILE ($DEB_SIZE)${NC}"
        echo -e "${GREEN}  Install : sudo dpkg -i $DEB_FILE${NC}"
    fi
}

build_rpm() {
    if ! $HAS_RPM; then
        echo -e "${YELLOW}  Skipping .rpm (rpmbuild not found). Install: sudo apt install rpm${NC}"
        return
    fi
    echo ""
    echo -e "${YELLOW}  Building .rpm package...${NC}"

    "$JAVA_HOME/bin/jpackage" \
        "${COMMON_ARGS[@]}" \
        --type rpm \
        --linux-package-name "remoteterminal" \
        --linux-shortcut \
        --linux-menu-group "Network;RemoteAccess;" \
        --linux-rpm-license-type "MIT" \
        --license-file "$SCRIPT_DIR/LICENSE" 2>&1 | tail -3

    RPM_FILE=$(find "$DIST_DIR" -name "*.rpm" | head -1)
    if [ -n "$RPM_FILE" ]; then
        RPM_SIZE=$(du -h "$RPM_FILE" 2>/dev/null | cut -f1)
        echo -e "${GREEN}  Package : $RPM_FILE ($RPM_SIZE)${NC}"
        echo -e "${GREEN}  Install : sudo rpm -i $RPM_FILE${NC}"
    fi
}

case "$BUILD_TYPE" in
    deb)
        build_deb
        ;;
    rpm)
        build_rpm
        ;;
    zip|tar|tarball|appimage)
        build_appimage
        ;;
    all|"")
        build_appimage
        build_deb
        build_rpm
        ;;
    *)
        echo -e "${RED}Unknown type: $BUILD_TYPE${NC}"
        echo "Usage: $0 [deb|rpm|appimage|all]"
        exit 1
        ;;
esac

# ============================================================
# 完成
# ============================================================
echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "${GREEN}  BUILD SUCCESS!${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""
echo -e "  Output directory: ${YELLOW}$DIST_DIR${NC}"
echo ""
ls -lh "$DIST_DIR"/*.{tar.gz,deb,rpm} 2>/dev/null || true
echo ""
echo -e "  ${CYAN}Quick install:${NC}"
echo -e "    tar.gz: tar -xzf $DIST_DIR/*.tar.gz && ./RemoteTerminal/bin/RemoteTerminal"
echo -e "    deb:    sudo dpkg -i $DIST_DIR/*.deb"
echo -e "    rpm:    sudo rpm -i $DIST_DIR/*.rpm"
echo ""
