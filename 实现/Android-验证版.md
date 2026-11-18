# Android 验证版

当前版本：0.1.0，包名 `com.hifairy.app`。目标是先确认华为 nova 14 上的卓易通能否安装和启动 Android APK，以及麦克风权限能否弹出并授予。本版尚未接入录音、识别、语音合成、DeepSeek 或参考仓库素材。

## 构建

使用 JDK 21 与已安装的 Android SDK，运行 `./gradlew :app:assembleDebug`。生成文件为 `app/build/outputs/apk/debug/app-debug.apk`。首次构建已于 2026-11-18 成功，APK 约 10.3 MB。

## 真机验证

1. 在手机安装并打开卓易通，连接 USB 数据线。
2. 按手机提示允许 USB 调试；若卓易通的安装路径需要在其应用内导入 APK，则从中选择上述文件。
3. 启动 HiFairy，确认深色页面显示且按钮能弹出麦克风授权。
4. 记录安装、启动或授权失败时的完整提示与截图。

此验证结果会决定后续录音、音频播放和动画 PoC 的实现与测试方式。
