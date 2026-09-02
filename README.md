# 课表 App

安卓端课表生成器：导入选课结果 PDF 或 Word 文档，也可以手动输入课程，自动生成周一至周日的周课表，显示课程名称、老师、教室和上课周次。

## 功能

- 支持直接导入选课系统导出的 PDF
- 支持导入 Word 文档（DOC / DOCX）
- 支持手动输入课程并即时生成课表
- 课表自动保存到本地，重启 App 后自动恢复
- 支持设置每节课前提醒时间，到点发送通知预告上课
- 提醒开启后使用后台前台服务常驻，退出 App 也能按时提醒
- 使用 Android `PdfRenderer` 渲染 PDF
- PDF 有文字层时优先用 PDFBox 读取文字坐标，扫描版再走 OCR
- 使用 ML Kit 中文 OCR 在手机本地识别课表，无需联网
- 自动解析星期列、节次、课程、老师、教室、周次
- 同一时间段多门课自动并排显示
- 课程卡片内文字自动换行，点击课程可直接编辑名称、老师、教室、周次、星期和节次

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
- DOC / DOCX 文件优先按真实表格单元格解析，避免文字流顺序错乱，不需要联网
