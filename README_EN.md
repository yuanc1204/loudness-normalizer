# Loudness Normalizer

[中文](README.md) | [English](README_EN.md)

A local loudness-normalization tool for videos and voice recordings, available as an Android app and a Windows script. It analyzes perceived loudness over time, raises quieter sections, and preserves natural dynamics as much as possible.

## Features

- Import multiple videos or audio files, or share media directly from Telegram and other apps
- Segment-based loudness normalization with adjustable target loudness and strength
- Copy the original video stream when no trimming is required, avoiding video re-encoding
- Preview, split, remove, reorder, and concatenate video segments
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

Download the latest APK from [GitHub Releases](https://github.com/yuanc1204/loudness-normalizer/releases).

1. Install the APK and open **Loudness Normalizer** (`响度均衡`).
2. Tap **Select video or audio**. Media can also be shared directly to the app from Telegram and other apps.
3. Adjust the target loudness and normalization strength if needed; the defaults work well for most recordings.
4. Tap a video thumbnail to trim it, or select and reorder multiple videos for concatenation.
5. Start processing and wait for scanning and output generation to finish. Source files are never modified.

Default output locations:

- Videos: `Movies/响度均衡`
- Audio: `Music/响度均衡`
- A custom folder can be selected in Settings
- On Android 11 and later, videos can optionally be stored in the hidden folder `Movies/.响度均衡`

### Build the Android app

Requirements: JDK 17, Android SDK 34, and Gradle 8.9. Run from the `android` directory:

```powershell
gradle assembleDebug --no-daemon
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

For a signed release, create a local `android/keystore.properties` file and the keystore referenced by it, then run:

```powershell
gradle assembleRelease --no-daemon
```

The signed APK is generated at `android/app/build/outputs/apk/release/app-release.apk`.
The keystore and password configuration are ignored by Git. Keep both securely backed up, because all future updates must use the same signing key.

Release certificate SHA-256:

```text
85:86:84:22:A5:76:5E:3A:83:C4:7B:52:C1:63:2D:85:01:F3:84:B4:8B:EB:4C:71:14:8B:20:0C:94:06:33:E4
```

## Windows

The Windows version provides a quick way to try video loudness normalization without installing the Android app. It requires Python 3.9 or later, plus `ffmpeg` and `ffprobe` available on `PATH`.

The simplest method is to drag one or more videos onto `响度均衡.bat`. Processed files are written to the `输出` folder in the repository root, and the source videos are left unchanged.

Command-line examples:

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
