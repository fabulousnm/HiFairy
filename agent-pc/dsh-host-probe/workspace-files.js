import { createReadStream, promises as fs } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024
const MAX_ENTRIES = 500

function namesOnly(entries) {
  if (entries.length > MAX_ENTRIES) throw new Error('directory_too_large')
  return entries.filter(entry => entry.isDirectory() || entry.isFile())
    .map(entry => ({ name: entry.name, directory: entry.isDirectory() }))
    .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name, 'zh-CN'))
}

async function absoluteComputerPath(path) {
  if (!path || !isAbsolute(path)) throw new Error('absolute_path_required')
  return fs.realpath(path)
}

export async function listComputerRoots() {
  if (process.platform !== 'win32') return { path: '', parent: null, items: [{ name: '/', directory: true }] }
  const drives = await Promise.all('ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('').map(async letter => {
    const path = `${letter}:\\`
    try { return (await fs.stat(path)).isDirectory() ? { name: path, directory: true } : null }
    catch { return null }
  }))
  return { path: '', parent: null, items: drives.filter(Boolean) }
}

export async function listComputerDirectory(path) {
  const directory = await absoluteComputerPath(path)
  if (!(await fs.stat(directory)).isDirectory()) throw new Error('not_directory')
  const parent = dirname(directory) === directory ? '' : dirname(directory)
  return { path: directory, parent, items: namesOnly(await fs.readdir(directory, { withFileTypes: true })) }
}

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
  return { path: location.relativePath, parent: location.relativePath ? dirname(location.relativePath).replaceAll('\\', '/').replace(/^\.$/, '') : null,
    items: namesOnly(await fs.readdir(location.path, { withFileTypes: true })) }
}

async function uploadToDirectory(directory, name, body) {
  if (!name || name !== basename(name) || name === '.' || name === '..') throw new Error('invalid_filename')
  if (!(await fs.stat(directory)).isDirectory()) throw new Error('not_directory')
  const target = join(directory, name)
  const handle = await fs.open(target, 'wx')
  let bytes = 0
  try {
    for await (const chunk of body) {
      bytes += chunk.length
      if (bytes > MAX_UPLOAD_BYTES) throw new Error('upload_too_large')
      let offset = 0
      while (offset < chunk.length) offset += (await handle.write(chunk, offset, chunk.length - offset)).bytesWritten
    }
    return { name, path: target, bytes }
  } catch (error) {
    await handle.close()
    await fs.unlink(target).catch(() => {})
    throw error
  } finally { await handle.close().catch(() => {}) }
}

export async function uploadWorkspaceFile(registry, workspaceId, relativePath, name, body) {
  const location = await workspacePath(registry, workspaceId, relativePath)
  const result = await uploadToDirectory(location.path, name, body)
  return { ...result, relativePath: relative(location.root, result.path).split(sep).join('/') }
}

export async function uploadComputerFile(path, name, body) {
  return uploadToDirectory(await absoluteComputerPath(path), name, body)
}

async function downloadable(path) {
  const stat = await fs.stat(path)
  if (!stat.isFile()) throw new Error('not_file')
  if (stat.size > MAX_UPLOAD_BYTES) throw new Error('file_too_large')
  return { stream: createReadStream(path), name: basename(path), bytes: stat.size }
}

export async function downloadComputerFile(path) { return downloadable(await absoluteComputerPath(path)) }

export async function downloadWorkspaceFile(registry, workspaceId, relativePath) {
  const location = await workspacePath(registry, workspaceId, relativePath)
  return downloadable(location.path)
}
