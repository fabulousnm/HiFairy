# Fairy 电脑端原版权重基准

隔离的命令行实验，不启动网页或修改 Android 应用。运行原版 Fairy GPT-SoVITS 权重，以仓库公开的 5.62 秒参考音频及同名台词提示，输出 WAV 和 JSON 耗时指标。

## 固定资源

- 推理代码：`RVC-Boss/GPT-SoVITS` 的 `20250606v2pro` 标签，提交 `d7c2210da8c013e81a94bfc7b811a477c99fd506`。
- 角色权重及参考音频：`UnlimitedBurst/GPT-SoVITS` 修订 `361def686acbe777f9659944afed8c3925d6cd2e`，目录 `绝区零（已更新1.0）/Fairy`。权重为 v2。
- 基础模型：`lj1995/GPT-SoVITS` 的 `chinese-hubert-base` 与 `chinese-roberta-wwm-ext-large`。
- 中文 G2PW：`XXXXRT/GPT-SoVITS-Pretrained` 的 `G2PWModel.zip`，修订 `502bea75a80af535955c39eeef3f7c86bc235665`。

所有下载放在 `voice-pc/vendor/`，虚拟环境放在项目之外，均不进入 Git。基准输出放在 `voice-pc/output/`，同样不提交。已校验两个 Fairy 权重和基础模型的 SHA-256 与 Hugging Face LFS 元数据一致。

## 运行

使用 Python 3.11 和 RTX 4060 Laptop 的 CUDA 12.4 版 PyTorch 2.5.1；其余依赖见 `requirements-benchmark.txt`。运行前应保证 `ffmpeg.exe` 在 `PATH` 中，中文 G2PW 模型解压为 `voice-pc/vendor/GPT-SoVITS/GPT_SoVITS/text/G2PWModel/`。

```powershell
& '<虚拟环境>\Scripts\python.exe' voice-pc\benchmark.py --repeats 3
```

可用 `--text` 改测试句，`--text-split-method cut5` 按标点切分并分段返回，`--output-dir` 改输出目录。指标含模型加载、进程启动至模型就绪、首段生成、完整推理、音频长度、RTF、PyTorch 已分配显存峰值。首段生成时间是第一批 PCM 样本返回时间，不等于扬声器实际出声；当前未做异网传输测试。

2026-11-19 的实测与试听文件见《../测试/Fairy-电脑端原版权重推理.md》。
