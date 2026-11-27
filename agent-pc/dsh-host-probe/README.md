# HiFairy DSH Bridge

DSH 插件，将当前电脑的会话、模式、工作区、审批和文件浏览接入 HiFairy 手机端，并为手机端手动挂载的工作区注册受限文件工具。本插件只监听 `127.0.0.1:18765`，不直接对公网开放。

## 安装

在 DSH Desktop 的“插件 → 添加插件”中安装 `hifairy-dsh-bridge` 的发布包，或在支持的 DSH profile 中执行：

```sh
dsh plugin --profile web add <发布包地址>
```

安装后重启 DSH。旧版 `hifairy-dsh-host-probe` 应先停用或移除，否则两个桥接器会争用同一端口。移除本插件时，DSH 的会话和工作区数据不会删除。

首次运行会在用户目录的 `.hifairy/bridge.token` 生成本机桥接令牌。HiFairy 电脑网关默认读取同一文件；可通过 `HIFAIRY_BRIDGE_TOKEN_FILE` 同时覆盖两端路径。令牌不要提交到仓库。

手机 Agent 的公网地址和独立访问令牌由 HiFairy 电脑启动脚本生成。完整步骤见[项目 README](https://github.com/fabulousnm/HiFairy#readme)。

## 能力边界

- 手机当前会话的增量输出、最近 15 轮历史、模式和工作区选择、权限预设与审批。
- 电脑文件抽屉；电脑工作区外的文件入口须由用户主动选择。
- 手机工作区工具只在手机当前 Agent 会话接收请求时执行，受手机端手动挂载目录限制。
- 不包含手机应用控制或无障碍服务。

`npm test` 运行桥接、历史折叠、文件路径和审批协议测试。
