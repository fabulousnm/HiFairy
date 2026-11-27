import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, symlink, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createProbeServer } from './index.js'

test('browse one directory, protect its boundary, upload and download without mutation routes', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hifairy-files-'))
  const outside = await mkdtemp(join(tmpdir(), 'hifairy-outside-'))
  await mkdir(join(root, 'child'))
  await writeFile(join(root, 'note.txt'), 'hello')
  await writeFile(join(root, 'child', 'inside.txt'), 'inside')
  await symlink(outside, join(root, 'escape'), 'junction')
  const token = 'f'.repeat(40)
  const server = createProbeServer({}, token, { workspaceRegistry: { list: () => [{ id: 'project', path: root }] } })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${server.address().port}`
    const headers = { Authorization: `Bearer ${token}` }
    assert.equal((await fetch(`${base}/workspaces/project/files`, { headers })).status, 200)
    const initial = await (await fetch(`${base}/workspaces/project/files`, { headers })).json()
    assert(initial.items.some(item => item.name === 'child' && item.directory))
    assert(!initial.items.some(item => item.name === 'inside.txt'))
    const child = await (await fetch(`${base}/workspaces/project/files?path=child`, { headers })).json()
    assert.deepEqual(child.items.map(item => item.name), ['inside.txt'])
    assert.equal((await fetch(`${base}/workspaces/project/files?path=escape`, { headers })).status, 400)
    assert.equal((await fetch(`${base}/workspaces/project/files?path=..`, { headers })).status, 400)
    const uploaded = await (await fetch(`${base}/workspaces/project/upload?name=photo.txt`, { method: 'POST', headers, body: 'uploaded' })).json()
    assert.equal(await readFile(uploaded.path, 'utf8'), 'uploaded')
    const nested = await (await fetch(`${base}/workspaces/project/upload?path=child&name=nested.txt`, { method: 'POST', headers, body: 'child-file' })).json()
    assert.equal(nested.relativePath, 'child/nested.txt')
    const downloaded = await fetch(`${base}/workspaces/project/download?path=${encodeURIComponent(uploaded.relativePath)}`, { headers })
    assert.equal(await downloaded.text(), 'uploaded')
    assert.equal((await fetch(`${base}/workspaces/project/upload?name=..%2Fbad.txt`, { method: 'POST', headers, body: 'bad' })).status, 400)
    assert.equal((await fetch(`${base}/workspaces/project/files`, { method: 'DELETE', headers })).status, 405)
    const roots = await (await fetch(`${base}/filesystem/roots`, { headers })).json()
    assert(roots.items.some(item => item.directory))
    const absolute = await (await fetch(`${base}/filesystem/files?path=${encodeURIComponent(root)}`, { headers })).json()
    assert(absolute.items.some(item => item.name === 'child' && item.directory))
    const arbitraryUpload = await (await fetch(`${base}/filesystem/upload?path=${encodeURIComponent(root)}&name=anywhere.txt`,
      { method: 'POST', headers, body: 'anywhere' })).json()
    assert.equal(await readFile(arbitraryUpload.path, 'utf8'), 'anywhere')
    const arbitraryDownload = await fetch(`${base}/filesystem/download?path=${encodeURIComponent(arbitraryUpload.path)}`, { headers })
    assert.equal(await arbitraryDownload.text(), 'anywhere')
    assert.equal((await fetch(`${base}/filesystem/files?path=relative`, { headers })).status, 400)
    assert.equal((await fetch(`${base}/filesystem/download?path=${encodeURIComponent(outside)}`, { headers })).status, 400)
    assert.equal((await fetch(`${base}/filesystem/files`, { method: 'DELETE', headers })).status, 405)
  } finally {
    await new Promise(resolve => server.close(resolve))
    await rm(root, { recursive: true, force: true })
    await rm(outside, { recursive: true, force: true })
  }
})

test('cancel reaches the native Session controller only for an authenticated session', async () => {
  const calls = []
  const server = createProbeServer({ cancel(request) { calls.push(request) } }, 'c'.repeat(40))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const url = `http://127.0.0.1:${server.address().port}/sessions/session-test/cancel`
    assert.equal((await fetch(url, { method: 'POST' })).status, 401)
    assert.equal((await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${'c'.repeat(40)}` } })).status, 200)
    assert.deepEqual(calls, [{ sessionId: 'session-test' }])
  } finally { await new Promise(resolve => server.close(resolve)) }
})

test('Agent attachment upload produces a DSH receipt consumed by the next prompt', async () => {
  const calls = []
  const controller = {
    async list() { return { items: [{ sessionId: 'session-test' }] } },
    async prompt(request) { calls.push(request) }
  }
  const fileUploads = { async uploadStream(request) {
    let content = ''
    for await (const chunk of request.data) content += chunk.toString()
    assert.equal(content, 'payload')
    assert.equal(request.name, 'note.txt')
    return { receiptId: '12345678-1234-1234-1234-123456789012' }
  } }
  const token = 'z'.repeat(40)
  const server = createProbeServer(controller, token, { fileUploads })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${server.address().port}`
    const headers = { Authorization: `Bearer ${token}` }
    const upload = await (await fetch(`${base}/sessions/session-test/attachments?name=note.txt`, { method: 'POST', headers, body: 'payload' })).json()
    const sent = await fetch(`${base}/sessions/session-test/messages`, { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: '分析文件', attachments: [upload.receiptId] }) })
    assert.equal(sent.status, 202)
    assert.deepEqual(calls[0].content, [{ type: 'text', text: '分析文件' }, { type: 'file', receiptId: upload.receiptId }])
  } finally { await new Promise(resolve => server.close(resolve)) }
})
