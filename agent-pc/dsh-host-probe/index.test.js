import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createProbeServer } from './index.js'

test('only an authenticated request can read Host session summaries', async () => {
  let calls = 0
  const server = createProbeServer({ async list() { calls++; return { items: [{ sessionId: 'one', title: 'Test', workingDirectory: 'D:\\workspace', secret: 'hidden' }] } } }, 'a'.repeat(40))
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
    assert.equal(body.items[0].secret, undefined)
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})
