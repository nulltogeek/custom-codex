#!/usr/bin/env node
// Verify the real mobile protocol without printing credentials or provider error bodies.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { WebSocket } from 'ws';
import { privateJson } from '../bridge/auth.mjs';

const base = new URL(process.argv[2] ?? 'https://codex.geeksun.ir');
const login = privateJson(process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'));
const report = { endpoint: base.origin, passed: false }; let token, peer;
const waiting = new Map(), events = []; let serial = 0;
async function post(path, body, headers = {}) {
  const response = await fetch(new URL(path, base), { method: 'POST', body, signal: AbortSignal.timeout(60000),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers } });
  return { status: response.status, body: await response.json() };
}
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++serial, timer = setTimeout(() => { waiting.delete(id); reject(Error('RPC timeout')); }, 20000);
  waiting.set(id, { resolve, reject, timer }); peer.send(JSON.stringify({ id, method, params }));
});
try {
  const signedIn = await post('/v1/login', JSON.stringify(login), { 'Content-Type': 'application/json' });
  assert.equal(signedIn.status, 200); token = signedIn.body.token;
  const host = await (await fetch(new URL('/v1/host', base), { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000) })).json();
  assert.equal(host.capabilities.attachments, true);
  const text = await post('/v1/attachments', 'Mobile attachment verification fixture.', { 'Content-Type': 'text/plain', 'X-File-Name': 'mobile-check.txt' });
  assert.equal(text.status, 201); assert.equal(text.body.input.type, 'text'); assert.match(text.body.input.text, /Mobile attachment verification fixture/);
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC', 'base64');
  const image = await post('/v1/attachments', png, { 'Content-Type': 'image/png', 'X-File-Name': 'mobile-check.png' });
  assert.equal(image.status, 201); assert.equal(image.body.input.type, 'localImage');
  report.authenticatedFileUpload = true; report.authenticatedImageUpload = true;
  const rpcUrl = new URL('/v1/rpc', base); rpcUrl.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  peer = new WebSocket(rpcUrl, { headers: { Authorization: `Bearer ${token}` }, handshakeTimeout: 15000 });
  peer.on('message', data => {
    const message = JSON.parse(data.toString());
    if (message.type === 'event') events.push(message.message);
    const request = waiting.get(message.id); if (!request) return;
    clearTimeout(request.timer); waiting.delete(message.id);
    message.error ? request.reject(Error(`RPC error ${message.error.code}`)) : request.resolve(message.result);
  });
  await new Promise((resolve, reject) => { peer.once('open', resolve); peer.once('error', reject); });
  await call('initialize'); await call('bridge/subscribe');
  const models = await call('model/list'); report.models = models.data.length;
  const examples = [];
  for (const [mode, access, expectedSandbox, expectedApproval] of [
    ['codex', 'ask', 'workspaceWrite', 'on-request'],
    ['codex', 'full', 'dangerFullAccess', 'never'],
    ['codex', 'read-only', 'readOnly', 'on-request'],
    ['chat', 'full', 'readOnly', 'never'],
  ]) {
    const result = await call('thread/start', { ephemeral: true, mobileMode: mode, accessMode: access });
    assert.equal(result.thread.historyMode, 'legacy'); assert.equal(result.sandbox.type, expectedSandbox); assert.equal(result.approvalPolicy, expectedApproval);
    const loaded = await call('thread/loaded/list'); assert.ok(loaded.data.includes(result.thread.id));
    const metadata = await call('thread/read', { threadId: result.thread.id, includeTurns: false }); assert.equal(metadata.thread.id, result.thread.id);
    examples.push({ mode, access, sandbox: result.sandbox.type, approval: result.approvalPolicy });
    if (mode === 'chat' && process.argv.includes('--model')) {
      const started = await call('turn/start', { threadId: result.thread.id, mobileMode: mode, accessMode: access,
        input: [{ type: 'text', text: 'Connection check with one tiny image and an attached text fixture. Do not use tools or change files. Reply with MOBILE_FEATURES_OK.' }, image.body.input, text.body.input] });
      report.promptAcknowledged = true;
      const deadline = Date.now() + 100000;
      while (!events.some(e => e.method === 'turn/completed' && e.params.turn.id === started.turn.id)) {
        if (Date.now() > deadline) throw Error('Model timeout'); await new Promise(resolve => setTimeout(resolve, 100));
      }
      const completed = events.find(e => e.method === 'turn/completed' && e.params.turn.id === started.turn.id);
      report.modelTurnStatus = completed.params.turn.status;
      report.modelErrorInfo = completed.params.turn.error?.codexErrorInfo ?? null;
      report.visionAndConversationGeneration = completed.params.turn.status === 'completed';
    }
  }
  report.accessPresets = examples; report.freshChatsLoadedWithoutResume = true;
  report.passed = true;
  if (process.argv.includes('--model') && !report.visionAndConversationGeneration) { report.passed = false; process.exitCode = 1; }
} catch (error) {
  report.failure = ['RPC timeout', 'Model timeout'].includes(error.message) || /^RPC error -?\d+$/.test(error.message) ? error.message : (error.code ?? error.name);
  process.exitCode = 1;
} finally {
  peer?.close(); for (const request of waiting.values()) clearTimeout(request.timer);
  if (token) { try { await post('/v1/logout', '{}', { 'Content-Type': 'application/json' }); } catch {} }
  console.log(JSON.stringify(report, null, 2));
}
