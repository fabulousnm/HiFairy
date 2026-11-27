import { readFileSync } from 'node:fs'

export const name = 'hifairy-phone-workspace-tool'
export const inject = ['tools']

export function apply(ctx, config) {
  if (!config?.tokenFile) throw new Error('HiFairy phone tool requires tokenFile')
  const token = readFileSync(config.tokenFile, 'utf8').trim()
  ctx.effect(() => ctx.tools.register({
    name: 'hifairy_phone_workspace_probe',
    description: '用户明确要求测试手机本地挂载工作区时使用。仅在当前手机 Agent 会话前台执行：列出少量文件名、拒绝越界路径，并创建和覆盖一个 HiFairy-agent-test- 前缀的测试文本。不会修改其他文件。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output: { schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false },
      render: (_args, value) => [{ type: 'text', text: value.text }] },
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
}
