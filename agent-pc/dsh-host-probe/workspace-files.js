import { createReadStream, promises as fs } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024
const MAX_ENTRIES = 500

export async function workspacePath(registry, workspaceId, relativePath = '') {
  const item = registry.list().find(value => value.id === workspaceId)
  if (!item?.path || !isAbsolute(item.path)) throw new Error('workspace_not_found')
  const root = await fs.realpath(item.path)
  const requested = resolve(root, relativePath)
  const canonical = await fs.realpath(requested)
  const inside = relative(root.toLowerCase(), canonical.toLowerCase())
  if (inside === '..' || inside.startsWith(`..${sep}`) || isAbsolute(inside)) throw new Error('outside_workspace')
  return { root, path: canonical, relativePath: relative(root, canonical).split(sep).join('/') }
}

export async function listWorkspaceDirectory(registry, workspaceId, relativePath) {
  const location = await workspacePath(registry, workspaceId, relativePath)
  if (!(await fs.stat(location.path)).isDirectory()) throw new Error('not_directory')
  const entries = await fs.readdir(location.path, { withFileTypes: true })
  if (entries.length > MAX_ENTRIES) throw new Error('directory_too_large')
  return { path: location.relativePath, parent: location.relativePath ? dirname(location.relativePath).replaceAll('\\', '/').replace(/^\.$/, '') : null,
    items: entries.filter(entry => entry.isDirectory() || entry.isFile()).map(entry => ({ name: entry.name, directory: entry.isDirectory() }))
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name, 'zh-CN')) }
}

export async function uploadWorkspaceFile(registry, workspaceId, relativePath, name, body) {
  if (!name || name !== basename(name) || name === '.' || name === '..') throw new Error('invalid_filename')
  const location = await workspacePath(registry, workspaceId, relativePath)
  if (!(await fs.stat(location.path)).isDirectory()) throw new Error('not_directory')
  const target = join(location.path, name)
  const handle = await fs.open(target, 'wx')
  let bytes = 0
  try {
    for await (const chunk of body) {
      bytes += chunk.length
      if (bytes > MAX_UPLOAD_BYTES) throw new Error('upload_too_large')
      let offset = 0
      while (offset < chunk.length) offset += (await handle.write(chunk, offset, chunk.length - offset)).bytesWritten
    }
    return { name, path: target, relativePath: relative(location.root, target).split(sep).join('/'), bytes }
  } catch (error) {
    await handle.close()
    await fs.unlink(target).catch(() => {})
    throw error
  } finally { await handle.close().catch(() => {}) }
}

export async function downloadWorkspaceFile(registry, workspaceId, relativePath) {
  const location = await workspacePath(registry, workspaceId, relativePath)
  const stat = await fs.stat(location.path)
  if (!stat.isFile()) throw new Error('not_file')
  if (stat.size > MAX_UPLOAD_BYTES) throw new Error('file_too_large')
  return { stream: createReadStream(location.path), name: basename(location.path), bytes: stat.size }
}
