export const RECENT_TURN_LIMIT = 15
// DSH counts internal notices as messages. Read enough Host events to find 15 real prompts.
export const HOST_MESSAGE_WINDOW = 240

const textOf = blocks => (blocks ?? []).filter(block => block?.type === 'text').map(block => block.text ?? '').join('')
const detailText = value => typeof value === 'string' ? value : JSON.stringify(value ?? '')
const isRealUser = event => event.type === 'user/message' && event.data?.source?.kind === 'user' && event.data?.role === 'user'
const isTerminalTranscript = text => /^(?:PS\s+[^\r\n>]+>|[\w./~:\\-]+\s*[$#>])\s*[^\r\n]+(?:\r?\n|$)/i.test(text)

export function recentItems(snapshot, limit = RECENT_TURN_LIMIT) {
  const events = (snapshot.records ?? []).filter(record => record.type === 'event' && record.event).map(record => record.event)
  const prompts = events.filter(isRealUser)
  const cutoff = prompts.at(-limit)?.seq ?? events[0]?.seq ?? 0
  const selected = events.filter(event => event.seq >= cutoff)
  const items = []
  const pending = []
  const flush = () => { items.push(...pending); pending.length = 0 }
  const tools = new Map()
  const steps = new Map()
  const parentOf = data => {
    const id = `work-${data.turn}-${data.step}`
    return steps.has(`${data.turn}-${data.step}`) ? id : undefined
  }
  const seenMessages = new Set()

  for (const event of selected) {
    const data = event.data ?? {}
    if (isRealUser(event)) {
      flush()
      const id = data.id ?? `user-${event.seq}`
      if (seenMessages.has(id)) continue
      seenMessages.add(id)
      const content = textOf(data.content)
      if (!content) continue
      if (isTerminalTranscript(content)) items.push({ id, kind: 'terminal', title: '终端记录', detailKey: String(event.seq) })
      else items.push({ id, kind: 'user', text: content })
    } else if (event.type === 'assistant/message') {
      const message = data.message ?? {}
      const id = message.id ?? `assistant-${event.seq}`
      if (seenMessages.has(id)) continue
      seenMessages.add(id)
      const reasoning = (message.content ?? []).filter(block => block?.type === 'reasoning').map(block => block.text ?? '').join('')
      if (reasoning) pending.push({ id: `reasoning-${event.seq}`, kind: 'reasoning', title: '思考', detailKey: String(event.seq), parentId: parentOf(data) })
      const content = textOf(message.content)
      if (content && (message.content ?? []).some(block => block?.type === 'tool-call'))
        pending.push({ id: `commentary-${event.seq}`, kind: 'commentary', title: '工作说明', detailKey: String(event.seq), parentId: parentOf(data) })
      else if (content) { flush(); items.push({ id, kind: 'assistant', text: content }) }
    } else if (event.type === 'tool/call') {
      const id = data.callId ?? `tool-${event.seq}`
      const item = { id: `tool-${id}`, kind: 'tool', title: data.name ?? '工具调用', detailKey: id, parentId: parentOf(data), hasResult: false, isError: false }
      tools.set(id, item)
      pending.push(item)
      const step = steps.get(`${data.turn}-${data.step}`)
      if (step) step.tools.push(data.name ?? '工具调用')
    } else if (event.type === 'tool/result') {
      const item = tools.get(data.message?.toolCallId)
      if (item) { item.hasResult = true; item.isError = Boolean(data.message?.isError) }
    } else if (event.type === 'step/start') {
      const key = `${data.turn}-${data.step}`
      const item = { id: `work-${key}`, kind: 'work', title: `步骤 ${data.step}`, text: '正在执行' }
      steps.set(key, { item, time: event.time, tools: [] })
      pending.push(item)
    } else if (event.type === 'step/end') {
      const entry = steps.get(`${data.turn}-${data.step}`)
      if (entry) {
        const ms = Math.max(0, (event.time ?? 0) - (entry.time ?? 0))
        entry.item.text = `已完成 · ${ms} ms\n${entry.tools.length ? `调用：${entry.tools.join('、')}` : '本步骤无工具调用'}`
      }
    }
  }
  flush()
  return { items, turnCount: Math.min(prompts.length, limit), hasMore: Boolean(snapshot.hasMore) || prompts.length > limit }
}

export function findDetail(snapshot, kind, key) {
  const events = (snapshot.records ?? []).filter(record => record.type === 'event' && record.event).map(record => record.event)
  if (kind === 'reasoning') {
    const event = events.find(event => event.type === 'assistant/message' && String(event.seq) === key)
    if (!event) return null
    return { text: (event.data?.message?.content ?? []).filter(block => block?.type === 'reasoning').map(block => block.text ?? '').join('') }
  }
  if (kind === 'commentary') {
    const event = events.find(event => event.type === 'assistant/message' && String(event.seq) === key)
    return event ? { text: textOf(event.data?.message?.content) } : null
  }
  if (kind === 'terminal') {
    const event = events.find(event => isRealUser(event) && String(event.seq) === key)
    return event ? { text: textOf(event.data?.content) } : null
  }
  if (kind === 'tool') {
    const call = events.find(event => event.type === 'tool/call' && event.data?.callId === key)
    const result = events.find(event => event.type === 'tool/result' && event.data?.message?.toolCallId === key)
    if (!call && !result) return null
    const args = call?.data?.arguments
    const parsedArgs = typeof args === 'string' ? (() => { try { return JSON.parse(args) } catch { return null } })() : args
    const terminal = /(?:bash|shell|terminal|powershell|exec_command|ops_bash)/i.test(call?.data?.name ?? '')
    return { arguments: detailText(args), input: terminal ? detailText(parsedArgs?.command ?? parsedArgs?.cmd ?? parsedArgs?.script ?? args) : detailText(args),
      result: textOf(result?.data?.message?.content) || detailText(result?.data?.message?.content), terminal,
      isError: Boolean(result?.data?.message?.isError) }
  }
  return null
}
