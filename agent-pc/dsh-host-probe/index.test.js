import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createProbeServer } from './index.js'

test('phone workspace probe is delivered only to the active session and returns its result', async () => {
  const token = 'p'.repeat(40)
  const options = {}
  const controller = { follow(_input, signal) {
    return (async function* () {
      yield { type: 'snapshot', cursor: 1 }
      await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }))
    })()
  } }
  const server = createProbeServer(controller, token, options)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const headers = { Authorization: `Bearer ${token}` }
  const abort = new AbortController()
  try {
    assert.match((await options.phoneWorkspace.offer('session-other')).text, /没有打开/)
    const offline = await fetch(`${base}/sessions/session-other/phone-workspace-probe`, { method: 'POST', headers })
    assert.equal(offline.status, 200)
    assert.match((await offline.json()).text, /没有打开/)
    const stream = await fetch(`${base}/sessions/session-test/events`, { headers, signal: abort.signal })
    assert.equal(stream.status, 200)
    const reader = stream.body.getReader()
    const first = await reader.read()
    assert.match(new TextDecoder().decode(first.value), /snapshot/)
    const waiting = options.phoneWorkspace.offer('session-test')
    const frame = await reader.read()
    const match = new TextDecoder().decode(frame.value).match(/"id":"([a-f0-9-]{36})"/)
    assert.ok(match)
    const invalid = await fetch(`${base}/sessions/session-other/phone-workspace-result`, {
      method: 'POST', headers, body: JSON.stringify({ id: match[1], text: 'wrong session' })
    })
    assert.equal(invalid.status, 400)
    const accepted = await fetch(`${base}/sessions/session-test/phone-workspace-result`, {
      method: 'POST', headers, body: JSON.stringify({ id: match[1], text: 'probe ok' })
    })
    assert.equal(accepted.status, 200)
    assert.deepEqual(await waiting, { text: 'probe ok' })
    await reader.cancel()
  } finally {
    abort.abort()
    await new Promise(resolve => server.close(resolve))
  }
})

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

test('permission catalog and session selection use Host presets', async () => {
  const session = { id: 'session-abc' }
  let selected = 'workspace-write'
  const options = { sessions: { list: () => [session] }, permissionPresets: {
    catalog: () => ({ options: [{ value: 'workspace-write', name: 'Workspace' }, { value: 'danger-full-access', name: 'Full' }], defaultPreset: 'workspace-write' }),
    current: () => selected, set: (_, value) => { selected = value }
  } }
  const token = 'p'.repeat(40)
  const server = createProbeServer({}, token, options)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${server.address().port}`
    const headers = { Authorization: `Bearer ${token}` }
    assert.equal((await (await fetch(`${base}/permissions`, { headers })).json()).defaultPreset, 'workspace-write')
    assert.equal((await (await fetch(`${base}/sessions/session-abc/permission`, { headers })).json()).value, selected)
    const changed = await fetch(`${base}/sessions/session-abc/permission`, { method: 'POST', headers,
      body: JSON.stringify({ value: 'danger-full-access' }) })
    assert.equal(changed.status, 200)
    assert.equal(selected, 'danger-full-access')
    assert.equal((await fetch(`${base}/sessions/session-abc/permission`, { method: 'POST', headers,
      body: JSON.stringify({ value: 'invalid' }) })).status, 400)
  } finally { await new Promise(resolve => server.close(resolve)) }
})

test('a connected phone can answer approval and disconnect delegates to desktop', async () => {
  const options = {}
  const controller = { follow() {
    return (async function* () {
      yield { type: 'snapshot', cursor: 1 }
      await new Promise(resolve => { controller.stop = resolve })
    })()
  } }
  const token = 'q'.repeat(40)
  const server = createProbeServer(controller, token, options)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const headers = { Authorization: `Bearer ${token}` }
  const abort = new AbortController()
  try {
    const stream = await fetch(`${base}/sessions/session-abc/events`, { headers, signal: abort.signal })
    assert.equal(stream.status, 200)
    const agent = { session: { id: 'session-abc', seq: 2, eventAt: () => ({ type: 'approval/asked', data: { id: 'approval-one' } }) } }
    const decision = options.approvalBridge.offer({ agent, toolName: 'ops_bash', reason: 'Run command' }, () => 'desktop')
    assert.equal((await (await fetch(`${base}/sessions/session-abc/approvals`, { headers })).json()).items[0].toolName, 'ops_bash')
    const answer = await fetch(`${base}/sessions/session-abc/approvals/approval-one`, { method: 'POST', headers,
      body: JSON.stringify({ outcome: 'allowed-once' }) })
    assert.equal(answer.status, 200)
    assert.equal(await decision, 'allowed-once')
    const delegated = options.approvalBridge.offer({ agent, toolName: 'ops_bash' }, () => 'desktop')
    abort.abort()
    controller.stop()
    assert.equal(await delegated, 'desktop')
  } finally {
    abort.abort()
    controller.stop?.()
    await new Promise(resolve => server.close(resolve))
  }
})
