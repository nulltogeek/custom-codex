#!/usr/bin/env node
// Read private credentials locally. Print only verification metadata, never model/provider errors.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { request } from 'node:https';
import { request as httpRequest } from 'node:http';
import { createRequire } from 'node:module';
import { WebSocket } from 'ws';
import { readProjects } from '../bridge/host.mjs';
import { privateJson } from '../bridge/auth.mjs';
const base = new URL(process.argv[2] ?? 'http://192.168.1.2:8787');
const loginFile = process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json');
const credentials = privateJson(loginFile);
let token;
const clients = []; const report = { endpoint: base.origin, passed: false };
async function deniedSocket(headers) {
  const url = new URL('/v1/rpc', base); url.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  await new Promise((resolve, reject) => {
    const peer = new WebSocket(url, { headers, handshakeTimeout: 15000 });
    peer.on('error', () => {});
    peer.once('open', () => { peer.close(); reject(Error('Unauthorized WebSocket opened')); });
    peer.once('unexpected-response', (_req, res) => {
      const status = res.statusCode; res.resume(); peer.terminate();
      status === 401 ? resolve() : reject(Error('Unexpected WebSocket denial'));
    });
    peer.once('error', () => reject(Error('WebSocket denial check failed')));
  });
}
function get(path, headers = { Authorization: `Bearer ${token}` }) {
  return new Promise((resolve, reject) => {
    const transport = base.protocol === 'https:' ? request : httpRequest;
    const req = transport(new URL(path, base), { headers, timeout: 15000 }, res => {
      let body = ''; res.on('data', part => { body += part; });
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(body) }); } catch { reject(Error('Invalid HTTP response')); }
      });
    });
    req.on('timeout', () => req.destroy(Error('HTTP timeout'))); req.on('error', reject); req.end();
  });
}
function post(path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const encoded = JSON.stringify(body);
    const transport = base.protocol === 'https:' ? request : httpRequest;
    const req = transport(new URL(path, base), { method: 'POST', timeout: 15000,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(encoded), ...headers } }, res => {
      let data = ''; res.on('data', chunk => { data += chunk; });
      res.on('end', () => { try { resolve({ status: res.statusCode, body: JSON.parse(data) }); } catch { reject(Error('Invalid HTTP response')); } });
    });
    req.on('timeout', () => req.destroy(Error('HTTP timeout'))); req.on('error', reject); req.end(encoded);
  });
}
async function connect() {
  const url = new URL('/v1/rpc', base); url.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  const peer = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` }, handshakeTimeout: 15000 });
  const pending = new Map(), events = []; let serial = 0;
  peer.on('message', data => {
    const message = JSON.parse(data.toString());
    if (message.type === 'event') events.push(message);
    else if (message.id !== undefined && pending.has(message.id)) {
      const waiter = pending.get(message.id); pending.delete(message.id); clearTimeout(waiter.timer);
      message.error ? waiter.reject(Error(`RPC error ${message.error.code}`)) : waiter.resolve(message.result);
    }
  });
  await new Promise((resolve, reject) => { peer.once('open', resolve); peer.once('error', reject); });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('RPC timeout')); }, 20000);
    pending.set(id, { resolve, reject, timer }); peer.send(JSON.stringify({ id, method, params }));
  });
  peer.on('close', () => { for (const waiter of pending.values()) { clearTimeout(waiter.timer); waiter.reject(Error('Connection closed')); } pending.clear(); });
  const client = { peer, call, events }; clients.push(client);
  await call('initialize'); return client;
}
async function until(predicate, timeout = 10000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) { if (Date.now() > deadline) throw Error('Expected event timeout'); await new Promise(r => setTimeout(r, 50)); }
}
try {
  assert.equal((await get('/healthz', {})).status, 200);
  assert.equal((await post('/v1/login', { ...credentials, password: 'invalid-verification-password' })).status, 401);
  assert.equal((await post('/v1/login', credentials, { Origin: 'https://example.com' })).status, 401);
  const login = await post('/v1/login', credentials); assert.equal(login.status, 200);
  token = login.body.token; assert.equal(login.body.username, credentials.username);
  report.login = { username: login.body.username, incorrectPasswordRejected: true, browserOriginRejected: true, successful: true };
  assert.equal((await get('/v1/host', {})).status, 401);
  assert.equal((await get('/v1/host', { Authorization: 'Bearer invalid' })).status, 401);
  assert.equal((await get('/v1/host', { Authorization: `Bearer ${token}`, Origin: 'https://example.com' })).status, 401);
  await deniedSocket({});
  await deniedSocket({ Authorization: `Bearer ${token}`, Origin: 'https://example.com' });
  const host = await get('/v1/host'); assert.equal(host.status, 200); assert.equal(host.body.ready, true);
  assert.equal(host.body.runtimeMode, 'standalone');
  report.host = { backendVersion: host.body.backendVersion, backendPid: host.body.backendPid, configFile: host.body.configFile, mode: host.body.runtimeMode };
  report.authentication = { noTokenRejected: true, badTokenRejected: true, browserOriginRejected: true, validTokenAccepted: true, unauthorizedWebSocketRejected: true, browserWebSocketRejected: true };
  const phone = await connect(), second = await connect();
  await phone.call('bridge/subscribe'); await second.call('bridge/subscribe');
  const models = await phone.call('model/list'); assert.ok(models.data.length); report.models = models.data.length;
  const projects = await get('/v1/projects'); assert.equal(projects.status, 200); assert.ok(!projects.body.error);
  report.projects = projects.body.projects.length;
  const catalogue = [];
  for (const archived of [false, true]) {
    let cursor = null, count = 0;
    do {
      const path = `/v1/history?archived=${archived}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
      const page = await get(path); assert.equal(page.status, 200);
      catalogue.push(...page.body.data); count += page.body.data.length; cursor = page.body.nextCursor;
    } while (cursor);
    report[archived ? 'archivedChats' : 'activeChats'] = count;
  }
  if (process.argv.includes('--local-inventory')) {
    const require = createRequire(import.meta.url); const { DatabaseSync } = require('node:sqlite');
    const db = new DatabaseSync(join(host.body.codexHome, 'state_5.sqlite'), { readOnly: true });
    try {
      const rows = db.prepare('SELECT id,archived FROM threads').all();
      assert.deepEqual(catalogue.map(t => t.id).sort(), rows.map(t => t.id).sort());
      assert.deepEqual(projects.body, readProjects(host.body.codexHome));
      report.completeLocalInventoryMatched = true;
    } finally { db.close(); }
  }
  const thread = await phone.call('thread/start', { ephemeral: true }); const threadId = thread.thread.id;
  const read = await second.call('thread/read', { threadId, includeTurns: false }); assert.equal(read.thread.id, threadId);
  await until(() => [phone, second].every(c => c.events.some(e => e.message.method === 'thread/started' && e.message.params.thread.id === threadId)));
  report.sharedRuntimeAndMirroredEvents = true;
  if (process.argv.includes('--model')) {
    const start = await phone.call('turn/start', { threadId, input: [{ type: 'text', text: 'Connection verification. Do not use tools, read files, or change files. Reply with REMOTE_CODEX_OK.' }] });
    const turnId = start.turn.id;
    await until(() => [phone, second].every(c => c.events.some(e => e.message.method === 'turn/completed' && e.message.params.turn.id === turnId)), 100000);
    const relevant = c => c.events.filter(e => e.message.params?.threadId === threadId).map(e => e.message);
    assert.deepEqual(relevant(phone), relevant(second));
    const completed = relevant(phone).find(m => m.method === 'turn/completed' && m.params.turn.id === turnId);
    report.modelTurnStatus = completed.params.turn.status;
    if (report.modelTurnStatus !== 'completed') {
      report.modelErrorInfo = completed.params.turn.error?.codexErrorInfo ?? null;
      throw Error('Model turn failed (provider error text intentionally omitted)');
    }
    const reply = relevant(phone).filter(m => m.method === 'item/completed' && m.params.item.type === 'agentMessage').map(m => m.params.item.text).join(' ');
    assert.ok(reply.includes('REMOTE_CODEX_OK')); report.modelStreaming = true;
    // A follow-up from the other phone must run in the same server-owned chat.
    const followup = await second.call('turn/start', { threadId, input: [{ type: 'text', text: 'Phone follow-up verification. Reply with PHONE_FOLLOWUP_OK. Do not use tools or access files.' }] });
    await until(() => [phone, second].every(c => c.events.some(e => e.message.method === 'turn/completed' && e.message.params.turn.id === followup.turn.id)), 100000);
    assert.deepEqual(relevant(phone), relevant(second));
    const finished = relevant(phone).find(m => m.method === 'turn/completed' && m.params.turn.id === followup.turn.id);
    assert.equal(finished.params.turn.status, 'completed');
    assert.equal(relevant(phone).filter(m => m.method === 'turn/started').length, 2);
    report.phoneFollowupConvergedWithoutDuplicateTurns = true;
  }
  const snapshot = (await get('/v1/snapshot')).body;
  phone.peer.close(); await new Promise(r => setTimeout(r, 100));
  const reconnected = await connect();
  const replay = await reconnected.call('bridge/subscribe', { epoch: snapshot.epoch, sequence: snapshot.sequence });
  assert.equal(replay.replayGap, false);
  assert.equal((await second.call('thread/read', { threadId, includeTurns: false })).thread.id, threadId);
  report.disconnectAndReconnect = true;
  const closed = new Promise(resolve => reconnected.peer.once('close', code => resolve(code)));
  assert.equal((await post('/v1/logout', {}, { Authorization: `Bearer ${token}` })).status, 200);
  assert.equal(await closed, 1008);
  assert.equal((await get('/v1/host')).status, 401);
  await deniedSocket({ Authorization: `Bearer ${token}` });
  report.signOutRevokesHttpAndWebSockets = true;
  report.passed = true;
} catch (error) {
  report.failure = ['Expected event timeout', 'HTTP timeout', 'RPC timeout', 'Model turn failed (provider error text intentionally omitted)'].includes(error.message)
    ? error.message : (error.code ?? error.name);
  process.exitCode = 1;
} finally {
  if (token) { try { await post('/v1/logout', {}, { Authorization: `Bearer ${token}` }); } catch {} }
  for (const client of clients) client.peer.close();
  console.log(JSON.stringify(report, null, 2));
}
