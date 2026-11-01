# TV QR Installer

[English](README.md) | [简体中文](README.zh-CN.md)

Install an APK on your Android TV by scanning a QR code — no adb, no USB drive.

Run this app on the TV, scan the QR code it shows with your phone, pick an APK on the upload page, and the TV installs it.

## How it works

- A foreground service starts a small HTTP server on the LAN and the screen shows the upload URL as a QR code
- Scan the QR code with a phone browser: choose an APK, upload it with a progress bar, and the file is streamed straight to the TV
- The TV opens the system installer automatically; if the "install unknown apps" permission is missing it jumps to the settings page and continues as soon as you come back
- Data only travels inside your local network — nothing is uploaded to the internet

## Requirements

- Android 5.0 (API 21) or newer
- Phone and TV on the same LAN

## Build

```bash
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Create a `local.properties` with `sdk.dir=/path/to/Android/sdk` before building.

## Tech

- Java, plain Android views, minSdk 21 / targetSdk 34
- [ZXing](https://github.com/zxing/zxing) core for QR generation
- `HttpFileServer` on top of `ServerSocket` for the upload page
