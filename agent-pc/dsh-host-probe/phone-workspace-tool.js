import { readFileSync } from 'node:fs'

export const name = 'hifairy-phone-workspace-tool'
export const inject = ['tools']

export function apply(ctx, config) {
  if (!config?.tokenFile) throw new Error('HiFairy phone tool requires tokenFile')
  const token = readFileSync(config.tokenFile, 'utf8').trim()
  const output = { schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false },
    render: (_args, value) => [{ type: 'text', text: value.text }] }
  async function command(sessionId, body) {
    const response = await fetch(`http://127.0.0.1:${config.port ?? 18765}/sessions/${sessionId}/phone-workspace-command`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: AbortSignal.timeout(33000)
    })
    if (!response.ok) return { text: `手机工作区桥接返回 ${response.status}` }
    return response.json()
  }
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_probe',
    description: '用户明确要求测试手机本地挂载工作区时使用。仅在当前手机 Agent 会话前台执行：统计当前目录全部条目、拒绝越界路径，并创建和覆盖一个 HiFairy-agent-test- 前缀的测试文本。不会修改其他文件。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output,
    timeoutMs: 35000,
    async execute(_args, exec) {
      const sessionId = exec?.agent?.session?.id
      if (!sessionId) return { text: '无法识别当前 DSH 会话。' }
      const response = await fetch(`http://127.0.0.1:${config.port ?? 18765}/sessions/${sessionId}/phone-workspace-probe`, {
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
