# Fairy 电脑端原版权重基准

隔离的命令行实验，不启动网页或修改 Android 应用。运行原版 Fairy GPT-SoVITS 权重，以仓库公开的 5.62 秒参考音频及同名台词提示，输出 WAV 和 JSON 耗时指标。

## 固定资源

- 推理代码：`RVC-Boss/GPT-SoVITS` 的 `20250606v2pro` 标签，提交 `d7c2210da8c013e81a94bfc7b811a477c99fd506`。
- 角色权重及参考音频：`UnlimitedBurst/GPT-SoVITS` 修订 `361def686acbe777f9659944afed8c3925d6cd2e`，目录 `绝区零（已更新1.0）/Fairy`。权重为 v2。
- 基础模型：`lj1995/GPT-SoVITS` 的 `chinese-hubert-base` 与 `chinese-roberta-wwm-ext-large`。
- 中文 G2PW：`XXXXRT/GPT-SoVITS-Pretrained` 的 `G2PWModel.zip`，修订 `502bea75a80af535955c39eeef3f7c86bc235665`。
- 中英混合文本：`fast-langdetect` 的 `lid.176.bin` 及 NLTK 的 CMU 词典、Punkt 和词性标注资源，运行 `voice-pc/prepare-language-assets.ps1` 下载、校验并解压到忽略目录。FastText 文件校验 MD5 为 `01810bc59c6a3d2b79c79e6336612f65`；其余 ZIP 的 SHA-256 固定在脚本中。

所有下载放在 `voice-pc/vendor/`，虚拟环境放在项目之外，均不进入 Git。基准输出放在 `voice-pc/output/`，同样不提交。已校验两个 Fairy 权重和基础模型的 SHA-256 与 Hugging Face LFS 元数据一致。

## 运行

使用 Python 3.11 和 RTX 4060 Laptop 的 CUDA 12.4 版 PyTorch 2.5.1；其余依赖见 `requirements-benchmark.txt`。运行前应保证 `ffmpeg.exe` 在 `PATH` 中，中文 G2PW 模型解压为 `voice-pc/vendor/GPT-SoVITS/GPT_SoVITS/text/G2PWModel/`，并运行一次 `voice-pc/prepare-language-assets.ps1`。服务和基准脚本启动时会预加载中英混合语言识别与英文发音资源，缺失时在启动阶段报错。

```powershell
& '<虚拟环境>\Scripts\python.exe' voice-pc\benchmark.py --repeats 3
```

可用 `--text` 改测试句，`--text-split-method cut5` 按标点切分并分段返回，`--text-lang` 覆盖自动语言选择，`--output-dir` 改输出目录。默认自动选择：纯中文 `all_zh`，中英混合 `zh`，纯英文 `en`；参考音频的提示文本始终按中文处理。指标含模型加载、进程启动至模型就绪、首段生成、完整推理、音频长度、RTF、PyTorch 已分配显存峰值。首段生成时间是第一批 PCM 样本返回时间，不等于扬声器实际出声；异网传输另见测试文档。

2026-11-19 的实测与试听文件见《../测试/Fairy-电脑端原版权重推理.md》。

## 临时异网语音测试

Windows 上可双击仓库根目录的 `启动HiFairy语音服务器.cmd`：脚本检查并复用已就绪的本地 Fairy 服务和 Cloudflare 通道；缺失时在后台启动，等待模型预热及公网健康检查，最后显示手机「语音播放」设置所需的 `wss://…/stream` 地址与访问令牌。命令行只检查现有服务可运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File voice-pc/start-hifairy-server.ps1 -StatusOnly`；虚拟环境位置变化时用 `-PythonPath` 指定 Python。令牌只从被 Git 忽略的 `vendor/remote-test-token.txt` 读取，不写入脚本或仓库。地址为临时 Quick Tunnel，每次重新建通道可能变化。

`fairy_runtime.py` 统一模型路径、加载和请求配置，供基准脚本及 `server.py` 复用。服务器只绑定 `127.0.0.1:8765`，启动时预热 Fairy 原版模型，随后通过 `/stream` WebSocket 分段发送 32 kHz、单声道、16 位 PCM。测试令牌首次启动时生成在忽略的 `vendor/remote-test-token.txt`，客户端需在首条消息中发送令牌。主应用会将任意长度回答分段发送，不限制回答总字数。

同一地址现提供 `/agent` HTTP 接口，供手机连接当前电脑 DSH Host。Agent 使用独立的 `vendor/agent-mobile-token.txt` Bearer 令牌，网关从电脑外部文件读取桥接令牌，仅转发允许的会话操作至 `127.0.0.1:18765`。启动脚本会生成并显示 Agent 令牌和地址。手机首次进入 Agent 页面时填写该令牌；语音播放设置中的地址仍为 `wss://…/stream`，Agent 自动换算为同域 `https://…/agent`。可运行 `python voice-pc/test_agent_gateway.py` 做隔离测试、`python voice-pc/verify_agent_gateway.py` 做本机真实 Host 只读验证。

```powershell
& '<虚拟环境>\Scripts\python.exe' voice-pc\server.py
& '<虚拟环境>\Scripts\python.exe' voice-pc\client_benchmark.py ws://127.0.0.1:8765/stream
```

手机测试页是 `web_test.html`。同一 WebSocket 连接可连续请求，第二次起可直接观察连接复用后的耗时；命令行用 `--repeats 2` 测同一连接的两次请求。临时公网入口可用 cloudflared Quick Tunnel 连接本地服务；网址会变动，没有运行保证。网页的令牌可放在链接的 `#token=...` 片段中，页面读后会立即清除片段。该片段不随 HTTP 请求发送。公网入口用于实验，不作为正式部署；具体测量及局限见《../测试/Fairy-异网语音传输.md》。
