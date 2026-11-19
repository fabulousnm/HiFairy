# Fairy 本地语音推理测试

2026-11-19：nova 14 的卓易通没有可用的本地中文系统 TTS。`tts-lab` 因此改为独立的 **ZipVoice 离线生成测试包**，包名 `com.hifairy.ttslab`，不会改动 HiFairy 正式应用的数据。

测试包采用 [sherpa-onnx ZipVoice 中文/英文 INT8 模型](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/tts/zipvoice.rst)，以参考仓库的 `greet-noon.wav` 作为 Fairy 音色参考，并使用参考仓库中的对应原文。音频被转换为模型要求的 24 kHz 单声道 PCM16。**这是真实的手机端离线合成，不是播放预录句子；但它不是原版 Fairy GPT-SoVITS 权重，音色相似度必须试听判断。**「试听 Fairy 原声参考」按钮只用于对照。

打开测试包后等待模型加载，记录加载时间。保留默认短句，连续点击「离线生成并播放」三次，再输入较长中文句子测试。界面显示：

- 首块生成：原版用于测从发起合成到首次返回音频块；0.2.1 因暂时停用不兼容的 JNI 回调，界面显示“无”。
- 完整推理：模型生成整段音频的耗时，不含播放器写入和播放等待。
- 音频长度与 RTF：RTF = 完整推理耗时 ÷ 音频长度；小于 1 才可能达到实时生成。

当前版本为了把推理与播放器等待分开计时，先完成整段生成再播放。测试时还需记录是否发声、音色相似度、清晰度、是否漏字及发热。长文本若过慢，可据此决定是否分句、使用流式播放或换模型。

构建：先运行 `./tts-lab/prepare-voice.ps1` 下载并校验模型/声码器，复制参考音频；`voice-lab/vendor/sherpa-static.aar` 由 `./voice-lab/prepare-models.ps1` 准备。然后运行 `./gradlew.bat :tts-lab:assembleDebug`。APK 位于 `tts-lab/build/outputs/apk/debug/tts-lab-debug.apk`，约 179 MB。模型与参考音频保存在 Git 忽略路径，不提交大文件。

验证：APK 构建通过，包内模型、参考音频和 arm64 JNI 库齐全。使用相同资源的 Windows CPU 静默合成检查成功：短句「主人，本系统已经就绪。」生成 49,387 个 24 kHz 样本，模型加载约 1,860 ms、完整推理约 1,875 ms、RTF 约 0.91。这些是电脑指标，**不能代替 nova 14 的推理延迟或音色评价**。当前 ADB 没有设备，真机结果仍待测试。

2026-11-19 用户反馈 nova 14 上启动本地推理时闪退。HDC 已连接手机并在复现时从 Harmony hilog 捕获到 `ANCO DIED:pid=49012,pName=com.hifairy.ttslab`，确认卓易通内测试进程退出；该日志没有 Android 异常栈或具体原因，不能据此判断是内存不足、模型原生崩溃还是播放错误。ADB 仍不显示该设备，因为目前暴露的是 HDC Interface。

诊断版测试包显示上次执行阶段、Android `ApplicationExitInfo` 退出原因及可读的原生跟踪摘要。用户复现并重启后截图确认：模型加载约 4494 ms；上次阶段为 `generating`；系统退出为原生崩溃、状态 6（`SIGABRT`）；原生跟踪明确指出 `NoSuchMethodError`，JNI 在 `generateWithConfigImpl` 回调过程中试图调用测试包 lambda 的 `invoke(F)Ljava/lang/Integer;`，与 Kotlin 侧回调签名不匹配。由此不能推断手机算力或内存不足。

修复版 0.2.1 改用 sherpa 官方 `generateWithConfig` 无回调接口，避免该 JNI/Kotlin 回调不匹配；仍测完整推理时间、音频长度及 RTF，首块时间显示“无”。已构建成功，待真机安装复测，手机端推理性能和音色仍未验证。APK 路径仍为 `tts-lab/build/outputs/apk/debug/tts-lab-debug.apk`。对手机端与电脑端的可行性比较见《../调研/2026-11-19-Fairy-语音推理部署比较.md》。
