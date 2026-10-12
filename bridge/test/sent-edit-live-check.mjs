// Isolated real-runtime regression: no desktop data, credentials or model calls.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { mobileOptions } from '../mobile-options.mjs';

const home = mkdtempSync(join(tmpdir(), 'codex-sent-edit-regression-'));
const id = randomUUID(), stamp = new Date().toISOString();
const directory = join(home, 'sessions', ...stamp.slice(0, 10).split('-'));
mkdirSync(directory, { recursive: true });
const path = join(directory, `rollout-${stamp.replaceAll(':', '-')}-${id}.jsonl`);
const entries = [
  { type: 'session_meta', payload: { id, timestamp: stamp, cwd: home, originator: 'writer_regression',
    cli_version: '0.159.2', source: 'cli', model_provider: 'openai', base_instructions: { text: 'Isolated test.' } } },
  { type: 'event_msg', payload: { type: 'task_started', turn_id: 'saved-turn', model_context_window: 128000, collaboration_mode_kind: 'default' } },
  { type: 'event_msg', payload: { type: 'user_message', message: 'Saved question.', images: [], local_images: [], text_elements: [] } },
  { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'Saved question.' }] } },
  { type: 'response_item', payload: { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Saved answer.' }] } },
  { type: 'event_msg', payload: { type: 'agent_message', message: 'Saved answer.', phase: 'final_answer' } },
  { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'saved-turn', last_agent_message: 'Saved answer.' } },
];
entries.push(...entries.slice(1).map(entry => JSON.parse(JSON.stringify(entry).replaceAll('saved-turn', 'second-turn').replaceAll('Saved question.', 'Second question.').replaceAll('Saved answer.', 'Second answer.'))));
writeFileSync(path, entries.map(entry => JSON.stringify({ timestamp: stamp, ...entry })).join('\n') + '\n');

async function runtime() {
  const child = spawn(process.env.CODEX_REAL_CLI_PATH ?? '/usr/lib/chatgpt/resources/codex',
    ['app-server', '--listen', 'stdio://'], { cwd: home, env: { ...process.env, CODEX_HOME: home }, stdio: ['pipe', 'pipe', 'ignore'] });
  const pending = new Map(); let serial = 0;
  createInterface({ input: child.stdout }).on('line', line => {
    const message = JSON.parse(line), waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id); clearTimeout(waiter.timer);
    if (message.error) waiter.reject(Object.assign(Error(message.error.message), { code: message.error.code }));
    else waiter.resolve(message.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const requestId = ++serial;
    const timer = setTimeout(() => { pending.delete(requestId); reject(Error(`${method} timed out`)); }, 15000);
    pending.set(requestId, { resolve, reject, timer });
    child.stdin.write(JSON.stringify({ id: requestId, method, params }) + '\n');
  });
  await call('initialize', { clientInfo: { name: 'writer_regression', version: '1' }, capabilities: { experimentalApi: true } });
  child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  return { call, close: () => new Promise(resolve => {
    child.once('close', resolve); child.stdin.end();
  }) };
}
const processes = [];
try {
  const owner = await runtime(); processes.push(owner);
  await owner.call('thread/resume', { threadId: id, path, excludeTurns: true });
  const phone = await runtime(); processes.push(phone);
  await assert.rejects(phone.call('thread/resume', { threadId: id, excludeTurns: true }), /already has an active writer/i);
  const before = readFileSync(path, 'utf8');
  for (const [beforeTurnId, expected] of [['saved-turn', []], ['second-turn', ['saved-turn']]]) {
    const fork = await phone.call('thread/fork', mobileOptions('thread/fork', {
      threadId: id, beforeTurnId, mobileMode: 'chat', accessMode: 'full', excludeTurns: true,
    }));
    assert.notEqual(fork.thread.id, id);
    assert.equal(fork.sandbox.type, 'readOnly');
    const history = await phone.call('thread/read', { threadId: fork.thread.id, includeTurns: true });
    assert.deepEqual(history.thread.turns.map(turn => turn.id), expected);
    assert.ok(!JSON.stringify(history.thread.turns).includes('Second question.'));
    assert.equal(readFileSync(path, 'utf8'), before);
    assert.equal((await owner.call('thread/read', { threadId: id })).thread.id, id);
  }
  await assert.rejects(phone.call('thread/fork', mobileOptions('thread/fork', {
    threadId: id, beforeTurnId: 'missing-turn', excludeTurns: true,
  })), /turn/i);
  assert.equal(readFileSync(path, 'utf8'), before);
  console.log(JSON.stringify({ passed: true, forkBeforeFirstMessage: true, forkBeforeLaterMessage: true,
    missingBoundaryRejected: true, originalUnchanged: true, originalOwnerStillConnected: true,
    conversationReadOnly: true, modelGeneration: false, isolatedCodexHome: true }, null, 2));
} finally {
  for (const process of processes.reverse()) await process.close();
}
