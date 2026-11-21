import { test } from 'node:test'
import assert from 'node:assert/strict'
import { recentItems, findDetail } from './recent.js'

test('selects fifteen real question-answer turns, keeping full answers and compact records', () => {
  const records = []
  let seq = 0
  const add = (type, data) => records.push({ type: 'event', event: { seq: ++seq, type, data, time: seq * 100 } })
  for (let n = 0; n < 20; n++) {
    add('user/message', { id: `u${n}`, role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: `prompt ${n}` }] })
    add('user/message', { role: 'user', source: { kind: 'runtime-context' }, content: [{ type: 'text', text: 'internal context' }] })
    if (n === 19) {
      add('step/start', { turn: n, step: 1 })
      add('tool/call', { turn: n, step: 1, callId: 'call-1', name: 'ops_bash', arguments: { command: 'pwd' } })
      add('tool/result', { message: { toolCallId: 'call-1', content: [{ type: 'text', text: 'file content' }] } })
      add('step/end', { turn: n, step: 1 })
    }
    add('assistant/message', { turn: n, step: n === 19 ? 1 : undefined, message: { id: `a${n}`, content: [
      ...(n === 19 ? [{ type: 'reasoning', text: '思考内容'.repeat(100) }] : []),
      { type: 'text', text: n === 19 ? '完整回答'.repeat(120) : `answer ${n}` }] } })
  }
  const result = recentItems({ records, hasMore: true })
  assert.equal(result.turnCount, 15)
  assert.equal(result.items.filter(x => x.kind === 'user').length, 15)
  assert.equal(result.items.filter(x => x.kind === 'assistant').length, 15)
  assert.equal(result.items.some(x => x.text === 'internal context'), false)
  assert.equal(result.items.find(x => x.id === 'a19').text.length, 480)
  assert.equal(result.items.find(x => x.kind === 'tool').parentId, 'work-19-1')
  assert.equal(result.items.find(x => x.kind === 'reasoning').parentId, 'work-19-1')
  const tail = result.items.slice(-4).map(x => x.kind)
  assert.deepEqual(tail, ['tool', 'work', 'reasoning', 'assistant'].sort((a, b) => ({ work: 0, tool: 1, reasoning: 2, assistant: 3 })[a] - ({ work: 0, tool: 1, reasoning: 2, assistant: 3 })[b]))
  assert.equal(findDetail({ records }, 'tool', 'call-1').result, 'file content')
  assert.equal(findDetail({ records }, 'tool', 'call-1').input, 'pwd')
  assert.equal(findDetail({ records }, 'tool', 'call-1').terminal, true)
  assert.equal(findDetail({ records }, 'reasoning', String(seq)).text.length, 400)
})

test('folds a real pasted terminal transcript without displaying it as a user bubble', () => {
  const transcript = 'PS C:\\Users\\admin> Remove-Item a\nRemove-Item : denied\n  + CategoryInfo : error'
  const records = [{ type: 'event', event: { seq: 1, type: 'user/message', data: {
    id: 'terminal-1', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: transcript }] } } }]
  const result = recentItems({ records })
  assert.equal(result.turnCount, 1)
  assert.equal(result.items[0].kind, 'terminal')
  assert.equal(findDetail({ records }, 'terminal', '1').text, transcript)
})

test('treats command-shaped tool arguments as terminal text regardless of tool name', () => {
  const records = [{ type: 'event', event: { type: 'tool/call', data: {
    callId: 'cmd-1', name: 'ops_execute', arguments: { command: 'cat file.tex' }
  } } }, { type: 'event', event: { type: 'tool/result', data: {
    message: { toolCallId: 'cmd-1', content: [{ type: 'text', text: '\\newcommand{\\TBD}{x}' }] }
  } } }]
  const detail = findDetail({ records }, 'tool', 'cmd-1')
  assert.equal(detail.terminal, true)
  assert.equal(detail.input, 'cat file.tex')
  assert.equal(detail.result, '\\newcommand{\\TBD}{x}')
})

test('keeps raw LaTeX tool output out of Markdown rendering', () => {
  const records = [{ type: 'event', event: { type: 'tool/call', data: {
    callId: 'latex-1', name: 'document_reader', arguments: { path: 'paper.tex' }
  } } }, { type: 'event', event: { type: 'tool/result', data: {
    message: { toolCallId: 'latex-1', content: [{ type: 'text', text: 'line 12 \\newcommand{\\TBD}{x}' }] }
  } } }]
  assert.equal(findDetail({ records }, 'tool', 'latex-1').terminal, true)
})

test('keeps work narration with tool calls out of answer bubbles', () => {
  const records = [
    { type: 'event', event: { seq: 1, type: 'user/message', data: { role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '检查项目' }] } } },
    { type: 'event', event: { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '我先查看目录' }, { type: 'tool-call', id: 'c' }] } } } },
    { type: 'event', event: { seq: 3, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '检查完成' }] } } } },
  ]
  const result = recentItems({ records })
  assert.deepEqual(result.items.map(x => x.kind), ['user', 'commentary', 'assistant'])
  assert.equal(findDetail({ records }, 'commentary', '2').text, '我先查看目录')
})

test('places the step/start emitted before a prompt under that user message', () => {
  const records = [
    { type: 'event', event: { seq: 1, type: 'turn/end', data: { turn: 1 } } },
    { type: 'event', event: { seq: 2, type: 'step/start', data: { turn: 2, step: 1 } } },
    { type: 'event', event: { seq: 3, type: 'user/message', data: {
      id: 'u2', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '第二轮问题' }] } } },
    { type: 'event', event: { seq: 4, type: 'assistant/message', data: {
      turn: 2, step: 1, message: { id: 'a2', content: [{ type: 'text', text: '第二轮回答' }] } } } },
    { type: 'event', event: { seq: 5, type: 'turn/end', data: { turn: 2 } } },
  ]
  const result = recentItems({ records }, 1)
  assert.deepEqual(result.items.map(item => item.kind), ['user', 'work', 'assistant'])
  assert.equal(result.items[1].title, '步骤 1')
})
