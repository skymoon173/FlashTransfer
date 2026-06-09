# FlashTransfer / 闪传

无需云服务的局域网文件传输工具，支持 Windows、Android、iPhone 和普通浏览器。

文件只在当前 Wi-Fi 或热点中传输。每次启动会生成随机访问口令，支持多文件、文件夹、进度显示、二维码和设备自动发现。

## 功能

- Windows 作为文件传输主机
- Android 作为主机或客户端
- Android 与 Android 自动发现连接
- iPhone、电脑和其他设备通过浏览器连接
- 上传、下载、删除、多文件和文件夹传输
- Windows 与 Android 主机显示连接二维码
- 单个文件最大 50 GB，Android 主机会检查剩余空间

## 快速开始

### Windows 源码运行

需要 Python 3.10 或更高版本。

```powershell
python -m pip install -r requirements-build.txt
python server.py --hotspot
```

手机连接同一 Wi-Fi 或电脑热点后，扫描二维码或打开终端显示的地址。

### Android 构建

需要 JDK 17+、Android SDK 和 Gradle。

```powershell
cd android
gradle assembleDebug
```

APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。

### Windows EXE 构建

```powershell
python build_exe.py
```

EXE 输出到 `dist/FlashTransfer.exe`。

### 合规发行包

```powershell
.\build_release.ps1
```

该脚本会构建 Windows EXE 和 Android APK，并把 MIT 许可证、第三方声明及
完整第三方许可证文本一起放入发行包。

## Android 使用方式

- 连接电脑或另一台 Android：点击“自动查找”。
- Android 作为主机：点击“开启主机”，其他设备自动发现、扫码或打开地址。
- Android 与 iPhone：Android 开启主机，iPhone 连接同一网络后扫码。

## 网络说明

- IP 地址在运行时自动检测，没有写死。
- Windows 移动热点通常使用 `192.168.137.1`，程序会优先推荐该地址。
- 自动发现使用 UDP `8766`，文件传输默认使用 TCP `8765`。
- 手机作为主机时，速度通常低于电脑主机。推荐使用 5 GHz Wi-Fi/热点并关闭省电模式。

## 安全说明

- 服务仅监听当前设备网络接口，不会把文件上传到云端。
- 连接地址包含每次启动随机生成的访问口令。
- 请仅在可信局域网中使用，使用后停止主机服务。

## 许可证与商用

本项目源码采用 [MIT License](LICENSE)，允许个人和商业使用、修改及分发。

项目使用的第三方依赖也允许商业使用，但分发二进制文件时需要保留相应许可证与版权声明。详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 免责声明

本项目按原样提供，不附带任何保证。请在传输重要文件前保留备份。
