import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { randomUUID, timingSafeEqual } from 'node:crypto'
import { HOST_MESSAGE_WINDOW, recentItems, findDetail } from './recent.js'
import { listWorkspaceDirectory, uploadWorkspaceFile, downloadWorkspaceFile,
  listComputerRoots, listComputerDirectory, uploadComputerFile, downloadComputerFile } from './workspace-files.js'

export const name = 'hifairy-dsh-host-probe'
export const inject = ['sessionController', 'workspaceRegistry', 'agentPresets', 'fileUploads', 'sessions', 'permissionPresets']

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
  const subscribers = new Map()
  const clients = new Map()
  const approvals = new Map()
  const approvalBridge = {
    hasSubscriber: id => (subscribers.get(id) ?? 0) > 0,
    offer(request, next) {
      const session = request.agent?.session
      if (!session || !this.hasSubscriber(session.id)) return next()
      const last = session.eventAt(session.seq - 1)
      const id = last?.type === 'approval/asked' ? last.data?.id : null
      if (!id) return next()
      return new Promise(resolve => {
        const key = `${session.id}:${id}`
        const done = outcome => {
          if (!approvals.has(key)) return
          approvals.delete(key)
          request.signal?.removeEventListener('abort', onAbort)
          resolve(outcome === 'delegate' ? next() : outcome)
        }
        const onAbort = () => done('cancelled')
        approvals.set(key, { sessionId: session.id, id, toolName: request.toolName, reason: request.reason ?? '', done })
        request.signal?.addEventListener('abort', onAbort, { once: true })
        const frame = `data: ${JSON.stringify({ type: 'event', event: { type: 'approval/asked', data: { id } } })}\n\n`
        for (const client of clients.get(session.id) ?? []) client.write(frame)
      })
    }
  }
  options.approvalBridge = approvalBridge
  return createServer(async (request, response) => {
    if (!authorized(request, token)) return respond(response, 401, { error: 'unauthorized' })
    if (request.method === 'GET' && request.url === '/workspaces') {
      try {
        return respond(response, 200, { items: options.workspaceRegistry.list().map(item => ({
          id: item.id, title: item.title || item.path, path: item.path
        })) })
      } catch (error) { return respond(response, 503, { error: 'workspaces_unavailable', detail: String(error?.message ?? error) }) }
    }
    if (request.method === 'GET' && request.url === '/modes') {
      try {
        const presets = await options.agentPresets.list()
        return respond(response, 200, { items: presets.map(item => ({
          id: item.id, title: item.name || item.title || item.id,
          description: item.description || '', available: !item.error && !item.reason,
          isDefault: item.isDefault || item.id === options.agentPresets.defaultId
        })) })
      } catch (error) { return respond(response, 503, { error: 'modes_unavailable', detail: String(error?.message ?? error) }) }
    }
    if (request.method === 'GET' && request.url === '/permissions') {
      try { return respond(response, 200, options.permissionPresets.catalog()) }
      catch (error) { return respond(response, 503, { error: 'permissions_unavailable', detail: String(error?.message ?? error) }) }
    }
    const parsed = new URL(request.url ?? '/', 'http://localhost')
    const approvalMatch = /^\/sessions\/(session-[a-z0-9-]+)\/approvals(?:\/([a-zA-Z0-9-]+))?$/.exec(parsed.pathname)
    if (approvalMatch) {
      const [ , sessionId, id ] = approvalMatch
      if (request.method === 'GET' && !id) return respond(response, 200, { items: [...approvals.values()]
        .filter(item => item.sessionId === sessionId).map(({ sessionId, id, toolName, reason }) => ({ sessionId, id, toolName, reason })) })
      if (request.method === 'POST' && id) {
        const pending = approvals.get(`${sessionId}:${id}`)
        if (!pending) return respond(response, 409, { error: 'approval_expired' })
        const body = await readJson(request)
        if (!['allowed-once', 'rejected'].includes(body.outcome)) return respond(response, 400, { error: 'invalid_outcome' })
        pending.done(body.outcome)
        return respond(response, 200, { outcome: body.outcome })
      }
      return respond(response, 405, { error: 'method_not_allowed' })
    }
    const permissionMatch = /^\/sessions\/(session-[a-z0-9-]+)\/permission$/.exec(parsed.pathname)
    if (permissionMatch) {
      const session = options.sessions.list().find(item => item.id === permissionMatch[1])
      if (!session) return respond(response, 404, { error: 'session_not_found' })
      if (request.method === 'GET') return respond(response, 200, { value: options.permissionPresets.current(session) })
      if (request.method === 'POST') {
        try {
          const body = await readJson(request)
          if (typeof body.value !== 'string' || !options.permissionPresets.catalog().options.some(item => item.value === body.value))
            return respond(response, 400, { error: 'permission_not_found' })
          options.permissionPresets.set(session, body.value)
          return respond(response, 200, { value: options.permissionPresets.current(session) })
        } catch (error) { return respond(response, 503, { error: 'permission_update_failed', detail: String(error?.message ?? error) }) }
      }
      return respond(response, 405, { error: 'method_not_allowed' })
    }
    const workspaceMatch = /^\/workspaces\/([a-zA-Z0-9_-]+)\/(files|upload|download)$/.exec(parsed.pathname)
    const computerMatch = /^\/filesystem\/(roots|files|upload|download)$/.exec(parsed.pathname)
    if (workspaceMatch || computerMatch) {
      const workspaceId = workspaceMatch?.[1]
      const action = workspaceMatch?.[2] ?? computerMatch[1]
      const path = parsed.searchParams.get('path') ?? ''
      try {
        if (request.method === 'GET' && action === 'roots') return respond(response, 200, await listComputerRoots())
        if (request.method === 'GET' && action === 'files')
          return respond(response, 200, workspaceId
            ? await listWorkspaceDirectory(options.workspaceRegistry, workspaceId, path) : await listComputerDirectory(path))
        if (request.method === 'POST' && action === 'upload')
          return respond(response, 201, workspaceId
            ? await uploadWorkspaceFile(options.workspaceRegistry, workspaceId, path, parsed.searchParams.get('name') ?? '', request)
            : await uploadComputerFile(path, parsed.searchParams.get('name') ?? '', request))
        if (request.method === 'GET' && action === 'download') {
          const file = workspaceId ? await downloadWorkspaceFile(options.workspaceRegistry, workspaceId, path)
            : await downloadComputerFile(path)
          response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': file.bytes,
            'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`, 'Cache-Control': 'no-store' })
          file.stream.pipe(response)
          return
        }
        return respond(response, 405, { error: 'method_not_allowed' })
      } catch (error) { return respond(response, 400, { error: String(error?.message ?? error) }) }
    }
    const cancelMatch = /^\/sessions\/(session-[a-z0-9-]+)\/cancel$/.exec(request.url ?? '')
    if (request.method === 'POST' && cancelMatch) {
      try { controller.cancel({ sessionId: cancelMatch[1] }); return respond(response, 200, { cancelled: true }) }
      catch (error) { return respond(response, 503, { error: 'cancel_failed', detail: String(error?.message ?? error) }) }
    }
    const attachmentMatch = /^\/sessions\/(session-[a-z0-9-]+)\/attachments$/.exec(parsed.pathname)
    if (request.method === 'POST' && attachmentMatch) {
      try {
        const name = parsed.searchParams.get('name') || 'attachment'
        if (name.length > 180 || name.includes('/') || name.includes('\\')) return respond(response, 400, { error: 'invalid_filename' })
        let bytes = 0
        const data = (async function* () {
          for await (const chunk of request) {
            bytes += chunk.length
            if (bytes > 25 * 1024 * 1024) throw new Error('upload_too_large')
            yield chunk
          }
        })()
        const result = await options.fileUploads.uploadStream({ sessionId: attachmentMatch[1], name, data })
        return respond(response, 201, { receiptId: result.receiptId, name })
      } catch (error) { return respond(response, 400, { error: String(error?.message ?? error) }) }
    }
    if (request.method === 'POST' && request.url === '/sessions') {
      try {
        const body = await readJson(request)
        const workspaces = options.workspaceRegistry.list()
        const workspaceId = body.workspaceId || options.workspaceId
        if (!workspaces.some(item => item.id === workspaceId)) return respond(response, 400, { error: 'workspace_not_found' })
        const presets = await options.agentPresets.list()
        const agentPreset = body.agentPreset || options.agentPresets.defaultId
        if (!presets.some(item => item.id === agentPreset && !item.error && !item.reason))
          return respond(response, 400, { error: 'mode_not_found' })
        const permission = body.permission
        if (permission !== undefined && (typeof permission !== 'string' || !options.permissionPresets.catalog().options.some(item => item.value === permission)))
          return respond(response, 400, { error: 'permission_not_found' })
        const result = await controller.create({ workspaceId, agentPreset })
        if (permission !== undefined) {
          const session = options.sessions.list().find(item => item.id === result.sessionId)
          if (!session) return respond(response, 503, { error: 'session_not_found_after_create' })
          options.permissionPresets.set(session, permission)
        }
        return respond(response, 201, { sessionId: result.sessionId })
      } catch (error) {
        return respond(response, 503, { error: 'host_unavailable', detail: String(error?.message ?? error) })
      }
    }
    const promptMatch = /^\/sessions\/(session-[a-z0-9-]+)\/messages$/.exec(request.url ?? '')
    if (request.method === 'POST' && promptMatch) {
      try {
        const body = await readJson(request)
        if (typeof body.text !== 'string' || body.text.length > 8000 || !Array.isArray(body.attachments ?? [])
            || body.attachments?.length > 5 || (!body.text.trim() && !body.attachments?.length))
          return respond(response, 400, { error: 'invalid_text' })
        const sessionId = promptMatch[1]
        const listed = await controller.list({}, AbortSignal.timeout(5000))
        if (!listed.items.some(item => item.sessionId === sessionId)) return respond(response, 404, { error: 'session_not_found' })
        const requestId = randomUUID()
        const receipts = (body.attachments ?? []).map(value => {
          if (typeof value !== 'string' || !/^[a-zA-Z0-9-]{12,80}$/.test(value)) throw new Error('invalid_receipt')
          return { type: 'file', receiptId: value }
        })
        const content = [...(body.text.trim() ? [{ type: 'text', text: body.text }] : []), ...receipts]
        await controller.prompt({ requestId, sessionId, mode: 'queue', content }, AbortSignal.timeout(10000))
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
      subscribers.set(sessionId, (subscribers.get(sessionId) ?? 0) + 1)
      if (!clients.has(sessionId)) clients.set(sessionId, new Set())
      clients.get(sessionId).add(response)
      try {
        const stream = controller.follow({ address: { kind: 'session', sessionId }, maxMessages: 20, assistantStream: true }, abort.signal)[Symbol.asyncIterator]()
        for await (const frame of { [Symbol.asyncIterator]: () => stream }) {
          let outgoing
          if (frame.type === 'snapshot') outgoing = { type: 'snapshot', cursor: frame.cursor }
          else if (frame.type === 'assistant-stream') {
            const chunkType = frame.frame?.chunk?.type
            if (frame.frame?.type === 'start' || chunkType === 'block-start' || chunkType === 'text-delta') outgoing = frame
          } else if (frame.type === 'event' && ['user/message', 'assistant/message', 'tool/call', 'tool/result', 'step/start', 'step/end', 'turn/end', 'approval/asked', 'approval/decided'].includes(frame.event?.type)) {
            outgoing = { type: 'event', event: { type: frame.event.type,
              ...frame.event.type.startsWith('approval/') ? { data: frame.event.data } : {} } }
          }
          if (outgoing) response.write(`data: ${JSON.stringify(outgoing)}\n\n`)
        }
      } catch (error) {
        if (!abort.signal.aborted) response.write(`event: error\ndata: ${JSON.stringify({ error: String(error?.message ?? error) })}\n\n`)
      } finally {
        clients.get(sessionId)?.delete(response)
        const count = (subscribers.get(sessionId) ?? 1) - 1
        if (count > 0) subscribers.set(sessionId, count)
        else {
          subscribers.delete(sessionId)
          clients.delete(sessionId)
          for (const pending of [...approvals.values()].filter(item => item.sessionId === sessionId)) pending.done('delegate')
        }
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
  const bridgeOptions = {
    workspaceId: config.workspaceId, workspaceRegistry: ctx.workspaceRegistry, agentPresets: ctx.agentPresets,
    fileUploads: ctx.fileUploads, sessions: ctx.sessions, permissionPresets: ctx.permissionPresets
  }
  const server = createProbeServer(ctx.sessionController, token, bridgeOptions)
  ctx.effect(() => {
    server.listen(config.port ?? 18765, '127.0.0.1')
    return () => server.close()
  })
  ctx.on('approval/request', (request, next) => bridgeOptions.approvalBridge.offer(request, next), true)
}
