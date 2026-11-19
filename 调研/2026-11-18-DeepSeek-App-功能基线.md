# DeepSeek App 功能基线与 HiFairy 优先级

调研日期：2026-11-18。依据 DeepSeek 官方 App 公告、官方聊天应用开发指南和官方开源示例。

| 能力 | 官方 App / 示例 | HiFairy 当前状态 | 建议顺序 |
| --- | --- | --- | --- |
| 流式文本聊天 | 示例支持 | 已有基础接入 | 优先完善异常、停止、重试 |
| 历史会话 | App 跨端同步；示例重命名、删除、导入导出 | 本机持久化、重命名、删除；尚无同步与导入导出 | 后续考虑跨端 |
| Markdown、代码、公式 | 示例支持 Markdown 与公式 | 本次接入 Markdown / LaTeX | 真机验收，后续代码复制与高亮 |
| 深度思考 | App 支持 Deep-Think | 可配置思考强度；思考过程单独折叠展示 | 真机验收 |
| 联网搜索与引用 | App 支持搜索；API 有内建工具 | 已接入显式开关、引用展示和会话保存；待真机 API 验证 | 当前阶段验收 |
| 文件与图片输入 | App 支持文件上传和提取 | 已接入附件选择、上传状态、图片/文档/文本上传及历史追问；待真机 API 验证 | 当前阶段验收 |
| 语音输入与播报 | 官方示例支持 | Paraformer 语音输入、电脑端 Fairy GPT-SoVITS 异网播报已接入；中英文音色已由用户试听认可 | 后续稳定服务入口 |
| 消息操作 | 示例支持编辑、重新生成、续答 | 已支持编辑用户消息、停止、重新生成和复制回答；续答未接入 | 后续增加续答 |

联网搜索使用 `deepseek-flash` 的 `deepseek_web_search` 与 `deepseek_read_url` 服务端工具。官方文档说明工具调用本身不另计费，模型输入输出 token 仍计费；模型会自行决定是否搜索。来源以回答后的链接列表显示并随会话持久化，服务端工具调用元数据原样传入后续请求，以保留网页上下文。此功能待真实 API 账号与手机验收。上表中的“官方示例”功能不等于 HiFairy 已实现，也不保证 App 每个版本界面完全一致。

附件通过官方 Files API 上传后以 `file_id` 发送给 `deepseek-flash`，每条消息最多选择 5 个。图片和文本/表格使用 `user_data`；PDF、Word、PowerPoint 使用 `rich_layout_recognition`，保留文中图表。文档解析成功可能按页计费，后续请求还会计入模型输入 token；文件 ID 与名称保存到本地会话，供追问使用。上传失败时不会发送该附件。远端原文件无法通过 Files API 下载，历史页只显示文件名。图片/文件格式与大小限制以官方文档为准。

资料：

- [DeepSeek App 官方公告](https://api-docs.deepseek.com/news/news250115/)
- [DeepSeek 官方聊天应用开发指南](https://api-docs.deepseek.com/guides/build_a_chatbot/)
- [DeepSeek Web Tools 文档](https://api-docs.deepseek.com/guides/web_tools/)
- [DeepSeek Files API](https://api-docs.deepseek.com/guides/files_api/)
- [DeepSeek File Parsing](https://api-docs.deepseek.com/guides/document_parsing/)
- [DeepSeek 官方开源聊天示例](https://github.com/deepseek-ai/chat-demo)
- [Markwon Markdown 渲染库](https://github.com/noties/Markwon)
