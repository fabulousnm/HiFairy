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

## 真机结果（2026-11-18）

用户提供华为 nova 14 截图，显示 HiFairy 验证版已成功启动，页面渲染正常，按钮文字为“麦克风权限已授予”。这确认了当前 APK 的安装、启动、Compose 页面显示和 Android 麦克风权限授予流程。尚未验证实际录音数据、语音播放、性能或后台行为。

## Android 模拟器结果（2026-11-18）

使用本机 `Medium_Phone_API_37.0` 模拟器无窗口启动，通过 ADB 安装现有 debug APK，安装返回 `Success`，Activity 成功启动且进程持续运行。截图检查页面布局正常。点击麦克风按钮后出现系统授权弹窗，选择允许后 `dumpsys package` 显示 `RECORD_AUDIO: granted=true`。该结果只验证标准 Android 环境，不代表卓易通的音频与性能表现。

## 下一版录音回放验证

已加入开始录音、停止录音和播放录音按钮。录音保存到应用私有缓存，使用 Android `MediaRecorder` 的 AAC/MPEG-4 格式；播放使用 `MediaPlayer`。构建已通过。尚需在 nova 14 卓易通中试听，并确认权限、录音长度和输出音量。

用户于 2026-11-18 确认：在 nova 14 的卓易通中可以完成录音与回放。实际延迟、音质、长时间录音和后台中断仍待测。
