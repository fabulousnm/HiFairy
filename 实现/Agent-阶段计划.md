# HiFairy Agent 模式实施阶段

2026-11-20 开始。目标：手机 HiFairy 与电脑端同一个 DSH Host 共享会话，在电脑指定工作区执行任务；语音与 Agent 共用对外网址。

| 阶段 | 交付与验证门槛 |
| --- | --- |
| 0. 同会话接口 PoC | 固定 DSH 版本，确认电脑 Host 的会话列表、默认工作区、事件流与创建/继续接口；手机/桥接创建的会话必须能在电脑 DSH 界面继续。 |
| 1. 电脑 Agent 网关 | 单一对外网址按路径承载语音与 Agent；独立认证；桥接 Host 会话、事件、取消和批准；工作区由电脑配置。用隔离测试目录验证。 |
| 2. 手机 Agent 基础界面 | 新对话眼徽长按 2 秒切换 Chat/Agent；复用 Chat 气泡、头像、输入和历史抽屉，接入逐段回复及断线重连；工具事件待后续补齐。 |
| 3. DSH 控件与互通 | 手机显示电脑 DSH 的模式、模型和权限，处理批准请求；双端同会话读写、停止、恢复及附件引用。Fairy 自定义模式与人格/知识库留到后续独立阶段。 |
| 4. 异网与体验验收 | 共用公网入口，测连接、首事件、完整任务和断线恢复；接入现有语音，完善眼徽与主题转场动效。 |

阶段 0 已核实：运行中的 DSH Desktop 是 `0.2.0-rc.2`，主程序位于 `D:\DSH\DeepSeek Harness.exe`，Host 进程使用 `D:\dsh-data\profiles\desktop`；`C:\Users\admin\.dsh` 是指向 `D:\dsh-data` 的 junction，两者为同一份数据。默认工作区由 `D:\dsh-data\storages\workspace.json` 指向 `D:\dsh-data\workspace`，已有多条桌面会话。捆绑命令位于 `D:\DSH\resources\runtime\cli\bin\dsh.cmd`，只是未注册到 PATH。先前 npm 预览版下载已按用户要求停止，后续复用现有桌面安装。现有 `dsh-fairy-zzz` 插件仅作为电脑端既有配置，不作为手机 Agent 的 Fairy 人格依赖。

桌面 Host 是 Electron 私有本地服务：运行进程监听本机动态端口，未认证读取返回 HTTP 401。官方桌面文档说明 Host 凭据只由所属应用窗口附带，不保存在普通浏览器 Cookie 中。因此阶段 0 的下一项验证是：在当前 `0.2.0-rc.2` 版本下，通过受控 DSH Host 插件或官方 Remote API 获取同一批会话、预设和权限，而不是从手机直接请求私有端口或读写 `.zstd` 会话文件。该验证尚未完成，暂不改手机 Agent 界面。安装新插件前先在隔离 profile 验证其接口与认证，再接入现有桌面配置。

已建立 `agent-pc/dsh-host-probe/` 只读 Host 插件 PoC：依赖 DSH 的 `sessionController` 服务，独立监听 `127.0.0.1:18765`，仅 `GET /sessions` 且必须带独立 Bearer 令牌；只返回会话 ID、标题和工作目录，不暴露原始事件或工具执行。Node 静默单元测试验证未经认证不调用 Host 服务、授权后仅返回允许字段。

2026-11-20 Host 接入验证：用户确认可重启后，已将本地插件目录链接到现有 DSH Desktop profile，并列入其 bundle；离线 pnpm 安装因现有第三方包缺少离线元数据而失败，未改动原 manifest/lock，改用可撤回的本地目录链接。独立令牌生成于 `D:\dsh-data\hifairy-agent-probe.token`，不进入仓库。重启后 `127.0.0.1:18765` 成功监听；`verify.ps1` 实际返回 Host 会话 37 条，其中 21 个 ID 与桌面工作区存储一致；无令牌请求得到 HTTP 401。当前桥接仅可在电脑本机访问，未暴露公网。

