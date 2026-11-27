import { bridgeToken } from './bridge-token.js'

export const name = 'hifairy-phone-workspace-tool'
export const inject = ['tools']

export function apply(ctx, config) {
  const token = bridgeToken(config)
  const output = { schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false },
    render: (_args, value) => [{ type: 'text', text: value.text }] }
  async function command(sessionId, body) {
    const response = await fetch(`http://127.0.0.1:${config?.port ?? 18765}/sessions/${sessionId}/phone-workspace-command`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: AbortSignal.timeout(33000)
    })
    if (!response.ok) return { text: `手机工作区桥接返回 ${response.status}` }
    return response.json()
  }
  function registerWorkspaceTool(toolName, description, properties, required, makeBody) {
    ctx.effect(() => ctx.tools.register({
      name: toolName, description,
      parameters: { type: 'object', properties, required, additionalProperties: false },
      output, timeoutMs: 35000,
      execute(args, exec) {
        const sessionId = exec?.agent?.session?.id
        if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
        return command(sessionId, makeBody(args))
      }
    }))
  }
  const path = { type: 'string', description: '手机设置中手动挂载目录内的相对路径；根目录填空字符串，子目录用 / 分隔' }
  registerWorkspaceTool('hifairy_phone_list',
    '当用户要求查看手机端挂载文件夹、列手机工作区文件或查找其中的文件时直接使用。按需分页，只读取手动挂载目录及子目录，不访问电脑工作区。',
    { path, offset: { type: 'integer', description: '分页起点，首次为 0；根据返回的下一页 offset 继续' } }, ['path', 'offset'],
    args => ({ action: 'list_path', path: args.path, offset: args.offset }))
  registerWorkspaceTool('hifairy_phone_read_text',
    '当用户要读取或分析手机端挂载文件夹中的文本、Markdown、JSON 或代码文件时直接使用。只读相对路径，单次最多 12 KB。',
    { path }, ['path'], args => ({ action: 'read_text', path: args.path }))
  registerWorkspaceTool('hifairy_phone_write_text',
    '当用户说“输出到手机端的挂载文件夹中”“保存到手机工作区”或要求修改其中的文本文件时直接使用。只能写入已手动挂载目录内的 UTF-8 文本；新建时 overwrite=false，用户明确要求覆盖现有文件时才设 true；最多 8 KB。',
    { path, content: { type: 'string', description: '要写入的完整 UTF-8 文本，最多 8 KB' },
      overwrite: { type: 'boolean', description: '只在用户明确要求覆盖现有文件时设 true；新建设 false' } },
    ['path', 'content', 'overwrite'], args => ({ action: 'write_text', path: args.path, content: args.content, overwrite: args.overwrite }))
  registerWorkspaceTool('hifairy_phone_mkdir',
    '当用户要求在手机端挂载文件夹中新建子文件夹时直接使用；不能覆盖已有条目。',
    { path }, ['path'], args => ({ action: 'mkdir', path: args.path }))
  registerWorkspaceTool('hifairy_phone_rename',
    '仅当用户明确要求重命名手机端挂载工作区的文本文件时使用；在同一父目录内先复制并校验，再删除原文件。不能重命名目录或覆盖已有条目，文件上限 12 KB。',
    { path, newName: { type: 'string', description: '同一目录内的新名称，不含斜杠' } },
    ['path', 'newName'], args => ({ action: 'rename', path: args.path, newName: args.newName }))
  registerWorkspaceTool('hifairy_phone_delete_file',
    '仅当用户明确要求删除手机端挂载工作区的某个文件时使用。只删除单个文件，不删除目录或递归删除。',
    { path }, ['path'], args => ({ action: 'delete_file', path: args.path }))
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_batch_probe',
    description: '用户要求一次验证六项手机工作区能力时使用：分页列目录、新建测试子目录、新建测试文本、读取、覆盖、进入子目录并返回。只在当前手机 Agent 会话前台运行，只留下 HiFairy-agent-test- 前缀的测试目录和文本，不修改已有文件。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output,
    timeoutMs: 35000,
    execute(_args, exec) {
      const sessionId = exec?.agent?.session?.id
      if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
      return command(sessionId, { action: 'batch_probe' })
    }
  }))
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_probe',
    description: '用户明确要求测试手机本地挂载工作区时使用。仅在当前手机 Agent 会话前台执行：统计当前目录全部条目、拒绝越界路径，并创建和覆盖一个 HiFairy-agent-test- 前缀的测试文本。不会修改其他文件。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output,
    timeoutMs: 35000,
    async execute(_args, exec) {
      const sessionId = exec?.agent?.session?.id
      if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
      const response = await fetch(`http://127.0.0.1:${config?.port ?? 18765}/sessions/${sessionId}/phone-workspace-probe`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(33000)
      })
      if (!response.ok) return { text: `手机工作区桥接返回 ${response.status}` }
      return response.json()
    }
  }))
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_files',
    description: '分页列出手机手动挂载工作区当前目录的全部文件名，没有总条数上限。首次 offset=0；返回下一页 offset 后可继续调用。只在手机当前 Agent 会话前台可用。',
    parameters: { type: 'object', properties: { offset: { type: 'integer', description: '从第几个条目开始，首次传 0' } },
      required: ['offset'], additionalProperties: false },
    output,
    timeoutMs: 35000,
    execute(args, exec) {
      const sessionId = exec?.agent?.session?.id
      if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
      return command(sessionId, { action: 'list', offset: args.offset })
    }
  }))
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_test_write',
    description: '仅在手机当前 Agent 会话前台，对用户明确指定的 HiFairy-agent-test-*.txt 测试文件覆盖写入并读回校验。其他文件、目录和绝对路径均拒绝。先用 hifairy_phone_workspace_files 查看文件名。',
    parameters: { type: 'object', properties: {
      name: { type: 'string', description: '挂载目录中已有的 HiFairy-agent-test-*.txt 文件名，不含路径' },
      content: { type: 'string', description: '写入的测试文本，最多 2000 字符' }
    }, required: ['name', 'content'], additionalProperties: false },
    output,
    timeoutMs: 35000,
    execute(args, exec) {
      const sessionId = exec?.agent?.session?.id
      if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
      return command(sessionId, { action: 'test_write', name: args.name, content: args.content })
    }
  }))
}
