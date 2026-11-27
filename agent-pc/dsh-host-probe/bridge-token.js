import { randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export function bridgeTokenFile(config = {}) {
  return config.tokenFile || process.env.HIFAIRY_BRIDGE_TOKEN_FILE ||
    join(homedir(), '.hifairy', 'bridge.token')
}

export function bridgeToken(config = {}) {
  const file = bridgeTokenFile(config)
  mkdirSync(dirname(file), { recursive: true })
  try {
    writeFileSync(file, randomBytes(32).toString('hex'), { flag: 'wx', mode: 0o600 })
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
  }
  const token = readFileSync(file, 'utf8').trim()
  if (token.length < 32) throw new Error('HiFairy bridge token must contain at least 32 characters')
  return token
}