电脑端验证（2026-11-20）：使用本机安装的 DSH `0.2.0-rc.2` Session Controller 类型定义，桥接新增受令牌保护的 `POST /sessions`、`POST /sessions/:id/messages`、`GET /sessions/:id/snapshot` 和 `GET /sessions/:id/events`。最初只传测试子目录 `cwd` 创建的会话虽能回答，但未出现在桌面工作区列表；同时传 `cwd` 与 `workspaceId` 被 Host 明确拒绝。最终改为仅传电脑默认 `workspaceId`，新会话收到回答，且同时出现在 Host 列表和桌面工作区记录。持续事件流测试在连接保持期间发送第二条消息，实时收到 `assistant/message` 和 `turn/end`，脚本 `verify-live.mjs` 输出三项均为 true。单元测试 3 项通过。**已验证电脑端同 Host 创建、发送及实时事件闭环**；此 PoC 桥接使用本机回环端口和独立令牌，不直接对公网开放。

2026-11-20 阶段 1/2 最小接入：语音 FastAPI 新增同源 `/agent` 受限网关，路由仅允许会话列表、创建、快照、消息和 SSE；手机令牌保存在被 Git 忽略的 `voice-pc/vendor/agent-mobile-token.txt`，桥接令牌继续保存在 `D:\dsh-data\hifairy-agent-probe.token`，网关只转发到电脑回环端口。独立测试验证 401、路径白名单及上游令牌替换；真实 Host 验证授权后返回 39 条会话和测试会话快照。本机 FastAPI 入口又完成创建、发送、收到回答、SSE 开流的闭环，公网 Quick Tunnel 上授权会话列表返回 40 条。Android 新增 Agent 界面：新对话眼徽长按 2 秒进入，Agent 眼徽长按 2 秒返回；读取电脑会话、创建绑定桌面工作区的新会话、发送文本并监听实时通道，断线后重连和刷新快照。地址沿用设置中语音 WebSocket 地址的同一域，Agent 令牌在首次进入时单独输入并加密保存。`app:assembleDebug` 通过。当前消息界面使用 Host 的 `turnOutline` 显示已完成回合，工具事件、权限批准、停止任务、模式选择和转场动画仍留待后续阶段；因此尚不把手机端完整 Agent 验收标记为完成。

2026-11-20 用户复测后修正：切换长按缩短为 2 秒。实测 DSH `/events` 在最终 `assistant/message` 之前发送多条 `assistant-stream`，其中 `frame.chunk.type=text-delta`；旧手机页只监听最终消息并从 `turnOutline` 取已完成回合，所以等待整条回答结束是客户端解析逻辑导致，并非已经证明网络传输更慢。本机经同域 FastAPI `/agent` 测试：首个文字增量约 2009 ms、最终消息约 2480 ms，共 65 个增量，首增量早于最终消息。Android 现直接累计 `text-delta` 并即时更新回答气泡，最终事件后切换为快照内容。Agent 页复用 Chat 的 `ChatBubble`、`MessageAction`、`HistorySessionSurface`、眼徽状态及录音输入；抽屉同样为 3/4 宽、点击右侧收起并按相同阈值回弹。新任务不预先创建空 DSH 会话，首次发送时创建并等待实时通道已订阅后再投递。真机逐字显示及手势仍需验收。

后续待完成：电脑 Host 的权限选择和审批；任务停止、工具事件细节、Agent 历史会话的重命名/删除能力；长会话与断线中的增量续传。普通 Chat 的“接入绳网”不放进 Agent 页。

### 异网可靠性待办（2026-11-22，暂不实施）

