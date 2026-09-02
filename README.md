# 课表 App

安卓端课表生成器：导入“选课结果 PDF”后，自动识别课程并生成周一至周日的周课表，显示课程名称、老师、教室和上课周次。

## 功能

- 支持直接导入选课系统导出的 PDF
- 使用 Android `PdfRenderer` 渲染 PDF
- 使用 ML Kit 中文 OCR 在手机本地识别课表，无需联网
- 自动解析星期列、节次、课程、老师、教室、周次
- 同一时间段多门课自动并排显示
- 内置示例课表

## 环境

- Android SDK 34
- JDK 17 或更高
- Gradle 8.9

## 构建

```bash
./gradlew assembleRelease
```

APK 输出位置：

```text
app/build/outputs/apk/release/app-release.apk
```

仓库中同时保留了已签名的安装包：`dist/KebiaoApp-v1.0.apk`。

## 说明

- 支持 Android 7.0 及以上（API 24+）
- 支持 arm64-v8a 与 armeabi-v7a
- 中文 OCR 模型已随 APK 打包，离线可用
- `app/src/main/assets/sample/sample.pdf` 为内置示例课表
