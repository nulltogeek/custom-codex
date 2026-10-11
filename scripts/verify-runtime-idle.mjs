#!/usr/bin/env node
// Read only: inspect the standalone runtime before considering a service restart.
import assert from 'node:assert/strict';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { privateJson } from '../bridge/auth.mjs';

const base = new URL(process.argv[2] ?? 'http://192.168.1.2:8787');
let token, peer;
const report = { endpoint: base.origin, idle: false };
try {
  const credentials = privateJson(process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'));
  const login = await fetch(new URL('/v1/login', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials), signal: AbortSignal.timeout(15000) });
  assert.equal(login.status, 200); token = (await login.json()).token;
  const headers = { Authorization: `Bearer ${token}` };
  const host = await fetch(new URL('/v1/host', base), { headers, signal: AbortSignal.timeout(15000) });
  assert.equal(host.status, 200); assert.equal((await host.json()).runtimeMode, 'standalone');
  const url = new URL('/v1/rpc', base); url.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  peer = new WebSocket(url, { headers, handshakeTimeout: 15000 });
  const pending = new Map(); let serial = 0;
  peer.on('error', () => {});
  peer.on('close', () => {
    for (const waiting of pending.values()) { clearTimeout(waiting.timer); waiting.reject(Error('Runtime inspection disconnected')); }
    pending.clear();
  });
  peer.on('message', data => {
    const message = JSON.parse(data.toString()), waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id); clearTimeout(waiting.timer);
    message.error ? waiting.reject(Error('Runtime inspection rejected')) : waiting.resolve(message.result);
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('Runtime inspection timed out')); }, 15000);
    pending.set(id, { resolve, reject, timer }); peer.send(JSON.stringify({ id, method, params }));
  });
  await call('initialize');
  const loaded = (await call('thread/loaded/list')).data;
  assert.ok(Array.isArray(loaded));
  const statuses = [];
  for (const id of loaded) statuses.push((await call('thread/read', { threadId: id, includeTurns: false })).thread.status.type);
  const snapshot = await call('bridge/subscribe');
  report.loadedChats = loaded.length; report.statuses = statuses;
  report.pendingRequests = snapshot.pendingRequests.length;
  report.idle = statuses.every(status => status === 'idle') && report.pendingRequests === 0;
  if (!report.idle) process.exitCode = 1;
} catch (error) { report.failure = error.code ?? error.name; process.exitCode = 1; }
finally {
  peer?.close();
  if (token) await fetch(new URL('/v1/logout', base), { method: 'POST', headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15000) }).catch(() => {});
  console.log(JSON.stringify(report, null, 2));
}
