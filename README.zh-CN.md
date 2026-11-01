# TV 扫码安装

[English](README.md) | [简体中文](README.zh-CN.md)

在 Android TV 上装 APK，不用 adb、不用 U 盘：电视显示二维码，手机扫码上传即可。

## 使用方式

- 电视端打开 App，前台服务会在局域网内起一个 HTTP 上传页，屏幕上直接显示地址二维码
- 手机扫码进入上传页，选择 APK 上传（带进度条），文件直接传到电视
- 电视自动拉起系统安装器；如果还没授予「安装未知应用」权限，会跳到授权页，返回后自动继续安装
- 数据只在局域网内传输，不会上传到互联网

## 要求

- Android 5.0（API 21）及以上
- 手机和电视在同一局域网

## 构建

```bash
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。编译前先创建 `local.properties`，写入 `sdk.dir=/path/to/Android/sdk`。

## 技术栈

- Java + 原生 View，minSdk 21 / targetSdk 34
- [ZXing](https://github.com/zxing/zxing) core 生成二维码
- 基于 `ServerSocket` 的 `HttpFileServer` 提供上传页
