import { readFileSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { posix } from 'node:path';

const execute = promisify(execFile);
const script = readFileSync(new URL('./workspace-files.py', import.meta.url), 'utf8');
const failure = (status, message) => Object.assign(Error(message), { status });

const writes = new Map();
export async function workspaceFiles(context, catalogue, input) {
  if (input?.action !== 'write') return performWorkspaceFiles(context, catalogue, input);
  // Serialize phone saves in an account, including overlapping project roots.
  const key = context.workspaceRoot ?? 'owner';
  const task = (writes.get(key) ?? Promise.resolve()).catch(() => {}).then(() => performWorkspaceFiles(context, catalogue, input));
  writes.set(key, task);
  try { return await task; } finally { if (writes.get(key) === task) writes.delete(key); }
}

async function performWorkspaceFiles(context, catalogue, input) {
  if (!input || Array.isArray(input) || typeof input !== 'object' ||
      Object.keys(input).some(key => !['action', 'projectId', 'rootIndex', 'path', 'query', 'offset', 'revision', 'content'].includes(key)) ||
      !['list', 'search', 'read', 'write'].includes(input.action) || typeof input.projectId !== 'string' ||
      input.projectId.length > 256 || !Number.isInteger(input.rootIndex) || input.rootIndex < 0 ||
      typeof input.path !== 'string' || input.path.length > 4096 ||
      (input.query !== undefined && (typeof input.query !== 'string' || input.query.length > 200)) ||
      (input.revision !== undefined && (typeof input.revision !== 'string' || !/^[a-f0-9]{64}$/.test(input.revision))))
    throw failure(400, 'Invalid file request.');
  if (input.content !== undefined && (typeof input.content !== 'string' || Buffer.byteLength(input.content) > 256 * 1024))
    throw failure(413, 'The editor supports text files up to 256 KB.');
  const project = catalogue.projects.find(project => project.id === input.projectId);
  if (!project) throw failure(404, 'This project is no longer available. Refresh projects.');
  const roots = project.rootPaths ?? project.roots?.map(root => typeof root === 'string' ? root : root.path) ?? [];
  const root = roots[input.rootIndex];
  if (typeof root !== 'string' || !posix.isAbsolute(root) || root.includes('\0')) throw failure(404, 'This project folder is unavailable.');
  const boundary = context.workspaceRoot;
  const relative = boundary && posix.relative(boundary, root);
  if (boundary && (relative === '..' || relative.startsWith('../') || posix.isAbsolute(relative))) throw failure(403, 'Choose a folder inside your private workspace.');
  const encoded = Buffer.from(JSON.stringify({ ...input, root, boundary })).toString('base64');
  const args = ['-I', '-c', script, ...encoded.match(/.{1,30000}/g)];
  let stdout;
  if (boundary) {
    if (typeof context.call !== 'function') throw failure(503, 'The private workspace is unavailable.');
    const result = await context.call('command/exec', { command: ['python3', ...args], cwd: boundary, timeoutMs: 10000, outputBytesCap: 4 * 1024 * 1024, sandboxPolicy: { type: 'dangerFullAccess' } });
    if (result.exitCode !== 0) throw failure(503, 'The file operation could not complete.');
    stdout = result.stdout;
  } else {
    ({ stdout } = await execute('/usr/bin/python3', args, { timeout: 15000, maxBuffer: 4 * 1024 * 1024 }));
  }
  let response;
  try { response = JSON.parse(stdout); } catch { throw failure(503, 'The file operation could not complete.'); }
  if (response.status !== 200) throw failure(response.status, response.error);
  return response.result;
}

export async function receiveFileRequest(req) {
  if ((req.headers['content-type'] ?? '').split(';')[0].trim() !== 'application/json') throw failure(415, 'Use application/json');
  req.setTimeout(15000, () => req.destroy());
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) throw failure(413, 'File request too large.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw failure(400, 'Invalid file request.'); }
}
