# 响度均衡

[中文](README.md) | [English](README_EN.md)

一款本地处理视频和录音响度的工具，提供 Android App 和 Windows 脚本。它会分析不同时间段的听感响度，重点提升偏小的声音，同时尽量保留自然起伏。

## 功能

- 支持视频和纯音频批量导入，也可从 Telegram 等应用直接分享文件
- 按时间段进行响度均衡，可调整目标响度和均衡力度
- 未裁剪的视频直接复制画面流，不重新编码画面
- 视频支持预览、分割、删除片段、排序和拼接
- 纯音频输出为 M4A，并尽量沿用原音轨码率，避免低码率录音体积膨胀
- 支持后台处理、进度通知、取消及未完成文件清理
- 可选择自定义保存文件夹

## 界面截图

<p align="center">
  <a href="docs/screenshots/home-processing.jpg"><img src="docs/screenshots/home-processing.jpg" width="280" alt="音视频响度处理首页"></a>
  &nbsp;&nbsp;
  <a href="docs/screenshots/video-trim.jpg"><img src="docs/screenshots/video-trim.jpg" width="280" alt="视频裁剪界面"></a>
</p>
<p align="center"><sub>音视频响度处理　｜　视频裁剪</sub></p>

## Android 使用

1. 安装已构建的 APK，打开「响度均衡」。
2. 点「选择视频或音频」，也可以从 Telegram 等应用直接分享文件到本应用。
3. 按需调整目标响度和均衡力度；一般保持默认值即可。
4. 视频可以点预览图裁剪，也可以选择多个视频排序、拼接。
5. 点「开始处理」，等待扫描和生成完成；原文件不会被修改。

保存位置：

- 视频默认保存到 `Movies/响度均衡`
- 音频默认保存到 `Music/响度均衡`
- 可在设置中选择自定义文件夹
- Android 11 及以上可选择隐藏保存到 `Movies/.响度均衡`

详细功能和设置说明见 [安卓使用说明.txt](安卓使用说明.txt)。

### 构建 APK

需要 JDK 17、Android SDK 34 和 Gradle 8.9。在 `android` 目录运行：

```powershell
gradle assembleDebug --no-daemon
```

调试 APK 位于：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

正式发布前需在本机创建 `android/keystore.properties` 和其中指定的密钥文件，
然后在 `android` 目录运行：

```powershell
gradle assembleRelease --no-daemon
```

签名后的正式 APK 位于 `android/app/build/outputs/apk/release/app-release.apk`。
密钥和密码配置已被 Git 忽略，不能上传仓库；必须安全备份，后续版本需要使用同一密钥才能覆盖升级。

正式签名证书 SHA-256：

```text
85:86:84:22:A5:76:5E:3A:83:C4:7B:52:C1:63:2D:85:01:F3:84:B4:8B:EB:4C:71:14:8B:20:0C:94:06:33:E4
```

## Windows 使用

Windows 版适合先在电脑上尝试视频响度均衡，不需要安装 Android App。
需要 Python 3.9 或更高版本，并安装 `ffmpeg`、`ffprobe`，确保二者已加入 `PATH`。

最简单的用法：把一个或多个视频拖到 `响度均衡.bat` 上，处理结果会保存到仓库根目录的 `输出` 文件夹，原视频不会被修改。

也可以在仓库根目录使用命令行：

```powershell
# 处理当前文件夹中的全部视频
python 响度均衡.py

# 处理一个或多个指定视频
python 响度均衡.py 视频1.mp4 视频2.mp4

# 处理指定文件夹中的全部视频
python 响度均衡.py D:\视频文件夹

# 自定义目标响度、均衡力度和并行任务数
python 响度均衡.py --target -14 --strength 0.95 --jobs 3 视频.mp4
```

Windows 脚本当前只处理视频；画面流直接复制，只重新处理音频。支持 `mp4`、`mkv`、`mov`、`avi`、`flv`、`ts`、`m4v`、`webm`、`wmv`、`mpg` 和 `mpeg`。
