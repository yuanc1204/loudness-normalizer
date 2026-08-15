# Loudness Normalizer

[中文](README.md) | [English](README_EN.md)

A local loudness-normalization tool for videos and voice recordings, available as an Android app and a Windows script. It analyzes perceived loudness over time, raises quieter sections, and preserves natural dynamics as much as possible.

## Download for Android

Open the [latest release](https://github.com/yuanc1204/loudness-normalizer/releases/latest), download the APK, and install it.
If Android blocks the installation, follow the system prompt to allow your browser or file manager to install unknown apps.

## Features

- Import multiple videos or audio files, or share media directly from Telegram and other apps
- Segment-based loudness normalization with adjustable target loudness and strength
- Copy the original video stream when no trimming is required, avoiding video re-encoding
- Preview, split, remove, reorder, and concatenate video segments
- Rename the generated video by tapping its title
- Export audio as M4A while preserving the source bitrate where possible to avoid unnecessary file-size growth
- Background processing, progress notifications, cancellation, and cleanup of incomplete files
- Custom output-folder support

## Screenshots

<p align="center">
  <a href="docs/screenshots/home-processing.jpg"><img src="docs/screenshots/home-processing.jpg" width="280" alt="Audio and video loudness processing"></a>
  &nbsp;&nbsp;
  <a href="docs/screenshots/video-trim.jpg"><img src="docs/screenshots/video-trim.jpg" width="280" alt="Video trimming interface"></a>
</p>
<p align="center"><sub>Loudness processing &nbsp;|&nbsp; Video trimming</sub></p>

## Android

1. Download the APK from the [latest release](https://github.com/yuanc1204/loudness-normalizer/releases/latest), install it, and open **Loudness Normalizer** (`响度均衡`).
2. Tap **Select video or audio**. Media can also be shared directly to the app from Telegram and other apps.
3. Adjust the target loudness and normalization strength if needed; the defaults work well for most recordings.
4. Tap a video thumbnail to trim it, or select and reorder multiple videos for concatenation.
5. Start processing and wait for scanning and output generation to finish. Source files are never modified.

Default output locations:

- Videos: `Movies/响度均衡`
- Audio: `Music/响度均衡`
- A custom folder can be selected in Settings
- On Android 11 and later, videos can optionally be stored in the hidden folder `Movies/.响度均衡`

### Build for development

Requirements: JDK 17 and Android SDK 34. Run from the `android` directory:

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Windows

The Windows version provides a quick way to try video loudness normalization without installing the Android app. It requires Python 3.9 or later, plus `ffmpeg` and `ffprobe` available on `PATH`.

1. Click **Code** on the repository page, choose **Download ZIP**, and extract it.
2. Install [Python](https://www.python.org/downloads/) and [FFmpeg](https://ffmpeg.org/download.html), ensuring both are available from the command line.
3. Drag one or more videos onto `响度均衡.bat`.
4. Find the processed files in the `输出` folder. Source videos are never modified.

Command-line examples for advanced users:

```powershell
# Process every video in the current folder
python 响度均衡.py

# Process one or more videos
python 响度均衡.py video1.mp4 video2.mp4

# Process every video in a folder
python 响度均衡.py D:\Videos

# Set target loudness, normalization strength, and parallel jobs
python 响度均衡.py --target -14 --strength 0.95 --jobs 3 video.mp4
```

The Windows script currently processes video files only. It copies the video stream and re-encodes only the audio. Supported containers include `mp4`, `mkv`, `mov`, `avi`, `flv`, `ts`, `m4v`, `webm`, `wmv`, `mpg`, and `mpeg`.

## License

Project source code is available under the [MIT License](LICENSE). Bundled dependencies remain under their respective licenses; see [Third-Party Notices](THIRD_PARTY_NOTICES.md).
