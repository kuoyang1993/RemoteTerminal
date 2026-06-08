#!/usr/bin/env bash
# ============================================================
#  RemoteTerminal - Linux 开发运行脚本
#  自动下载依赖 + 编译 + 启动
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

LIB_DIR="lib"
JAVAFX_VER="17.0.14"
JAVAFX_DIR="${LIB_DIR}/javafx-linux-sdk-${JAVAFX_VER}"
JAVAFX_LIB="${JAVAFX_DIR}/lib"

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  RemoteTerminal - Development Run${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

# ============================================================
# 1. 环境检测
# ============================================================
echo -e "${YELLOW}[1/5] Checking environment...${NC}"

if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java 2>/dev/null) 2>/dev/null) 2>/dev/null) 2>/dev/null)
fi
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo -e "${RED}  JAVA_HOME not set and java not found!${NC}"
    exit 1
fi
echo -e "${GREEN}  JAVA_HOME = $JAVA_HOME${NC}"

# ============================================================
# 2. 下载 Linux JavaFX SDK
# ============================================================
echo -e "${YELLOW}[2/5] JavaFX SDK (Linux)...${NC}"

if [ ! -f "${JAVAFX_LIB}/javafx.base.jar" ]; then
    JAVAFX_URL="https://download2.gluonhq.com/openjfx/${JAVAFX_VER}/openjfx-${JAVAFX_VER}_linux-x64_bin-sdk.zip"
    JAVAFX_ZIP="${LIB_DIR}/javafx-linux-${JAVAFX_VER}.zip"
    
    echo "  Downloading JavaFX SDK ${JAVAFX_VER} for Linux..."
    mkdir -p "$LIB_DIR"
    wget -q --show-progress -O "$JAVAFX_ZIP" "$JAVAFX_URL" || {
        echo "  wget failed, trying curl..."
        curl -L -o "$JAVAFX_ZIP" "$JAVAFX_URL" || { echo -e "${RED}  Download failed${NC}"; exit 1; }
    }
    
    echo "  Extracting..."
    mkdir -p "$JAVAFX_DIR"
    unzip -qo "$JAVAFX_ZIP" -d lib/
    EXTRACTED_DIR=$(find lib/ -maxdepth 1 -type d -name "javafx-sdk-*" | head -1)
    if [ -d "$EXTRACTED_DIR" ] && [ "$EXTRACTED_DIR" != "$JAVAFX_DIR" ]; then
        rm -rf "$JAVAFX_DIR"
        mv "$EXTRACTED_DIR" "$JAVAFX_DIR"
    fi
    rm -f "$JAVAFX_ZIP"
    echo -e "${GREEN}  Done.${NC}"
else
    echo -e "${GREEN}  JavaFX SDK already exists, skip.${NC}"
fi

# ============================================================
# 3. Maven 编译
# ============================================================
echo -e "${YELLOW}[3/5] Compiling with Maven...${NC}"

if [ -f "./mvnw" ]; then
    ./mvnw compile -q
elif command -v mvn &>/dev/null; then
    mvn compile -q
else
    echo -e "${RED}  Maven not found! Install: sudo apt install maven${NC}"
    exit 1
fi
echo -e "${GREEN}  Compile done.${NC}"

# ============================================================
# 4. 构建 classpath
# ============================================================
echo -e "${YELLOW}[4/5] Resolving dependencies...${NC}"

CLASSES_DIR="target/classes"
RESOURCES_DIR="src/main/resources"

# 通过 maven dependency:build-classpath 获取精确 classpath
if [ -f "./mvnw" ]; then
    CP=$("./mvnw" -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)
elif command -v mvn &>/dev/null; then
    CP=$(mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)
fi

if [ -z "$CP" ] || [ "$CP" = "null" ]; then
    echo -e "${RED}  Failed to resolve classpath${NC}"
    exit 1
fi

FULL_CP="${CLASSES_DIR}${CP:+:${CP}}"
if [ -d "$RESOURCES_DIR" ]; then
    FULL_CP="${RESOURCES_DIR}:${FULL_CP}"
fi
echo -e "${GREEN}  Classpath resolved.${NC}"

# ============================================================
# 5. 启动
# ============================================================
echo -e "${YELLOW}[5/5] Starting RemoteTerminal...${NC}"
echo ""

# JavaFX + Linux 需要的额外模块打开
"$JAVA_HOME/bin/java" \
    --module-path "${JAVAFX_LIB}" \
    --add-modules javafx.controls,javafx.web,javafx.base,javafx.graphics,javafx.fxml \
    --add-opens javafx.controls/javafx.scene.control=ALL-UNNAMED \
    --add-opens javafx.controls/javafx.scene.control.skin=ALL-UNNAMED \
    --add-opens javafx.graphics/javafx.scene=ALL-UNNAMED \
    --add-exports javafx.base/com.sun.javafx.event=ALL-UNNAMED \
    -cp "$FULL_CP" \
    com.remoteterminal.Main
