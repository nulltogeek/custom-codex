// Isolated real-runtime check: no production account, chats, or model calls.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { readProjects } from '../host.mjs';

const home = mkdtempSync(join(tmpdir(), 'codex-rename-check-'));
const threadId = randomUUID(), stamp = new Date().toISOString();
const directory = join(home, 'sessions', ...stamp.slice(0, 10).split('-'));
mkdirSync(directory, { recursive: true });
const path = join(directory, `rollout-${stamp.replaceAll(':', '-')}-${threadId}.jsonl`);
const entries = [
  { type: 'session_meta', payload: { id: threadId, timestamp: stamp, cwd: home, originator: 'rename_check',
    cli_version: '0.159.2', source: 'cli', model_provider: 'openai', base_instructions: { text: 'Isolated test.' } } },
  { type: 'event_msg', payload: { type: 'task_started', turn_id: 'saved', model_context_window: 128000, collaboration_mode_kind: 'default' } },
  { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'Saved question' }] } },
  { type: 'response_item', payload: { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Saved answer' }] } },
  { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'saved', last_agent_message: 'Saved answer' } },
];
writeFileSync(path, entries.map(entry => JSON.stringify({ timestamp: stamp, ...entry })).join('\n') + '\n');
async function runtime() {
  const child = spawn(process.env.CODEX_REAL_CLI_PATH ?? '/usr/lib/chatgpt/resources/codex',
    ['app-server', '--listen', 'stdio://'], { cwd: home, env: { ...process.env, CODEX_HOME: home }, stdio: ['pipe', 'pipe', 'ignore'] });
  const exited = new Promise(resolve => child.once('close', resolve));
  const pending = new Map(); let serial = 0;
  const output = createInterface({ input: child.stdout });
  output.on('line', line => {
    const message = JSON.parse(line), waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id); clearTimeout(waiter.timer);
    if (message.error) waiter.reject(Error(`${waiter.method}: ${message.error.message}`)); else waiter.resolve(message.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial;
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`${method} timed out`)); }, 15000);
    pending.set(id, { resolve, reject, timer, method });
    child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
  const close = async () => { child.stdin.end(); await exited; output.close(); };
  try {
    await call('initialize', { clientInfo: { name: 'rename_check', version: '1' }, capabilities: { experimentalApi: true } });
    child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  } catch (error) { await close(); throw error; }
  return { call, close };
}

let server = await runtime();
try {
  const created = await server.call('project/create', { idempotencyKey: randomUUID(), name: 'Original project', roots: [{ path: home }], metadata: { test: 'retained' } });
  const projectId = created.project.id;
  const result = await server.call('project/update', { projectId, name: 'Renamed project' });
  assert.equal(result.project.id, projectId); assert.equal(result.project.name, 'Renamed project');
  assert.deepEqual(result.project.roots, created.project.roots);
  assert.deepEqual(result.project.metadata, created.project.metadata);
  await server.call('thread/resume', { threadId, path, excludeTurns: true });
  await server.call('thread/metadata/update', { threadId, projectId });
  const before = (await server.call('thread/read', { threadId, includeTurns: true })).thread;
  await server.call('thread/name/set', { threadId, name: 'Renamed chat' });
  const catalog = readProjects(home);
  assert.equal(catalog.projects.find(p => p.id === projectId).name, 'Renamed project');
  assert.equal(catalog.assignments[threadId], projectId);
  await server.close(); server = await runtime();
  assert.equal((await server.call('project/read', { projectId })).project.name, 'Renamed project');
  const saved = (await server.call('thread/read', { threadId, includeTurns: true })).thread;
  assert.equal(saved.name, 'Renamed chat'); assert.equal(saved.projectId, projectId);
  assert.deepEqual(saved.turns, before.turns);
  console.log('PASS: chat/project names persist across restart; history, IDs, roots, metadata and membership remain intact. No model calls.');
} finally { await server.close(); }
