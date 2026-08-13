# Repository Guidelines

## Project Structure & Module Organization

This repository contains the Android implementation of the loudness-normalization workflow:

- `android/app/src/main/java/com/yc/loudnorm/` contains the Android Kotlin code. `MainActivity.kt` owns UI and processing orchestration; `Engine.kt` contains the loudness segmentation algorithm.
- `android/app/src/main/res/` holds the Android layout, theme, and launcher assets.
- `安卓使用说明.txt` is the user-facing documentation; `README.md` is the repository overview.

Generated media, APKs, caches, and Android build outputs are not source files and should remain untracked.

## Build, Test, and Development Commands

Build the Android debug APK from `android/` (Gradle 8.9 and JDK 17 are expected; no wrapper is committed):

```powershell
gradle assembleDebug --no-daemon
```

The APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`.

Use three-part Android versions. Increment the patch number for fixes or display tweaks (`2.3.1` to `2.3.2`); increment the minor number and reset the patch for substantial features (`2.3.2` to `2.4.0`). Increment `versionCode` on every APK build.

## Coding Style & Naming Conventions

Use UTF-8 and preserve Chinese filenames and UI text. Kotlin uses four-space indentation, `camelCase` members, `PascalCase` types, and concise KDoc for non-obvious media logic. Avoid unrelated formatting churn.

## Testing Guidelines

There is currently no automated test suite. At minimum, run an Android debug build for code or resource changes. For documentation-only changes, verify links, referenced paths, and the staged diff. For media changes, manually verify single and multiple videos, progress reporting, output playback, and preservation of the original files. For UI changes, test selection, thumbnail loading, reordering, deletion, and concat checkboxes on an Android 10+ device or emulator.

## Commit & Pull Request Guidelines

Follow the existing concise, feature-focused Chinese commit style, for example: `安卓 App：为导入视频增加预览图`. Keep each commit scoped to one behavior. Pull requests should explain user-visible changes, list validation performed, link relevant issues, and include screenshots for UI changes. Do not commit sample videos, generated APKs, build directories, or local SDK configuration.
