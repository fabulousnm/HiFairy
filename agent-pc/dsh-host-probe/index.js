import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { randomUUID, timingSafeEqual } from 'node:crypto'
import { HOST_MESSAGE_WINDOW, recentItems, findDetail } from './recent.js'

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
    const recentMatch = /^\/sessions\/(session-[a-z0-9-]+)\/recent$/.exec(request.url ?? '')
    const detailMatch = /^\/sessions\/(session-[a-z0-9-]+)\/details\/(reasoning|tool|terminal|commentary)\/([a-zA-Z0-9_-]+)$/.exec(request.url ?? '')
    if (recentMatch || detailMatch) {
      const sessionId = recentMatch?.[1] ?? detailMatch[1]
      try {
        let window = HOST_MESSAGE_WINDOW
        while (window <= 1920) {
          const stream = controller.follow({ address: { kind: 'session', sessionId }, maxMessages: window, assistantStream: false }, AbortSignal.timeout(15000))[Symbol.asyncIterator]()
          let snapshot
          try {
            const first = await stream.next()
            if (first.done || first.value?.type !== 'snapshot') return respond(response, 503, { error: 'snapshot_unavailable' })
            snapshot = first.value
          } finally { await stream.return?.() }
          if (recentMatch) {
            const recent = recentItems(snapshot)
            if (recent.turnCount >= 15 || !snapshot.hasMore || window === 1920) return respond(response, 200, recent)
          } else {
            const detail = findDetail(snapshot, detailMatch[2], detailMatch[3])
            if (detail) return respond(response, 200, detail)
            if (!snapshot.hasMore || window === 1920) return respond(response, 404, { error: 'detail_not_found' })
          }
          window *= 2
        }
      } catch (error) {
        return respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
      }
    }
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
          let outgoing
          if (frame.type === 'snapshot') outgoing = { type: 'snapshot', cursor: frame.cursor }
          else if (frame.type === 'assistant-stream') {
            const chunkType = frame.frame?.chunk?.type
            if (frame.frame?.type === 'start' || chunkType === 'block-start' || chunkType === 'text-delta') outgoing = frame
          } else if (frame.type === 'event' && ['user/message', 'assistant/message', 'tool/call', 'tool/result', 'step/start', 'step/end', 'turn/end'].includes(frame.event?.type)) {
            outgoing = { type: 'event', event: { type: frame.event.type } }
          }
          if (outgoing) response.write(`data: ${JSON.stringify(outgoing)}\n\n`)
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
      respond(response, 200, { items: result.items.filter(item => !item.blank)
        .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
        .map(item => ({ sessionId: item.sessionId, title: item.projections?.values?.title ?? '',
          workingDirectory: item.cwd, updatedAt: item.updatedAt, running: Boolean(item.running) })) })
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
