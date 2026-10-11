import test from 'node:test';
import assert from 'node:assert/strict';
import { Broker } from '../broker.mjs';

function harness(options = {}) {
  const upstream = [], desktop = [], phone = [];
  const broker = new Broker({ upstream: m => upstream.push(m), desktop: m => desktop.push(m), ...options });
  broker.addClient('phone', m => phone.push(m));
  const initialize = () => {
    const params = { clientInfo: { name: 'codex_desktop', title: 'Codex Desktop', version: '1' }, capabilities: { experimentalApi: true } };
    broker.accept('desktop', { method: 'initialize', id: 1, params });
    assert.deepEqual(upstream.at(-1).params, params);
    broker.receive({ id: upstream.at(-1).id, result: { userAgent: 'codex/0.159.2', codexHome: '/test' } });
    assert.equal(desktop.at(-1).id, 1);
    broker.accept('desktop', { method: 'initialized', params: {} });
    broker.accept('phone', { id: 1, method: 'bridge/subscribe', params: {} });
  };
  return { broker, upstream, desktop, phone, initialize };
}

test('desktop initialization unchanged; phone joins without a second upstream initialization', () => {
  const h = harness(); h.initialize(); assert.equal(h.broker.ready, true);
  const before = h.upstream.length;
  h.broker.accept('phone', { id: 2, method: 'initialize', params: {} });
  assert.equal(h.upstream.length, before); assert.equal(h.phone.at(-1).result.userAgent, 'codex/0.159.2');
});
test('installed desktop can join after successful initialize without emitting initialized', () => {
  const h = harness();
  h.broker.accept('desktop', { id: 1, method: 'initialize', params: { clientInfo: { title: 'Codex Desktop' } } });
  h.broker.receive({ id: h.upstream.at(-1).id, result: { userAgent: 'codex/0.159.2' } });
  assert.equal(h.broker.ready, true); assert.equal(h.broker.desktopNotificationSeen, false);
  h.broker.accept('phone', { id: 2, method: 'thread/loaded/list', params: {} });
  assert.equal(h.upstream.at(-1).method, 'thread/loaded/list');
});
test('colliding numeric and string client IDs are routed to the correct origin', () => {
  const h = harness(); h.initialize();
  h.broker.accept('desktop', { id: 7, method: 'thread/read', params: { threadId: 'a' } }); const a = h.upstream.at(-1).id;
  h.broker.accept('phone', { id: 7, method: 'thread/read', params: { threadId: 'b' } }); const b = h.upstream.at(-1).id;
  h.broker.accept('phone', { id: '7', method: 'thread/read', params: { threadId: 'c' } }); const c = h.upstream.at(-1).id;
  assert.notEqual(a, b);
  h.broker.receive({ id: b, result: { thread: 'b' } }); assert.equal(h.phone.at(-1).id, 7); assert.equal(h.phone.at(-1).result.thread, 'b');
  assert.equal(h.phone.at(-1).bridgeSequence, h.broker.sequence);
  h.broker.receive({ id: a, result: { thread: 'a' } }); assert.equal(h.desktop.at(-1).result.thread, 'a');
  h.broker.receive({ id: c, result: {} }); assert.equal(h.phone.at(-1).id, '7');
});
test('notifications reach desktop and subscribed phone in order', () => {
  const h = harness(); h.initialize();
  const m = { method: 'item/agentMessage/delta', params: { threadId: 'a', delta: 'hello' } };
  h.broker.receive(m); assert.deepEqual(h.desktop.at(-1), m); assert.deepEqual(h.phone.at(-1).message, m);
});
test('competing phone and desktop approval answers resolve once', () => {
  const h = harness(); h.initialize();
  h.broker.receive({ id: 99, method: 'item/commandExecution/requestApproval', params: { threadId: 'a', turnId: 't', command: 'test' } });
  const pending = h.broker.snapshot().pendingRequests[0]; const before = h.upstream.length;
  h.broker.accept('phone', { id: 4, method: 'bridge/respond', params: { requestId: pending.requestId, result: { decision: 'accept' } } });
  h.broker.accept('desktop', { id: 99, result: { decision: 'decline' } });
  assert.equal(h.upstream.length, before + 1); assert.deepEqual(h.upstream.at(-1), { id: 99, result: { decision: 'accept' } });
  assert.equal(h.broker.snapshot().pendingRequests.length, 0);
  assert.equal(h.desktop.at(-1).method, 'serverRequest/resolved');
  h.broker.accept('phone', { id: 5, method: 'bridge/respond', params: { requestId: pending.requestId, result: { decision: 'accept' } } });
  assert.equal(h.phone.at(-1).error.code, -32004);
});
test('desktop answer also removes the phone approval', () => {
  const h = harness(); h.initialize();
  h.broker.receive({ id: 'x', method: 'item/fileChange/requestApproval', params: { threadId: 'a' } });
  h.broker.accept('desktop', { id: 'x', result: { decision: 'decline' } });
  assert.equal(h.broker.snapshot().pendingRequests.length, 0);
  assert.equal(h.phone.at(-1).message.method, 'bridge/requestResolved');
});
test('desktop-only dynamic tools and attestation never appear as phone approvals', () => {
  const h = harness(); h.initialize(); const before = h.phone.length;
  for (const method of ['attestation/generate', 'item/tool/call']) {
    h.broker.receive({ id: method, method, params: { opaque: true } });
    assert.equal(h.desktop.at(-1).method, method);
    h.broker.accept('desktop', { id: method, result: {} });
  }
  assert.equal(h.phone.length, before); assert.equal(h.broker.snapshot().pendingRequests.length, 0);
});
test('disconnect does not resend a mutation or drop its upstream completion', () => {
  const h = harness(); h.initialize(); h.broker.accept('phone', { id: 8, method: 'turn/start', params: { threadId: 'a', input: [] } });
  const outgoing = h.upstream.at(-1); const before = h.upstream.length;
  h.broker.removeClient('phone'); h.broker.receive({ id: outgoing.id, result: {} });
  assert.equal(h.upstream.length, before); assert.equal(h.broker.requests.size, 0);
});
test('bounded replay reports gaps and backend epochs invalidate old cursors', () => {
  const h = harness({ maxEvents: 2 }); h.initialize();
  for (let i = 0; i < 4; i++) h.broker.receive({ method: 'tick', params: { i } });
  h.broker.addClient('reconnected', m => h.phone.push(m));
  h.broker.accept('reconnected', { id: 9, method: 'bridge/subscribe', params: { epoch: h.broker.epoch, sequence: 0 } });
  assert.equal(h.phone.at(-1).result.replayGap, true);
  h.broker.accept('reconnected', { id: 10, method: 'bridge/subscribe', params: { epoch: h.broker.epoch, sequence: 3 } });
  assert.equal(h.phone.at(-1).sequence, 4);
  h.broker.accept('reconnected', { id: 11, method: 'bridge/subscribe', params: { epoch: 'old-backend', sequence: 4 } });
  assert.equal(h.phone.at(-1).result.replayGap, true);
});
test('policy overrides, unsafe methods and invalid answers are rejected', () => {
  const h = harness(); h.initialize(); const before = h.upstream.length;
  h.broker.accept('phone', { id: 1, method: 'turn/start', params: { threadId: 'a', sandboxPolicy: { type: 'dangerFullAccess' } } });
  assert.equal(h.phone.at(-1).error.code, -32602);
  h.broker.accept('phone', { id: 2, method: 'command/exec', params: {} }); assert.equal(h.phone.at(-1).error.code, -32601);
  h.broker.receive({ id: 'ask', method: 'item/fileChange/requestApproval', params: { threadId: 'a' } });
  const r = h.broker.snapshot().pendingRequests[0];
  h.broker.accept('phone', { id: 3, method: 'bridge/respond', params: { requestId: r.requestId, result: { decision: 'arbitrary' } } });
  assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.upstream.length, before);
});
test('turn completion clears unanswered requests', () => {
  const h = harness(); h.initialize();
  h.broker.receive({ id: 1, method: 'item/tool/requestUserInput', params: { threadId: 'a', turnId: 't', questions: [] } });
  h.broker.receive({ method: 'turn/completed', params: { threadId: 'a', turn: { id: 't' } } });
  assert.equal(h.broker.snapshot().pendingRequests.length, 0);
});
test('backend project notifications invalidate the host adapter and sequence markers accompany phone responses', () => {
  const notifications = []; const h = harness({ onNotification: m => notifications.push(m) }); h.initialize();
  h.broker.receive({ method: 'project/changed', params: { projectId: 'p', changeType: 'updated' } });
  assert.equal(notifications.at(-1).method, 'project/changed');
  h.broker.accept('phone', { id: 6, method: 'thread/read', params: { threadId: 'a' } });
  h.broker.receive({ id: h.upstream.at(-1).id, result: { thread: { id: 'a' } } });
  assert.equal(h.phone.at(-1).bridgeSequence, 1); assert.equal(h.phone.at(-1).bridgeEpoch, h.broker.epoch);
});
test('host allowed decisions and typed form answers are validated before resolving a request', () => {
  const h = harness(); h.initialize();
  h.broker.receive({ id: 1, method: 'item/commandExecution/requestApproval', params: { availableDecisions: ['decline'] } });
  let request = h.broker.snapshot().pendingRequests[0];
  h.broker.accept('phone', { id: 3, method: 'bridge/respond', params: { requestId: request.requestId, result: { decision: 'accept' } } });
  assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.broker.snapshot().pendingRequests.length, 1);
  h.broker.accept('desktop', { id: 1, result: { decision: 'decline' } });
  h.broker.receive({ id: 2, method: 'mcpServer/elicitation/request', params: { requestedSchema: { type: 'object', properties: { count: { type: 'integer' } }, required: ['count'] } } });
  request = h.broker.snapshot().pendingRequests[0];
  h.broker.accept('phone', { id: 4, method: 'bridge/respond', params: { requestId: request.requestId, result: { action: 'accept', content: { count: 'bad' } } } });
  assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.broker.snapshot().pendingRequests.length, 1);
  h.broker.accept('phone', { id: 5, method: 'bridge/respond', params: { requestId: request.requestId, result: { action: 'accept', content: { count: 2 } } } });
  assert.equal(h.broker.snapshot().pendingRequests.length, 0);
});

test('phone deletion uses the upstream API, rejects extra fields and never retries after disconnect', () => {
  const h = harness(); h.initialize(); const before = h.upstream.length;
  for (const params of [{}, { threadId: '' }, { threadId: 'archive', path: '/tmp/chat' }]) {
    h.broker.accept('phone', { id: 50, method: 'thread/delete', params });
    assert.equal(h.phone.at(-1).error.code, -32602);
  }
  assert.equal(h.upstream.length, before);
  h.broker.accept('phone', { id: 51, method: 'thread/delete', params: { threadId: 'archive' } });
  const request = h.upstream.at(-1);
  assert.equal(request.method, 'thread/delete'); assert.deepEqual(request.params, { threadId: 'archive' });
  h.broker.removeClient('phone'); h.broker.receive({ id: request.id, result: {} });
  assert.equal(h.upstream.length, before + 1); assert.equal(h.broker.requests.size, 0);
});