- [ ] 将当前随机域名的 Quick Tunnel 更换为稳定入口。优先评估自有域名下的 Cloudflare 正式 Tunnel；若暂无域名，先在 nova 14 的卓易通环境真机验证 Tailscale 客户端和 VPN 能否稳定连接，再决定是否使用私有组网。两种路线择一，不同时维护两套入口。
- [ ] 电脑端配置开机自启和故障恢复：语音/Agent 网关、DSH Host 桥接及所选通道均须在进程退出或网络恢复后自动重启，并用已认证的 Agent 接口做健康检查；验证重启电脑、断网重连和长时间空闲后的恢复。
- [ ] 若需要电脑睡眠或关机后从手机恢复，再单独设计远程唤醒；在电脑断电、断网且无带外唤醒通道时，手机无法独自恢复本地推理。
- [ ] 切换正式入口前复核鉴权、密钥存储、HTTPS/WebSocket 和真机异网首包延迟；完成后从手机配置及文档清除过期临时地址。

公网流式复核：本机 SSE 可逐段返回，但沿用的 Cloudflare Quick Tunnel 对 SSE 连接超时；[Cloudflare 官方 Quick Tunnel 限制](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)明确说明不支持 SSE。因此 Agent 实时通道改用同一域名的 `wss://…/agent/live/{sessionId}`，首条 WebSocket 消息发送独立 Agent 令牌，电脑 FastAPI 在本机读取 DSH SSE 并逐帧转发到 WebSocket；会话列表、创建和发消息仍走 HTTPS。语音 `/stream` 不受影响。本机及公网首增量已在下一段复核；真机显示仍待验收。

WebSocket 复测通过：本机短回答首增量约 1166 ms、最终约 1478 ms；公网较长回答首增量约 3236 ms、增量持续 2164 ms、最终约 5432 ms，共 319 个增量。错误令牌在握手后以 WebSocket 1008 拒绝。说明公网传输可在回答完成前持续送达；手机端实际绘制与网络切换行为仍需真机验收。测试命令为 `python voice-pc/verify_agent_websocket.py --base-url <https://.../agent> --long`，不会打印令牌或回答正文。

2026-11-20 历史和记录修正：DSH `list()` 的标题位于 `projections.values.title`，旧桥接误读顶层 `title`，导致大量“新任务”。现目录只返回非空会话的轻量元数据；手机仅在选择会话时请求 `/recent`，不再用 `turnOutline` 的截短摘要。Agent 思考、工具调用及工作步骤有独立折叠卡片；工具输入与结果、完整思考在展开时才取 `/details`。WebSocket 快照只传游标，事件只传类型，避免把整份 Host 快照推到手机。眼徽按住时显示 2 秒环形进度，松开复位。桥接 Node 测试、网关隔离测试和 APK 构建通过。

2026-11-21 真机截图反馈后的修正：最近 15 条改为 **15 轮真实提问及其回答**，内部通知和工具调用不占轮数；Host 读取窗口不足时按需扩大，手机仍只收选择的最近轮次。DSH 将运行环境通知、技能目录等也写作 `user/message`，按 `source.kind` 排除；截图中的 PowerShell 报错虽标记为真实用户输入，但识别为终端转录后折叠为“终端记录”，点击可看原文。带工具调用的助手工作说明折叠为工作记录，最终回答保持完整气泡。思考、工具和工作记录只用回答上方靠 Fairy 侧的一行小字，详情按需展开。加载动效移到未加载对话区中央，使用眼徽、环形与线性进度。Node 隔离测试覆盖轮数、内部通知、终端转录和工具过程；真机新布局待复核。

2026-11-21 记录层级和详情：同一步骤的思考、工具调用及工作说明归入该步骤，默认只显示一步一行；展开步骤后每项仍可独立展开。非终端详情复用聊天页 Markdown/公式渲染，详情字号缩小；终端工具的输入、输出各有独立深色终端面板和复制键。首次加载所选会话后等待布局完成再跳到最新消息底部。隔离测试验证步骤归属和终端输入识别；真机布局待复核。

2026-11-21 设置与转场：Agent 的新任务眼徽和历史会话眼徽复用 Chat 的位置、尺寸动画（居中与左上角互换）；两个界面的“设置”均进入 Chat 的设置页。设置新增“隐私安全”子界面，统一加密保存 DeepSeek API Key、语音服务访问令牌和 Agent 令牌。Agent 令牌配置后返回原 Agent 页面。

