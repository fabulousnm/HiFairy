export const RECENT_MESSAGE_LIMIT = 15

const textOf = blocks => (blocks ?? []).filter(block => block?.type === 'text').map(block => block.text ?? '').join('')
const preview = value => String(value ?? '').slice(0, 220)
const detailText = value => typeof value === 'string' ? value : JSON.stringify(value ?? '')

export function recentItems(snapshot, limit = RECENT_MESSAGE_LIMIT) {
  const events = (snapshot.records ?? []).filter(record => record.type === 'event' && record.event).map(record => record.event)
  const messageSeqs = events.filter(event => event.type === 'user/message' ||
    event.type === 'assistant/message' && textOf(event.data?.message?.content).trim()).map(event => event.seq)
  const cutoff = messageSeqs.at(-limit) ?? events[0]?.seq ?? 0
  const selected = events.filter(event => event.seq >= cutoff)
  const items = []
  const seenMessages = new Set()
  const tools = new Map()
  const steps = new Map()

  for (const event of selected) {
    const data = event.data ?? {}
    if (event.type === 'user/message') {
      const id = data.id ?? `user-${event.seq}`
      if (seenMessages.has(id)) continue
      seenMessages.add(id)
      const content = textOf(data.content)
      if (content) items.push({ id, kind: 'user', text: content, turn: data.turn ?? null })
    } else if (event.type === 'assistant/message') {
      const message = data.message ?? {}
      const id = message.id ?? `assistant-${event.seq}`
      if (seenMessages.has(id)) continue
      seenMessages.add(id)
      const reasoning = (message.content ?? []).filter(block => block?.type === 'reasoning').map(block => block.text ?? '').join('')
      if (reasoning) items.push({ id: `reasoning-${event.seq}`, kind: 'reasoning', title: `思考 · 第 ${data.turn ?? '?'} 回合`, preview: preview(reasoning), detailKey: String(event.seq) })
      const content = textOf(message.content)
      if (content) items.push({ id, kind: 'assistant', text: content, turn: data.turn ?? null })
    } else if (event.type === 'tool/call') {
      const id = data.callId ?? `tool-${event.seq}`
      const item = { id: `tool-${id}`, kind: 'tool', title: data.name ?? '工具调用', preview: preview(detailText(data.arguments)), detailKey: id, hasResult: false, isError: false }
      tools.set(id, item)
      items.push(item)
      const step = steps.get(`${data.turn}-${data.step}`)
      if (step) step.tools.push(data.name ?? '工具调用')
    } else if (event.type === 'tool/result') {
      const id = data.message?.toolCallId
      if (!id) continue
      const item = tools.get(id)
      if (item) {
        item.hasResult = true
        item.isError = Boolean(data.message?.isError)
      }
    } else if (event.type === 'step/start') {
      const key = `${data.turn}-${data.step}`
      const item = { id: `work-${key}`, kind: 'work', title: `工作记录 · 第 ${data.turn} 回合 / 步骤 ${data.step}`, preview: '正在执行', text: '正在执行', durationMs: null }
      steps.set(key, { item, time: event.time, tools: [] })
      items.push(item)
    } else if (event.type === 'step/end') {
      const entry = steps.get(`${data.turn}-${data.step}`)
      if (entry) {
        entry.item.preview = '已完成'
        entry.item.durationMs = Math.max(0, (event.time ?? 0) - (entry.time ?? 0))
        entry.item.text = `已完成 · ${entry.item.durationMs} ms\n${entry.tools.length ? `调用：${entry.tools.join('、')}` : '本步骤无工具调用'}`
      }
    }
  }
  return { items, messageCount: items.filter(item => item.kind === 'user' || item.kind === 'assistant').length,
    hasMore: Boolean(snapshot.hasMore) || messageSeqs.length > limit }
}

export function findDetail(snapshot, kind, key) {
  const events = (snapshot.records ?? []).filter(record => record.type === 'event' && record.event).map(record => record.event)
  if (kind === 'reasoning') {
    const event = events.find(event => event.type === 'assistant/message' && String(event.seq) === key)
    if (!event) return null
    return { text: (event.data?.message?.content ?? []).filter(block => block?.type === 'reasoning').map(block => block.text ?? '').join('') }
  }
  if (kind === 'tool') {
    const call = events.find(event => event.type === 'tool/call' && event.data?.callId === key)
    const result = events.find(event => event.type === 'tool/result' && event.data?.message?.toolCallId === key)
    if (!call && !result) return null
    return { arguments: detailText(call?.data?.arguments), result: textOf(result?.data?.message?.content) || detailText(result?.data?.message?.content),
      isError: Boolean(result?.data?.message?.isError) }
  }
  return null
}
