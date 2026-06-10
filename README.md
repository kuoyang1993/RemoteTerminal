# RemoteTerminal
JavaFX 实现的桌面远程终端客户端，提供 SSH 会话新建 / 保存 / 打开 / 删除功能，可视化管理远程服务器连接。

## 平台支持

| 平台 | 安装包格式 | 构建脚本 |
|------|-----------|----------|
| Windows | `.msi` (WiX) | `build-installer.ps1` / `build.bat` |
| Linux | `.deb` / `.rpm` / `.tar.gz` | `build-linux.sh` |

## Linux 构建

在 **Linux 机器**上（需 JDK 17+、Maven）：

```bash
# 赋予执行权限
chmod +x build-linux.sh run.sh

# 全量构建（.tar.gz + .deb + .rpm）
./build-linux.sh

# 仅构建通用压缩包（适用所有 Linux 发行版）
./build-linux.sh appimage

# 仅构建 .deb（Debian/Ubuntu）
./build-linux.sh deb

# 仅构建 .rpm（Fedora/CentOS/RHEL）
./build-linux.sh rpm
```

构建产物在 `target/dist-linux/` 目录：

```bash
# 通用压缩包安装（无需 root）
tar -xzf RemoteTerminal-1.1.0-linux-x64.tar.gz
./RemoteTerminal/bin/RemoteTerminal

# Debian/Ubuntu 安装
sudo dpkg -i remoteterminal_1.1.0_amd64.deb

# Fedora/RHEL 安装
sudo rpm -i remoteterminal-1.1.0.x86_64.rpm
```

## 开发运行（Linux）

```bash
chmod +x run.sh
./run.sh
```

## Windows 构建

```powershell
# 安装程序
.\build.bat

# 或直接运行
.\build-installer.ps1
```
