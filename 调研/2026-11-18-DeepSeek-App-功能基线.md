# DeepSeek App 功能基线与 HiFairy 优先级

调研日期：2026-11-18。依据 DeepSeek 官方 App 公告、官方聊天应用开发指南和官方开源示例。

| 能力 | 官方 App / 示例 | HiFairy 当前状态 | 建议顺序 |
| --- | --- | --- | --- |
| 流式文本聊天 | 示例支持 | 已有基础接入 | 优先完善异常、停止、重试 |
| 历史会话 | App 跨端同步；示例重命名、删除、导入导出 | 本机持久化、重命名、删除；尚无同步与导入导出 | 后续考虑跨端 |
| Markdown、代码、公式 | 示例支持 Markdown 与公式 | 本次接入 Markdown / LaTeX | 真机验收，后续代码复制与高亮 |
| 深度思考 | App 支持 Deep-Think | 可配置思考强度；思考过程单独折叠展示 | 真机验收 |
| 联网搜索与引用 | App 支持搜索；API 有内建工具 | 未接入 | 在基础聊天稳定后接入 |
| 文件与图片输入 | App 支持文件上传和提取 | 未接入 | 搜索后接入 |
| 语音输入与播报 | 官方示例支持 | Paraformer 语音输入已接入；播报正在独立测试 | 先完成真机 ASR 验收，再比较播报音色与延迟 |
| 消息操作 | 示例支持编辑、重新生成、续答 | 已支持编辑用户消息、停止、重新生成和复制回答；续答未接入 | 后续增加续答 |

当前阶段先保证可读、可滚动、可稳定发送。上表中的“官方示例”功能不等于 HiFairy 已实现，也不保证 App 每个版本界面完全一致。API 的搜索、文件和语音能力可能产生额外费用，实现前应在设置中明确显示。

资料：

- [DeepSeek App 官方公告](https://api-docs.deepseek.com/news/news250115/)
- [DeepSeek 官方聊天应用开发指南](https://api-docs.deepseek.com/guides/build_a_chatbot/)
- [DeepSeek 官方开源聊天示例](https://github.com/deepseek-ai/chat-demo)
- [Markwon Markdown 渲染库](https://github.com/noties/Markwon)
