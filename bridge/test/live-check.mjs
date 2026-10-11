// Real bundled backend gate. No model generation, shell commands or persisted test chat.
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';

const temp = mkdtempSync(join(tmpdir(), 'codex-real-bridge-'));
const token = randomBytes(32).toString('base64url'); const tokenFile = join(temp, 'token');
writeFileSync(tokenFile, token, { mode: 0o600 });
const reservation = createServer(); reservation.listen(0, '127.0.0.1'); await once(reservation, 'listening');
const port = reservation.address().port; await new Promise(r => reservation.close(r));
const verifyModel = process.env.CODEX_VERIFY_MODEL === '1';
const proc = spawn(process.execPath, ['bridge/cli.mjs', 'app-server'], { env: { ...process.env,
  CODEX_BRIDGE_TOKEN_FILE: tokenFile, CODEX_BRIDGE_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
const pending = new Map(); const desktopEvents = []; const phoneEvents = []; let peer;
const deadline = setTimeout(() => { proc.stdin.end(); peer?.close(); process.exitCode = 1; console.error('Live gate timed out'); }, verifyModel ? 120000 : 30000);
const lines = createInterface({ input: proc.stdout });
lines.on('line', line => {
  const message = JSON.parse(line);
  if (message.method) desktopEvents.push(message);
  else { const p = pending.get(message.id); if (p) { pending.delete(message.id); message.error ? p.reject(Error(message.error.message)) : p.resolve(message.result); } }
});
let seq = 0;
const desktopCall = (method, params) => new Promise((resolve, reject) => {
  const id = ++seq; pending.set(id, { resolve, reject }); proc.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
});
try {
  await desktopCall('initialize', { clientInfo: { name: 'codex_desktop', title: 'Codex Desktop', version: 'bridge-test' }, capabilities: { experimentalApi: true } });
  proc.stdin.write(`${JSON.stringify({ method: 'initialized', params: {} })}\n`);
  let host;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { host = await (await fetch(`http://127.0.0.1:${port}/v1/host`, { headers: { Authorization: `Bearer ${token}` } })).json(); if (host.ready) break; }
    catch { /* Socket may not yet be listening. */ }
    await new Promise(r => setTimeout(r, 25));
  }
  assert.equal(host.ready, true); assert.ok(host.backendPid);
  const inventory = await (await fetch(`http://127.0.0.1:${port}/v1/projects`, { headers: { Authorization: `Bearer ${token}` } })).json();
  const catalogueCounts = {};
  for (const archived of [false, true]) {
    let cursor = null, count = 0;
    do {
      const page = await (await fetch(`http://127.0.0.1:${port}/v1/history?archived=${archived}` + (cursor ? `&cursor=${cursor}` : ''), { headers: { Authorization: `Bearer ${token}` } })).json();
      if (!page.data) throw Error('History catalogue failed'); count += page.data.length; cursor = page.nextCursor;
    } while (cursor);
    catalogueCounts[archived ? 'archived' : 'active'] = count;
  }
  peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } });
  const phonePending = new Map();
  peer.on('message', data => {
    const m = JSON.parse(data.toString());
    if (m.type === 'event') phoneEvents.push(m.message);
    else if (m.id !== undefined) { const p = phonePending.get(m.id); if (p) { phonePending.delete(m.id); m.error ? p.reject(Error(m.error.message)) : p.resolve(m.result); } }
  });
  await once(peer, 'open');
  const phoneCall = (method, params) => new Promise((resolve, reject) => { const id = ++seq; phonePending.set(id, { resolve, reject }); peer.send(JSON.stringify({ id, method, params })); });
  await phoneCall('bridge/subscribe', {});
  const phoneLoaded = await phoneCall('thread/loaded/list', {}); const desktopLoaded = await desktopCall('thread/loaded/list', {});
  assert.deepEqual(phoneLoaded, desktopLoaded);
  const sourceKinds = ['cli', 'vscode', 'exec', 'appServer', 'subAgent', 'subAgentReview', 'subAgentCompact', 'subAgentThreadSpawn', 'subAgentOther', 'unknown'];
  const counts = {};
  for (const archived of [false, true]) {
    let cursor = null, count = 0;
    do {
      const page = await phoneCall('thread/list', { archived, modelProviders: [], sourceKinds, limit: 50, cursor, useStateDbOnly: true, sortKey: 'updated_at' });
      if (!archived && !cursor && page.data.length) {
        for (const [method, params] of [
          ['thread/read', { threadId: page.data[0].id, includeTurns: true }],
          ['thread/turns/list', { threadId: page.data[0].id, limit: 5, itemsView: 'full', sortDirection: 'desc' }],
        ]) {
          try { const result = await phoneCall(method, params); console.log('Stored history probe:', method, 'OK',
            'turn count:', (result.thread?.turns ?? result.data ?? []).length,
            'item count:', (result.thread?.turns ?? result.data ?? []).reduce((n, t) => n + (t.items?.length ?? 0), 0)); }
          catch (e) { console.log('Stored history probe:', method, e.message); }
        }
      }
      count += page.data.length; cursor = page.nextCursor;
    } while (cursor);
    counts[archived ? 'archived' : 'active'] = count;
  }
  const thread = await phoneCall('thread/start', { ephemeral: true });
  assert.ok(thread.thread.id);
  const read = await desktopCall('thread/read', { threadId: thread.thread.id, includeTurns: false });
  assert.equal(read.thread.id, thread.thread.id);
  await new Promise(r => setTimeout(r, 100));
  assert.ok(desktopEvents.some(m => m.method === 'thread/started' && m.params.thread.id === thread.thread.id));
  assert.ok(phoneEvents.some(m => m.method === 'thread/started' && m.params.thread.id === thread.thread.id));
  let generatedSharedTurn = false;
  if (verifyModel) {
    const started = await desktopCall('turn/start', { threadId: thread.thread.id, input: [{ type: 'text', text:
      'This is a shared-runtime integration check. Do not use tools, read files or change files. Reply with SHARED_RUNTIME_OK.' }] });
    await phoneCall('turn/steer', { threadId: thread.thread.id, expectedTurnId: started.turn.id, input: [{ type: 'text', text:
      'Phone follow-up for the same integration check: include PHONE_FOLLOWUP_OK in your reply. Do not use tools or access files.' }] });
    const until = Date.now() + 90000;
    while (!phoneEvents.some(m => m.method === 'turn/completed' && m.params.threadId === thread.thread.id)) {
      if (Date.now() > until) throw Error('Model turn did not complete within 90 seconds');
      await new Promise(r => setTimeout(r, 100));
    }
    // Ephemeral conversations expose identity but intentionally have no stored turns.
    const desktopState = await desktopCall('thread/read', { threadId: thread.thread.id, includeTurns: false });
    const phoneState = await phoneCall('thread/read', { threadId: thread.thread.id, includeTurns: false });
    // Ephemeral read synthesizes current timestamps; compare persistent identity/state.
    for (const field of ['id', 'sessionId', 'model', 'modelProvider', 'status']) assert.deepEqual(phoneState.thread[field], desktopState.thread[field]);
    const desktopTurnEvents = desktopEvents.filter(m => m.params?.threadId === thread.thread.id);
    const phoneTurnEvents = phoneEvents.filter(m => m.params?.threadId === thread.thread.id);
    assert.deepEqual(phoneTurnEvents, desktopTurnEvents);
    assert.equal(phoneTurnEvents.filter(m => m.method === 'turn/started').length, 1, 'Steering must not duplicate a turn');
    const completed = phoneTurnEvents.filter(m => m.method === 'turn/completed');
    if (completed[0]?.params.turn.status !== 'completed') {
      const error = completed[0]?.params.turn.error;
      console.error('Backend turn failed:', JSON.stringify({ info: error?.codexErrorInfo, message: error?.message?.slice(0, 500) }));
    }
    assert.equal(completed.length, 1); assert.equal(completed[0].params.turn.status, 'completed');
    const replies = phoneTurnEvents.filter(m => m.method === 'item/completed' && m.params.item.type === 'agentMessage').map(m => m.params.item.text).join('\n');
    assert.ok(replies.includes('SHARED_RUNTIME_OK') && replies.includes('PHONE_FOLLOWUP_OK'));
    assert.ok(desktopEvents.some(m => m.method === 'turn/completed' && m.params.threadId === thread.thread.id));
    generatedSharedTurn = true;
  }
  console.log(JSON.stringify({ passed: true, realBackendPid: host.backendPid, backendVersion: host.backendVersion,
    projects: inventory.projects.length, sameEphemeralThreadVisibleToBoth: true,
    enumeratedLocalHistory: counts,
    completePersistedCatalogue: catalogueCounts,
    desktopAndPhoneNotifications: true, modelGeneration: generatedSharedTurn, actualDesktopRestarted: false }, null, 2));
} finally {
  clearTimeout(deadline); peer?.close(); proc.stdin.end(); lines.close();
  await once(proc, 'exit');
}
