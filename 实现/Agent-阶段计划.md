# HiFairy Agent 模式实施阶段

2026-11-20 开始。目标：手机 HiFairy 与电脑端同一个 DSH Host 共享会话，在电脑指定工作区执行任务；语音与 Agent 共用对外网址。

| 阶段 | 交付与验证门槛 |
| --- | --- |
| 0. 同会话接口 PoC | 固定 DSH 版本，确认电脑 Host 的会话列表、默认工作区、事件流与创建/继续接口；手机/桥接创建的会话必须能在电脑 DSH 界面继续。 |
| 1. 电脑 Agent 网关 | 单一对外网址按路径承载语音与 Agent；独立认证；桥接 Host 会话、事件、取消和批准；工作区由电脑配置。用隔离测试目录验证。 |
| 2. 手机 Agent 基础界面 | 新对话眼徽长按 5 秒切换 Chat/Agent；先实现明确的模式状态、会话列表、消息与工具事件、断线重连，再细化中心扩散动画。 |
| 3. DSH 控件与互通 | 手机显示电脑 DSH 的模式、模型和权限，处理批准请求；双端同会话读写、停止、恢复及附件引用。Fairy 自定义模式与人格/知识库留到后续独立阶段。 |
| 4. 异网与体验验收 | 共用公网入口，测连接、首事件、完整任务和断线恢复；接入现有语音，完善眼徽与主题转场动效。 |

阶段 0 已核实：运行中的 DSH Desktop 是 `0.2.0-rc.2`，主程序位于 `D:\DSH\DeepSeek Harness.exe`，Host 进程使用 `D:\dsh-data\profiles\desktop`；`C:\Users\admin\.dsh` 是指向 `D:\dsh-data` 的 junction，两者为同一份数据。默认工作区由 `D:\dsh-data\storages\workspace.json` 指向 `D:\dsh-data\workspace`，已有多条桌面会话。捆绑命令位于 `D:\DSH\resources\runtime\cli\bin\dsh.cmd`，只是未注册到 PATH。先前 npm 预览版下载已按用户要求停止，后续复用现有桌面安装。现有 `dsh-fairy-zzz` 插件仅作为电脑端既有配置，不作为手机 Agent 的 Fairy 人格依赖。

桌面 Host 是 Electron 私有本地服务：运行进程监听本机动态端口，未认证读取返回 HTTP 401。官方桌面文档说明 Host 凭据只由所属应用窗口附带，不保存在普通浏览器 Cookie 中。因此阶段 0 的下一项验证是：在当前 `0.2.0-rc.2` 版本下，通过受控 DSH Host 插件或官方 Remote API 获取同一批会话、预设和权限，而不是从手机直接请求私有端口或读写 `.zstd` 会话文件。该验证尚未完成，暂不改手机 Agent 界面。安装新插件前先在隔离 profile 验证其接口与认证，再接入现有桌面配置。

已建立 `agent-pc/dsh-host-probe/` 只读 Host 插件 PoC：依赖 DSH 的 `sessionController` 服务，独立监听 `127.0.0.1:18765`，仅 `GET /sessions` 且必须带独立 Bearer 令牌；只返回会话 ID、标题和工作目录，不暴露原始事件或工具执行。Node 静默单元测试验证未经认证不调用 Host 服务、授权后仅返回允许字段。**该测试使用模拟控制器，尚未将插件加载到正在运行的 DSH Desktop，也未证明真实会话互通。**下一步需在隔离 DSH profile 校验当前版本的服务注入与响应字段，再安装到桌面 profile 并重启验证；如果电脑有运行中的任务，不能直接为加载插件而重启。