2026-11-22 YOUKAI 主题：用户以《绝区零》YOUKAI 眼徽截图指定 Agent 色彩方向。第一次几何重绘偏离原图，色彩过艳，遮挡式转场也未达到双图层要求。现直接打包用户提供的眼徽原图，保留其主体、花瓣、光晕及扫描线；共享眼徽在原图上增加外瓣旋转、呼吸、眨眼、扫描、完成亮环与长按进度。Agent 调色板改为低饱和的深酒红、灰玫紫，抽屉历史按钮通过共用的 `HistorySessionSurface` 接收 Agent 专属凸起/按下色，不再套用 Chat 蓝色。Agent Activity 使用透明窗口叠在 Chat 上，Compose 根节点按眼徽坐标圆形裁剪真实 Agent UI；进入时展开，返回时收缩，圆外始终是底层 Chat 界面。已在 Android API 37 模拟器截到返回转场中间帧，确认为双页面同时可见；`app:assembleDebug --offline` 构建通过。nova 14 卓易通的透明窗口表现与帧率仍需真机验收。

2026-11-23 眼徽与语音：YOUKAI 眼徽改用用户提供的独立外环、眼白、瞳孔三层 PNG，外环独立旋转，眼白与瞳孔一起垂直收合以完整眨眼。模拟器截图确认三层比例与中心位置。Agent 回答不再自动或手动播报；单击眼徽查余额时保留预录语音，Agent 余额语音开关与 Chat 自动播报开关分离。问候的模式归属以后续用户修正为准。

2026-11-23 Agent 控制与文件：桥接插件向手机开放 DSH 原生 `sessionController.cancel`，Agent 输入栏在生成中显示停止图标，工作状态同步 DSH 会话 `running`；被本机终止的最新回答下显示继续图标。DSH 对手动取消没有同回合续跑 API，因此继续会提交一条新的上下文续任务消息；这与 Chat 的 Responses 原生 continuation 语义不同，后续需随 DSH 能力升级。附件走 DSH `fileUploads.uploadStream` 暂存凭证，再由 `sessionController.prompt` 引用正式文件块，最多 5 个、单个 25 MiB。右侧电脑文件抽屉仅浏览 DSH 已登记工作区；每次目录跳转先释放旧列表，只请求当前层名称，列表按需渲染，不预读子目录；支持上传到工作区、下载到手机文档和用手机文件关联应用只读预览。电脑路径经 realpath 限定在工作区内，禁止遍历与目录软链逃逸，无删改接口。Node 13 项接口/转换测试、网关测试和 APK 构建通过；当前 DSH Desktop 未运行，真实 Host 联调和 nova 14 验收尚待完成。

2026-11-23 用户修正：Chat 开屏问候保留原“主人”及原始音频；Agent 独立使用“法厄同”文字与三段对应的预生成语音，输入栏称 YOUKAI。YOUKAI 眼白替换为用户新放入仓库根目录的版本。启动脚本不再把 DSH Agent 桥接健康状态当作语音公网通道的前置条件：公网健康后即输出地址和令牌，Agent 桥接未运行时另给警告。`-StatusOnly` 已验证在 DSH 未运行而语音/通道正常时成功退出。

2026-11-27 双侧抽屉与文件浏览：Agent 右侧文件抽屉改用与左侧历史抽屉同一 `DrawerMotion` 回弹参数、位移阈值及 3D 压缩；右侧展开时主聊天页向左移动，两侧不能同时展开。文件抽屉默认打开当前 DSH 工作区，也可切换“此电脑”，从盘符逐层打开任意当前电脑可访问的目录。后端只读取当前目录的文件和子目录名称，手机切换目录先释放旧列表，并使用序号丢弃过期请求；任意目录上传使用独占新建，下载只读，无删改接口，单文件 25 MiB。Agent 顶栏改为 `H.D.D Ⅲ`；Chat/Agent 顶栏移除新对话加号，新对话只从历史抽屉创建。Node 13 项、网关测试和 `assembleDebug` 通过；真机手势与 DSH Desktop 实际文件联调由用户验收。
