# DeepSeek 聊天接入

2026-11-18 已实现首版：设置页输入 DeepSeek API Key、模型 ID 和思考强度；发送文本后调用官方 Chat Completions 流式接口，将 `delta.content` 逐段显示。API Key 使用 Android Keystore AES-GCM 加密后保存在私有 SharedPreferences；模型和强度是普通设置。缺少 Key 时跳转设置页。

当前默认模型为 `deepseek-flash`，思考强度为 `high`，可手动修改为官方支持的 ID 和 `none/low/high/max`。尚未接入模型列表能力查询。对话仅在本次进程内存中保存，重启后不会恢复；历史页也尚未显示会话列表。请求错误显示在会话区，尚未实现取消生成、重试、分页与长会话裁剪。构建通过，真实 API 请求待用户在应用内填写 Key 后验证。
