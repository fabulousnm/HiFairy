import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createProbeServer } from './index.js'

test('only an authenticated request can read Host session summaries', async () => {
  let calls = 0
  const server = createProbeServer({ async list() { calls++; return { items: [{ sessionId: 'one', projections: { values: { title: 'Test' } }, cwd: 'D:\\workspace', secret: 'hidden' }, { sessionId: 'blank', blank: true }] } } }, 'a'.repeat(40))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const url = `http://127.0.0.1:${server.address().port}/sessions`
    const denied = await fetch(url)
    assert.equal(denied.status, 401)
    assert.equal(calls, 0)
    const allowed = await fetch(url, { headers: { Authorization: `Bearer ${'a'.repeat(40)}` } })
    assert.equal(allowed.status, 200)
    const body = await allowed.json()
    assert.equal(body.items[0].sessionId, 'one')
    assert.equal(body.items[0].title, 'Test')
    assert.equal(body.items.length, 1)
    assert.equal(body.items[0].secret, undefined)
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})

test('session snapshot uses the same Host follow stream', async () => {
  let request
  const server = createProbeServer({
    follow(input) {
      request = input
      return (async function* () { yield { type: 'snapshot', cursor: 7, records: [] } })()
    }
  }, 'b'.repeat(40))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const url = `http://127.0.0.1:${server.address().port}/sessions/session-abc/snapshot`
    const result = await fetch(url, { headers: { Authorization: `Bearer ${'b'.repeat(40)}` } })
    assert.equal(result.status, 200)
    assert.equal((await result.json()).cursor, 7)
    assert.equal(request.address.sessionId, 'session-abc')
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})

test('creates in the configured workspace and queues a message in that Host session', async () => {
  const calls = []
  const controller = {
    async create(request) { calls.push(['create', request]); return { sessionId: 'session-test' } },
    async list() { return { items: [{ sessionId: 'session-test' }] } },
    async prompt(request) { calls.push(['prompt', request]); return { accepted: true } }
  }
  const token = 'c'.repeat(40)
  const workspaceItems = [{ id: 'workspace-1', title: 'Project', path: 'D:\\Project' }]
  const presetItems = [{ id: 'standard', name: 'Standard' }, { id: 'review', name: 'Review' }]
  const workspaceRegistry = { list: () => workspaceItems }
  const agentPresets = { defaultId: 'standard', list: async () => presetItems }
  const server = createProbeServer(controller, token, { workspaceId: 'workspace-1', workspaceRegistry, agentPresets })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${server.address().port}`
    const headers = { Authorization: `Bearer ${token}` }
    const workspaces = await (await fetch(`${base}/workspaces`, { headers })).json()
    const modes = await (await fetch(`${base}/modes`, { headers })).json()
    assert.equal(workspaces.items[0].id, 'workspace-1')
    assert.equal(modes.items[1].id, 'review')
    presetItems.push({ id: 'new-mode', name: 'New Mode' })
    assert.equal((await (await fetch(`${base}/modes`, { headers })).json()).items[2].id, 'new-mode')
    const created = await fetch(`${base}/sessions`, { method: 'POST', headers })
    assert.equal(created.status, 201)
    assert.equal(calls[0][1].cwd, undefined)
    assert.equal(calls[0][1].workspaceId, 'workspace-1')
    assert.equal(calls[0][1].agentPreset, 'standard')
    const custom = await fetch(`${base}/sessions`, { method: 'POST', headers, body: JSON.stringify({ workspaceId: 'workspace-1', agentPreset: 'review' }) })
    assert.equal(custom.status, 201)
    assert.equal(calls[1][1].agentPreset, 'review')
    presetItems.pop()
    assert.equal((await (await fetch(`${base}/modes`, { headers })).json()).items.length, 2)
    const removed = await fetch(`${base}/sessions`, { method: 'POST', headers, body: JSON.stringify({ workspaceId: 'workspace-1', agentPreset: 'removed' }) })
    assert.equal(removed.status, 400)
    const sent = await fetch(`${base}/sessions/session-test/messages`, { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ text: '联通测试' }) })
    assert.equal(sent.status, 202)
    assert.equal(calls[2][1].content[0].text, '联通测试')
    assert.equal(calls[2][1].mode, 'queue')
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})

test('expands the Host window until fifteen real turns are available', async () => {
  const windows = []
  const makeSnapshot = (count, hasMore) => ({ type: 'snapshot', hasMore, records: Array.from({ length: count }, (_, index) => ({
    type: 'event', event: { seq: index + 1, type: 'user/message', data: {
      role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: `question ${index}` }] } } })) })
  const server = createProbeServer({ follow(input) {
    windows.push(input.maxMessages)
    return (async function* () { yield input.maxMessages === 240 ? makeSnapshot(8, true) : makeSnapshot(20, false) })()
  } }, 'd'.repeat(40))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/sessions/session-abc/recent`,
      { headers: { Authorization: `Bearer ${'d'.repeat(40)}` } })
    assert.equal(response.status, 200)
    const data = await response.json()
    assert.equal(data.turnCount, 15)
    assert.deepEqual(windows, [240, 480])
  } finally { await new Promise(resolve => server.close(resolve)) }
})
