import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { timingSafeEqual } from 'node:crypto'

export const name = 'hifairy-dsh-host-probe'
export const inject = ['sessionController']

function authorized(request, token) {
  const candidate = request.headers.authorization?.replace(/^Bearer /, '') ?? ''
  const left = Buffer.from(candidate)
  const right = Buffer.from(token)
  return left.length === right.length && timingSafeEqual(left, right)
}

function respond(response, status, data) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(data))
}

export function createProbeServer(controller, token) {
  return createServer(async (request, response) => {
    if (!authorized(request, token)) return respond(response, 401, { error: 'unauthorized' })
    if (request.method !== 'GET' || request.url !== '/sessions') return respond(response, 404, { error: 'not_found' })
    try {
      const result = await controller.list({}, AbortSignal.timeout(5000))
      respond(response, 200, { items: result.items.map(({ sessionId, title, workingDirectory }) => ({ sessionId, title, workingDirectory })) })
    } catch (error) {
      respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
    }
  })
}

export function apply(ctx, config) {
  if (!config?.tokenFile) throw new Error('HiFairy probe requires a tokenFile')
  const token = readFileSync(config.tokenFile, 'utf8').trim()
  if (token.length < 32) throw new Error('HiFairy probe token must contain at least 32 characters')
  const server = createProbeServer(ctx.sessionController, token)
  ctx.effect(() => {
    server.listen(config.port ?? 18765, '127.0.0.1')
    return () => server.close()
  })
}
