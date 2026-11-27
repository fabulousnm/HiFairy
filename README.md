# HiFairy

HiFairy 是面向华为 nova 14 卓易通环境的 Android APK。Chat 使用 DeepSeek API；长按眼徽 2 秒可切换到 YOUKAI Agent，通过电脑上的 DeepSeek Harness（DSH）执行任务。首个版本的手机应用操作功能已撤回：**不提供跨应用读取、点击或输入**。手机文件工具只操作用户在设置中手动挂载的普通文件夹。

## 下载与使用范围

从 [GitHub Releases](https://github.com/fabulousnm/HiFairy/releases) 下载 `HiFairy-v1.0.0-arm64.apk`。已在 nova 14 的卓易通环境开发和验证；其他鸿蒙设备、原生 HAP、其他 Android 兼容层尚未逐一验证。首次正式版使用独立发布签名，如果之前安装的是调试 APK，系统可能要求先卸载旧版；请先备份需要保留的本机设置和聊天记录。

## 手机端：Chat

1. 在卓易通中安装 APK，授予应用实际需要的麦克风等权限。
2. 打开左侧历史抽屉 → 设置 → 隐私安全，填写自己的 **DeepSeek API Key**。密钥保存在手机本机的加密存储中。
3. 在设置中从服务端返回的列表选择模型及思考强度，然后开始对话。`deepseek-flash` 支持本项目的附件与“接入绳网”入口；实际模型能力和 API 计费由 DeepSeek 决定。
4. 语音输入在手机本地使用 Paraformer；第一次使用可能需要模型加载。语音播报需要电脑端 Fairy 服务，按下文配置。

## 电脑端：DSH 插件与公网入口

电脑必须运行 DSH Desktop、HiFairy 桥接插件及按需启动的网关。手机和电脑可以不在同一局域网，但电脑关机、DSH 退出或公网通道关闭后 Agent 不可用。当前使用 Cloudflare **临时** Quick Tunnel；每次重新建通道，网址可能变化，需在手机更新地址。

### 安装插件

在 DSH Desktop 的“插件 → 添加插件”安装 `hifairy-dsh-bridge`。如果市场尚未收录，可下载本 Release 附带的 `hifairy-dsh-bridge-0.1.0.tgz`，在添加插件界面选择该本地包；也可在支持的 profile 中运行 `dsh plugin --profile <profile> add <tgz路径>`。**DSH Desktop 不一定使用 `web` profile**，优先在 Desktop 界面安装。旧的 `hifairy-dsh-host-probe` 插件需先停用或移除，避免端口冲突；安装后重启 DSH。

插件启动时自动在用户目录的 `.hifairy/bridge.token` 生成电脑内部桥接令牌；网关自动读取它，用户无需手工填入 DSH 配置。这个内部令牌不同于手机端 Agent 访问令牌。若要更改路径，在启动 DSH 和 HiFairy 网关之前，给两个进程都设置 `HIFAIRY_BRIDGE_TOKEN_FILE` 环境变量。插件与网关默认使用电脑本机 `127.0.0.1:18765` 通信。

### 启动网关

1. 安装 Python 3 和依赖：仅 Agent 模式运行 `python -m pip install fastapi uvicorn httpx`。需要 Fairy 语音时，还需自行配置项目 [`voice-pc`](voice-pc/README.md) 所需的 GPU 推理环境、模型权重和参考音频；权重没有随源码或插件发布。
2. 安装 [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/) 并加入 `PATH`，或将 Windows 可执行文件放在 `voice-pc/vendor/cloudflared-windows-amd64.exe`。Quick Tunnel 无固定域名和在线保证。
3. 先打开 DSH Desktop，确认桥接插件已启用。然后双击仓库根目录的 `启动HiFairy语音服务器.cmd`。脚本先找 `HIFAIRY_PYTHON`，再找 `voice-pc/.venv/Scripts/python.exe`，最后找 `PATH` 中的 Python；也可用 `-PythonPath` 参数直接运行 `voice-pc/start-hifairy-server.ps1`。
4. 选择 **2**：只启动 Agent 网关与公网通道；选择 **1**：同时加载 Fairy 语音模型。等待脚本打印公网地址、Agent 令牌；模式 1 还会打印语音访问令牌。保持该窗口开启，按回车或关闭窗口会结束本次通道和服务进程。

### 手机连接

1. 在手机 Chat 的设置 → 语音播放，填脚本输出的 `wss://…/stream` 地址。即使选择了“仅网关”，Agent 也从这个地址推导同域的 `/agent` 地址；此时语音播放不可用。
2. 在设置 → 隐私安全，填脚本输出的 **电脑 Agent 访问令牌**。模式 1 若要播报，再填 **语音服务访问令牌**。不要把令牌贴进公开 issue 或提交到仓库。
3. 长按新对话中央眼徽 2 秒切换到 Agent。新对话可选择 DSH 当前提供的模式、工作区和权限预设；列表在进入新对话页时刷新。历史抽屉按需拉取最近 15 轮，当前会话用实时连接推送输出与审批。
4. 需要手机工作区工具时，在设置 → 手机工作区手动挂载一个**卓易通兼容区内可读写的普通文件夹**。`我的设备（鸿蒙）`映射目录与系统敏感目录不作为可写工作区。只在当前手机 Agent 会话可接收文件请求时，DSH 才能操作这个挂载目录。

## 开发与构建

Android 工程使用 Kotlin/Compose、JDK 21、Android SDK 37。`app` 是正式 APK；`voice-lab`、`tts-lab`、`workspace-lab` 是隔离测试包。Paraformer 模型和 sherpa AAR 放在被 Git 忽略的 `voice-lab` 本地路径，克隆仓库后需先按[模型准备脚本](voice-lab/prepare-models.ps1)准备，或直接下载 Release APK。

```powershell
$env:ANDROID_HOME = '<Android SDK 目录>'
./gradlew.bat :app:assembleDebug
cd agent-pc/dsh-host-probe
npm test
```

发布版用独立签名密钥；签名环境变量是 `HIFAIRY_RELEASE_KEYSTORE`、`HIFAIRY_RELEASE_STORE_PASSWORD`、`HIFAIRY_RELEASE_KEY_ALIAS`、`HIFAIRY_RELEASE_KEY_PASSWORD`。密钥与密码不要入库。电脑插件源码在 [`agent-pc/dsh-host-probe`](agent-pc/dsh-host-probe/README.md)；它打成 tgz 后可以单独安装和移除。

## 素材与授权

应用使用《绝区零》Fairy/YOUKAI 风格参考及相关视觉、语音素材，是个人非官方项目，不代表游戏发行方或 DeepSeek。DSH 插件目录内代码按其 `LICENSE` 发布；应用素材不因该插件许可而获得再授权。发布或分发前请核对素材来源及适用授权。
