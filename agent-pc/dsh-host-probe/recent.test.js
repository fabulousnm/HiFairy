import { test } from 'node:test'
import assert from 'node:assert/strict'
import { recentItems, findDetail } from './recent.js'

test('keeps full answers and the latest fifteen messages with separate details', () => {
  const events = []
  let seq = 0
  const add = (type, data) => events.push({ type: 'event', event: { seq: ++seq, type, data, time: seq * 100 } })
  for (let n = 0; n < 20; n++) add('user/message', { id: `u${n}`, content: [{ type: 'text', text: `prompt ${n}` }] })
  add('tool/call', { callId: 'call-1', name: 'read_file', arguments: { path: 'main.kt' } })
  add('tool/result', { message: { toolCallId: 'call-1', content: [{ type: 'text', text: 'file content' }] } })
  add('step/start', { turn: 20, step: 1 })
  add('step/end', { turn: 20, step: 1 })
  const answer = '完整回答'.repeat(120)
  add('assistant/message', { turn: 20, message: { id: 'a20', content: [{ type: 'reasoning', text: '思考内容'.repeat(100) }, { type: 'text', text: answer }] } })
  const snapshot = { records: events, hasMore: true }
  const result = recentItems(snapshot)
  assert.equal(result.messageCount, 15)
  assert.equal(result.items.find(item => item.id === 'a20').text, answer)
  assert.equal(result.items.find(item => item.kind === 'tool').hasResult, true)
  assert.ok(result.items.some(item => item.kind === 'work'))
  assert.equal(findDetail(snapshot, 'tool', 'call-1').result, 'file content')
  assert.equal(findDetail(snapshot, 'reasoning', String(seq)).text.length, 400)
  assert.equal(result.hasMore, true)
})
