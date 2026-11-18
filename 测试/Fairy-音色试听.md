# Fairy 音色试听包

用户确认 nova 14 卓易通内没有可用的本地中文系统 TTS。独立 `tts-lab` 已改为 Fairy 音色试听包，应用标识 `com.hifairy.ttslab`，不会改动 HiFairy 正式应用数据。

包内收录参考仓库 `assets/voice/` 中六段预录 WAV：原始问候、早上、中午、下午、晚上、深夜。可逐段播放或停止，用于验证卓易通的音频播放与音色观感。**固定录音只能试听，无法朗读任意输入或 AI 回复。**参考仓库不包含用于动态合成的 GPT-SoVITS 引擎和权重。

构建：先运行 `./tts-lab/prepare-voice.ps1`，从本地参考仓库复制音频素材；再运行 `./gradlew.bat :tts-lab:assembleDebug`。APK 位于 `tts-lab/build/outputs/apk/debug/tts-lab-debug.apk`。复制出的 WAV 不进入 Git。当前构建成功并确认包内六段音频齐全；ADB 未发现设备，仍需 nova 14 验收。

下一步是动态合成测试：找到的公开 Fairy 权重为 `Fairy-e10.ckpt`（约 155MB）和 `Fairy_e10_s870.pth`（约 85MB），但还需 GPT-SoVITS 运行时、前端资源与参考音频。需要验证手机端运行能力或桌面服务的连接延迟，不能仅把权重塞进 APK 宣称能合成语音。
