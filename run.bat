@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo  RemoteTerminal - 环境准备
echo ============================================
echo.

set "LIB_DIR=lib"
if not exist "%LIB_DIR%" mkdir "%LIB_DIR%"

:: ===== 1. Download JavaFX SDK =====
set "JAVAFX_VER=17.0.14"
set "JAVAFX_DIR=%LIB_DIR%\javafx-sdk-%JAVAFX_VER%"
if not exist "%JAVAFX_DIR%\lib\javafx.base.jar" (
    echo [1/6] Downloading JavaFX SDK %JAVAFX_VER%...
    set "JAVAFX_URL=https://download2.gluonhq.com/openjfx/%JAVAFX_VER%/openjfx-%JAVAFX_VER%_windows-x64_bin-sdk.zip"
    set "JAVAFX_ZIP=%LIB_DIR%\openjfx-%JAVAFX_VER%.zip"

    :: Try PowerShell first
    powershell -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '!JAVAFX_URL!' -OutFile '!JAVAFX_ZIP!' -UseBasicParsing } catch { exit 1 }"
    if !ERRORLEVEL! neq 0 (
        :: Fallback to certutil
        echo    PowerShell failed, trying certutil...
        certutil -urlcache -split -f "!JAVAFX_URL!" "!JAVAFX_ZIP!" >nul 2>&1
    )
    if not exist "!JAVAFX_ZIP!" (
        echo ERROR: Failed to download JavaFX SDK. Please check your network.
        echo URL: !JAVAFX_URL!
        pause
        exit /b 1
    )
    echo    Extracting...
    powershell -Command "$ProgressPreference='SilentlyContinue'; Expand-Archive -Path '!JAVAFX_ZIP!' -DestinationPath '%LIB_DIR%' -Force"
    if not exist "%JAVAFX_DIR%\lib\javafx.base.jar" (
        echo ERROR: Failed to extract JavaFX SDK
        pause
        exit /b 1
    )
    del "!JAVAFX_ZIP!"
    echo    Done.
) else (
    echo [1/6] JavaFX SDK already exists, skip.
)

:: ===== 2. Download JSch =====
set "JSCH_URL=https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.23/jsch-0.2.23.jar"
set "JSCH_JAR=%LIB_DIR%\jsch-0.2.23.jar"
if not exist "%JSCH_JAR%" (
    echo [2/6] Downloading JSch...
    powershell -Command "Invoke-WebRequest -Uri '%JSCH_URL%' -OutFile '%JSCH_JAR%' -UseBasicParsing"
    echo    Done.
) else (
    echo [2/6] JSch already exists, skip.
)

:: ===== 3. Download HikariCP =====
set "HIKARI_URL=https://repo1.maven.org/maven2/com/zaxxer/HikariCP/6.2.1/HikariCP-6.2.1.jar"
set "HIKARI_JAR=%LIB_DIR%\HikariCP-6.2.1.jar"
if not exist "%HIKARI_JAR%" (
    echo [3/6] Downloading HikariCP...
    powershell -Command "Invoke-WebRequest -Uri '%HIKARI_URL%' -OutFile '%HIKARI_JAR%' -UseBasicParsing"
    echo    Done.
) else (
    echo [3/6] HikariCP already exists, skip.
)

:: ===== 4. Download H2 =====
set "H2_URL=https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar"
set "H2_JAR=%LIB_DIR%\h2-2.3.232.jar"
if not exist "%H2_JAR%" (
    echo [4/6] Downloading H2 Database...
    powershell -Command "Invoke-WebRequest -Uri '%H2_URL%' -OutFile '%H2_JAR%' -UseBasicParsing"
    echo    Done.
) else (
    echo [4/6] H2 already exists, skip.
)

:: ===== 5. Download SLF4J =====
set "SLF4J_API_URL=https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"
set "SLF4J_SIMPLE_URL=https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.17/slf4j-simple-2.0.17.jar"
set "SLF4J_API_JAR=%LIB_DIR%\slf4j-api-2.0.17.jar"
set "SLF4J_SIMPLE_JAR=%LIB_DIR%\slf4j-simple-2.0.17.jar"
if not exist "%SLF4J_SIMPLE_JAR%" (
    echo [5/6] Downloading SLF4J...
    powershell -Command "Invoke-WebRequest -Uri '%SLF4J_API_URL%' -OutFile '%SLF4J_API_JAR%' -UseBasicParsing"
    powershell -Command "Invoke-WebRequest -Uri '%SLF4J_SIMPLE_URL%' -OutFile '%SLF4J_SIMPLE_JAR%' -UseBasicParsing"
    echo    Done.
) else (
    echo [5/6] SLF4J already exists, skip.
)

:: ===== 6. Download ControlsFX =====
set "CTRLFX_URL=https://repo1.maven.org/maven2/org/controlsfx/controlsfx/11.2.1/controlsfx-11.2.1.jar"
set "CTRLFX_JAR=%LIB_DIR%\controlsfx-11.2.1.jar"
if not exist "%CTRLFX_JAR%" (
    echo [6/6] Downloading ControlsFX...
    powershell -Command "Invoke-WebRequest -Uri '%CTRLFX_URL%' -OutFile '%CTRLFX_JAR%' -UseBasicParsing"
    echo    Done.
) else (
    echo [6/6] ControlsFX already exists, skip.
)

echo.
echo ============================================
echo  All dependencies ready! 
echo  Now compiling project...
echo ============================================

:: ===== Build classpath =====
set "JFX_LIB=%JAVAFX_DIR%\lib"
set "CP=src\main\resources;%JFX_LIB%\javafx.base.jar;%JFX_LIB%\javafx.controls.jar;%JFX_LIB%\javafx.graphics.jar;%JFX_LIB%\javafx.web.jar;%JSCH_JAR%;%HIKARI_JAR%;%H2_JAR%;%SLF4J_API_JAR%;%SLF4J_SIMPLE_JAR%;%CTRLFX_JAR%"

:: ===== Compile =====
set "SRC_DIR=src\main\java"
set "OUT_DIR=target\classes"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo Compiling Java sources...
dir /s /b "%SRC_DIR%\*.java" > sources.txt
javac --release 17 --module-path "%JFX_LIB%" --add-modules javafx.controls,javafx.web,javafx.base,javafx.graphics -cp "%CP%" -d "%OUT_DIR%" @sources.txt
del sources.txt

if %ERRORLEVEL% neq 0 (
    echo.
    echo ============================================
    echo  COMPILE FAILED! Check errors above.
    echo ============================================
    pause
    exit /b 1
)

echo Compile success!

:: ===== Run =====
echo.
echo ============================================
echo  Starting RemoteTerminal...
echo ============================================

set "RES_CP=src\main\resources;%OUT_DIR%"
set "ALL_CP=%RES_CP%;%JSCH_JAR%;%HIKARI_JAR%;%H2_JAR%;%SLF4J_API_JAR%;%SLF4J_SIMPLE_JAR%;%CTRLFX_JAR%"

java ^
  --module-path "%JFX_LIB%" ^
  --add-modules javafx.controls,javafx.web,javafx.base,javafx.graphics ^
  --add-opens javafx.controls/javafx.scene.control=ALL-UNNAMED ^
  --add-opens javafx.controls/javafx.scene.control.skin=ALL-UNNAMED ^
  --add-opens javafx.graphics/javafx.scene=ALL-UNNAMED ^
  --add-exports javafx.base/com.sun.javafx.event=ALL-UNNAMED ^
  -cp "%ALL_CP%" ^
  com.remoteterminal.Main

pause
