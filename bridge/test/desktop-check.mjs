// Verify the actual installed Electron desktop with isolated state/profile paths.
// Never quits or changes the user's existing desktop instance.
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import assert from 'node:assert/strict';

const temp = mkdtempSync(join(tmpdir(), 'codex-isolated-desktop-'));
const home = join(temp, 'codex'), profile = join(temp, 'profile'), tokenPath = join(temp, 'token');
mkdirSync(home); mkdirSync(profile);
const reservation = createServer(); reservation.listen(0, '127.0.0.1'); await once(reservation, 'listening');
const port = reservation.address().port; await new Promise(r => reservation.close(r));
const desktop = spawn('/usr/bin/chatgpt', [`--user-data-dir=${profile}`], { env: { ...process.env,
  CODEX_HOME: home, CODEX_SQLITE_HOME: home, CODEX_ELECTRON_USER_DATA_PATH: profile,
  CODEX_CLI_PATH: resolve('bridge/cli.mjs'), CODEX_REAL_CLI_PATH: '/usr/lib/chatgpt/resources/codex',
  CODEX_APP_SERVER_FORCE_CLI: '1', CODEX_BRIDGE_PORT: String(port), CODEX_BRIDGE_TOKEN_FILE: tokenPath,
  DISABLE_AUTO_UPDATE: '1' }, stdio: 'ignore' });
let peer;
try {
  let host, token;
  for (let attempt = 0; attempt < 240; attempt++) {
    if (desktop.exitCode !== null) throw Error(`Isolated desktop exited (${desktop.exitCode}) before initialization`);
    if (existsSync(tokenPath)) {
      token = readFileSync(tokenPath, 'utf8').trim();
      try { host = await (await fetch(`http://127.0.0.1:${port}/v1/host`, { headers: { Authorization: `Bearer ${token}` } })).json(); if (host.ready) break; }
      catch { /* Primary backend is still initializing. */ }
    }
    await new Promise(r => setTimeout(r, 250));
  }
  if (!host?.ready) console.log('Desktop handshake diagnostics:', JSON.stringify(host?.initialization ?? { listenerUnavailable: true }));
  assert.equal(host?.ready, true, 'Actual desktop must initialize through our launcher override');
  const isDescendant = pid => {
    for (let i = 0; i < 20; i++) {
      if (pid === desktop.pid) return true;
      try { pid = Number(readFileSync(`/proc/${pid}/status`, 'utf8').match(/^PPid:\s+(\d+)/m)[1]); } catch { return false; }
      if (!pid) return false;
    }
    return false;
  };
  assert.ok(isDescendant(host.backendPid), 'Backend must belong to this actual isolated desktop');
  peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } });
  let serial = 0; const waiting = new Map(); const events = [];
  peer.on('message', data => {
    const m = JSON.parse(data.toString());
    if (m.type === 'event') events.push(m.message);
    else if (m.id !== undefined) { const w = waiting.get(m.id); if (w) { waiting.delete(m.id); m.error ? w.reject(Error(m.error.message)) : w.resolve(m.result); } }
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial; const timer = setTimeout(() => { waiting.delete(id); reject(Error(`Timeout: ${method}`)); }, 15000);
    waiting.set(id, { resolve: v => { clearTimeout(timer); resolve(v); }, reject: e => { clearTimeout(timer); reject(e); } });
    peer.send(JSON.stringify({ id, method, params }));
  });
  await call('initialize'); await call('bridge/subscribe');
  const result = await call('thread/start', { ephemeral: true });
  const read = await call('thread/read', { threadId: result.thread.id, includeTurns: false });
  assert.equal(result.thread.id, read.thread.id);
  await new Promise(r => setTimeout(r, 300));
  assert.ok(events.some(m => m.method === 'thread/started' && m.params.thread.id === result.thread.id));
  console.log(JSON.stringify({ passed: true, actualInstalledDesktop: true, isolatedProfile: true,
    backendIsDesktopChild: true, phoneControlsDesktopRuntime: true, existingUserDesktopUntouched: true,
    modelGeneration: false }, null, 2));
} finally {
  peer?.close();
  // Stop only the test process we created; it has no model work or user state.
  desktop.kill('SIGTERM');
  await Promise.race([once(desktop, 'exit'), new Promise(r => setTimeout(r, 5000))]);
}
