import { readFileSync } from 'node:fs'

const token = readFileSync('D:/dsh-data/hifairy-agent-probe.token', 'utf8').trim()
const sessionId = readFileSync('D:/dsh-data/workspace/hifairy-agent-probe/session-id.txt', 'utf8').trim()
const base = 'http://127.0.0.1:18765'
const headers = { Authorization: `Bearer ${token}` }
const controller = new AbortController()
const timeout = setTimeout(() => controller.abort(), 30000)
let started = false
let assistant = false
let finished = false

try {
  const response = await fetch(`${base}/sessions/${sessionId}/events`, { headers, signal: controller.signal })
  if (!response.ok || !response.body) throw new Error(`event stream HTTP ${response.status}`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!finished) {
    const { value, done } = await reader.read()
    if (done) throw new Error('event stream closed early')
    buffer += decoder.decode(value, { stream: true })
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const packet = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const data = packet.split('\n').find(line => line.startsWith('data: '))?.slice(6)
      if (!data) continue
      const frame = JSON.parse(data)
      if (frame.type === 'snapshot' && !started) {
        started = true
        const sent = await fetch(`${base}/sessions/${sessionId}/messages`, {
          method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: 'HiFairy 实时事件测试。请只回复：实时事件流正常。不要调用工具。' })
        })
        if (sent.status !== 202) throw new Error(`prompt HTTP ${sent.status}`)
      }
      const type = frame.event?.type
      if (type === 'assistant/message') assistant = true
      if (type === 'turn/end' && assistant) finished = true
    }
  }
  console.log(`live_stream_started=${started} assistant_received=${assistant} turn_finished=${finished}`)
} finally {
  controller.abort()
  clearTimeout(timeout)
}
