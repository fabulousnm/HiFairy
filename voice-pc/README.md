# Fairy 电脑端原版权重基准

## 一键启动与关闭

双击仓库根目录的 `启动HiFairy语音服务器.cmd`，选择 `1` 加载 Fairy 模型、Agent 网关和临时公网通道，或选择 `2` 仅启动轻量 Agent 网关与临时公网通道。第二种模式不会加载模型，语音播放不可用。脚本打印本次公网地址与对应令牌；按回车或直接关闭窗口，会关闭本次启动的网关、通道与模型子进程。已有服务占用 8765 端口时脚本会提示先关闭旧服务，以免误清理不归它管理的进程。命令行可用 `-Mode Full`、`-Mode Gateway` 和 `-StatusOnly`；隔离测试可用 `-Port` 指定其他端口。当前仍是随机域名 Quick Tunnel；固定域名选型见 `调研/2026-11-27-按需固定域名公网通道.md`。

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

启动脚本复用通道前同时验证 `/health` 和带令牌的 `/agent/sessions`；仅健康页可访问而 Agent 请求超时时会重建通道。新建通道固定使用 Cloudflare HTTP/2 传输，规避这台电脑上 QUIC 通道长时间运行后大响应卡住的问题。Quick Tunnel 的随机域名在重建后会变化，手机需按脚本新输出更新服务地址，原令牌不变。

`fairy_runtime.py` 统一模型路径、加载和请求配置，供基准脚本及 `server.py` 复用。服务器只绑定 `127.0.0.1:8765`，启动时预热 Fairy 原版模型，随后通过 `/stream` WebSocket 分段发送 32 kHz、单声道、16 位 PCM。测试令牌首次启动时生成在忽略的 `vendor/remote-test-token.txt`，客户端需在首条消息中发送令牌。主应用会将任意长度回答分段发送，不限制回答总字数。

同一地址现提供 `/agent` HTTP 接口，供手机连接当前电脑 DSH Host。Agent 使用独立的 `vendor/agent-mobile-token.txt` Bearer 令牌，网关从电脑外部文件读取桥接令牌，仅转发允许的会话操作至 `127.0.0.1:18765`。启动脚本会生成并显示 Agent 令牌和地址。手机首次进入 Agent 页面时填写该令牌；语音播放设置中的地址仍为 `wss://…/stream`，Agent 自动换算为同域 `https://…/agent`。可运行 `python voice-pc/test_agent_gateway.py` 做隔离测试、`python voice-pc/verify_agent_gateway.py` 做本机真实 Host 只读验证。

Agent 实时回复使用同域 WebSocket `wss://…/agent/live/{sessionId}`，首条消息发送 Agent 令牌；服务端从本地 DSH SSE 逐帧转发。Cloudflare Quick Tunnel 不支持 SSE，所以手机不能直接使用 `/agent/sessions/{id}/events` 的 HTTP 事件流。可用 `python voice-pc/verify_agent_websocket.py --base-url <https://.../agent> --long` 测公网首增量与最终消息。

Agent 历史目录 `GET /agent/sessions` 只返回会话元数据；打开某条会话时 `GET /agent/sessions/{id}/recent` 返回最近 15 轮真实用户提问、对应完整回答及折叠记录，内部通知和工具调用不占轮数。展开思考、工具、终端转录或工作说明后再访问 `GET /agent/sessions/{id}/details/{reasoning|tool|terminal|commentary}/{key}`。实时 WebSocket 不转发整份快照，避免重复传输。电脑 DSH 重新启动后才会加载桥接插件文件的改动。

新任务页从电脑 DSH 的 `agentPresets.list()` 和 `workspaceRegistry.list()` 读取模式及已登记工作区：`GET /agent/modes`、`GET /agent/workspaces`。应用启动后首次进入新任务页、以及每次点击新任务进入该页时各读取一次，不在页面内定时轮询。电脑端的增删会在下一次进入新任务页时反映出来。创建会话时 `POST /agent/sessions` 传 `agentPreset` 和 `workspaceId`，Host 再以当前列表校验，避免使用过期选择；未传参数的旧客户端沿用 Host 默认模式和配置工作区。此选择只作用于新会话，不改变电脑 DSH 的全局默认设置。

新任务页在输入框上方以紧凑图标按钮显示当前模式与工作区；选项向上弹出，当前选择用主题色标记。DSH 的 `step/start` 通常先于该轮 `user/message` 发出，历史记录会把这条步骤展示在对应用户消息之后，仍保持步骤内部的工具、思考与回答顺序。

手机发送消息使用 HTTPS POST，电脑 DSH 的当前会话事件通过本地 SSE → 同域 WebSocket 推送给手机。手机仅对当前打开的会话建立实时订阅；抽屉中的其他会话仅保留轻量元数据，点开后再拉最近 15 轮与按需详情。WebSocket 首帧仅用于令牌认证和后续服务端推送，业务发送并不走双向 WebSocket。历史列表在打开、当前任务结束及实时连接重试时刷新。

`recent` 中的记录可带 `parentId`，表示归属的 `work-{turn}-{step}`；手机默认只显示步骤父级。工具详情返回 `input`、`result` 和 `terminal`，终端调用分别展示并复制输入和输出；其他详情用应用共用的 Markdown 渲染。

```powershell
& '<虚拟环境>\Scripts\python.exe' voice-pc\server.py
& '<虚拟环境>\Scripts\python.exe' voice-pc\client_benchmark.py ws://127.0.0.1:8765/stream
```

手机测试页是 `web_test.html`。同一 WebSocket 连接可连续请求，第二次起可直接观察连接复用后的耗时；命令行用 `--repeats 2` 测同一连接的两次请求。临时公网入口可用 cloudflared Quick Tunnel 连接本地服务；网址会变动，没有运行保证。网页的令牌可放在链接的 `#token=...` 片段中，页面读后会立即清除片段。该片段不随 HTTP 请求发送。公网入口用于实验，不作为正式部署；具体测量及局限见《../测试/Fairy-异网语音传输.md》。
