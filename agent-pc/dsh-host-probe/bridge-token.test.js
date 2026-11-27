import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { bridgeToken } from './bridge-token.js'

test('the Host and phone tool reuse one generated local token', () => {
  const directory = mkdtempSync(join(tmpdir(), 'hifairy-bridge-'))
  try {
    const file = join(directory, 'nested', 'bridge.token')
    const first = bridgeToken({ tokenFile: file })
    assert.equal(first.length, 64)
    assert.equal(bridgeToken({ tokenFile: file }), first)
    assert.equal(readFileSync(file, 'utf8'), first)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
