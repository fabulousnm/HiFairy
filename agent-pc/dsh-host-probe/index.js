import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { randomUUID, timingSafeEqual } from 'node:crypto'

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

async function readJson(request) {
  let raw = ''
  for await (const chunk of request) {
    raw += chunk
    if (raw.length > 16384) throw new Error('request_too_large')
  }
  return JSON.parse(raw || '{}')
}

export function createProbeServer(controller, token, options = {}) {
  return createServer(async (request, response) => {
    if (!authorized(request, token)) return respond(response, 401, { error: 'unauthorized' })
    if (request.method === 'POST' && request.url === '/sessions') {
      if (!options.workspaceId) return respond(response, 503, { error: 'workspace_unconfigured' })
      try {
        const result = await controller.create({ workspaceId: options.workspaceId })
        return respond(response, 201, { sessionId: result.sessionId })
      } catch (error) {
        return respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
      }
    }
    const promptMatch = /^\/sessions\/(session-[a-z0-9-]+)\/messages$/.exec(request.url ?? '')
    if (request.method === 'POST' && promptMatch) {
      try {
        const body = await readJson(request)
        if (typeof body.text !== 'string' || !body.text.trim() || body.text.length > 8000)
          return respond(response, 400, { error: 'invalid_text' })
        const sessionId = promptMatch[1]
        const listed = await controller.list({}, AbortSignal.timeout(5000))
        if (!listed.items.some(item => item.sessionId === sessionId)) return respond(response, 404, { error: 'session_not_found' })
        const requestId = randomUUID()
        await controller.prompt({ requestId, sessionId, mode: 'queue', content: [{ type: 'text', text: body.text }] }, AbortSignal.timeout(10000))
        return respond(response, 202, { sessionId, requestId })
      } catch (error) {
        return respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
      }
    }
    if (request.method !== 'GET') return respond(response, 405, { error: 'method_not_allowed' })
    const match = /^\/sessions\/(session-[a-z0-9-]+)\/(snapshot|events)$/.exec(request.url ?? '')
    if (match) {
      const [, sessionId, action] = match
      const abort = new AbortController()
      response.on('close', () => abort.abort())
      if (action === 'snapshot') {
        let stream
        try {
          stream = controller.follow({ address: { kind: 'session', sessionId }, maxMessages: 20, assistantStream: true }, abort.signal)[Symbol.asyncIterator]()
          const first = await stream.next()
          if (first.done || first.value?.type !== 'snapshot') return respond(response, 503, { error: 'snapshot_unavailable' })
          return respond(response, 200, first.value)
        } catch (error) {
          return respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
        } finally {
          await stream?.return?.()
        }
      }
      response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', Connection: 'keep-alive' })
      try {
        const stream = controller.follow({ address: { kind: 'session', sessionId }, maxMessages: 20, assistantStream: true }, abort.signal)[Symbol.asyncIterator]()
        for await (const frame of { [Symbol.asyncIterator]: () => stream }) {
          response.write(`data: ${JSON.stringify(frame)}\n\n`)
        }
      } catch (error) {
        if (!abort.signal.aborted) response.write(`event: error\ndata: ${JSON.stringify({ error: String(error?.message ?? error) })}\n\n`)
      } finally {
        response.end()
      }
      return
    }
    if (request.url !== '/sessions') return respond(response, 404, { error: 'not_found' })
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
  const server = createProbeServer(ctx.sessionController, token, { workspaceId: config.workspaceId })
  ctx.effect(() => {
    server.listen(config.port ?? 18765, '127.0.0.1')
    return () => server.close()
  })
}
