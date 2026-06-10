# FlashTransfer

FlashTransfer 是一个局域网文件传输工具，支持 Windows、Android、iPhone
和浏览器之间互传文件。文件只在本地网络中传输，不经过云端服务器。

## 功能

- Windows 与手机双向传输文件。
- Android 设备可作为主机，让其他 Android 或 iPhone 连接。
- 自动检测局域网 IP，并提供二维码连接。
- 支持文件与文件夹上传、下载进度和大文件传输。
- 自动发现同一局域网中的 FlashTransfer 主机。

## 快速运行

需要 Python 3.10 或更高版本。

```powershell
python server.py
```

程序启动后会显示本机访问地址和二维码。手机连接同一 Wi-Fi 或电脑热点后，
使用浏览器打开该地址即可传输文件。

## 构建

安装 Windows 构建依赖：

```powershell
python -m pip install -r requirements-build.txt
```

构建 Windows EXE：

```powershell
python build_exe.py
```

构建 Android APK：

```powershell
cd android
gradle assembleDebug
```

构建完整发布包：

```powershell
.\build_release.ps1
```

生成物统一放在 `release/`，源码仓库不提交 EXE、APK 或 ZIP。

## 网络

- 文件传输默认使用 TCP `8765`。
- 自动发现使用 UDP `8766`。
- IP 地址在启动时自动检测，没有写死。
- 使用 Windows 移动热点时通常会检测到 `192.168.137.1`。
- 推荐使用 5 GHz Wi-Fi 或热点以获得更高速度。

## 项目结构

详见 [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md)。

## 安全

- 文件不会上传到云端。
- 每次启动会生成随机访问令牌。
- 请只在可信局域网中使用，使用完毕后停止主机服务。

## 许可证

项目源码使用 [MIT License](LICENSE)。第三方依赖及二进制分发义务见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 `licenses/`。
