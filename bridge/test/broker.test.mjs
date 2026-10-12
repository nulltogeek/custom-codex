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

test('disabled output saves live bytes while preserving command status, sequence and other clients', () => {
  const h = harness(); h.initialize(); const full = [];
  h.broker.addClient('full', m => full.push(m));
  h.broker.accept('full', { id: 1, method: 'bridge/subscribe', params: {} });
  const before = h.upstream.length;
  h.broker.accept('phone', { id: 2, method: 'bridge/commandOutput', params: { loadCommandOutput: false } });
  assert.equal(h.phone.at(-1).result.loadCommandOutput, false);
  assert.equal(h.upstream.length, before);
  const output = 'command output\n'.repeat(100000);
  const command = { id: 'cmd', type: 'commandExecution', command: 'run tests', status: 'completed', exitCode: 0, aggregatedOutput: output };
  for (const m of [
    { method: 'item/started', params: { threadId: 'chat', turnId: 'turn', item: { ...command, status: 'inProgress' } } },
    { method: 'item/commandExecution/outputDelta', params: { threadId: 'chat', turnId: 'turn', itemId: 'cmd', delta: output } },
    { method: 'item/completed', params: { threadId: 'chat', turnId: 'turn', item: command } },
  ]) {
    h.broker.receive(m);
    const reduced = h.phone.at(-1);
    assert.ok(JSON.stringify(reduced).length < 500);
    assert.equal(reduced.sequence, full.at(-1).sequence);
    assert.deepEqual(full.at(-1).message, m); assert.deepEqual(h.desktop.at(-1), m);
    assert.deepEqual(h.broker.events.at(-1).event.message, m);
  }
  const item = h.phone.at(-1).message.params.item;
  assert.equal(item.command, 'run tests'); assert.equal(item.status, 'completed'); assert.equal(item.exitCode, 0);
  assert.equal(item.aggregatedOutput, undefined); assert.equal(item._commandOutputOmitted, true);
  assert.equal(command.aggregatedOutput, output);
  h.broker.accept('phone', { id: 3, method: 'bridge/commandOutput', params: { loadCommandOutput: true } });
  h.broker.receive({ method: 'item/commandExecution/outputDelta', params: { itemId: 'cmd', delta: output } });
  assert.equal(h.phone.at(-1).message.params.delta, output);
});

test('command output is omitted from paginated, legacy and resume responses without changing other content', () => {
  const results = [];
  const h = harness({ onResult: (method, result) => results.push(result) }); h.initialize();
  h.broker.accept('phone', { id: 2, method: 'bridge/commandOutput', params: { loadCommandOutput: false } });
  const items = [
    { id: 'cmd', type: 'commandExecution', command: 'test', status: 'completed', aggregatedOutput: 'big'.repeat(10000), output: 'alternate'.repeat(10000) },
    { id: 'u', type: 'userMessage', content: [{ type: 'text', text: 'user text' }] },
    { id: 'a', type: 'agentMessage', text: 'assistant text' },
    { id: 'f', type: 'fileChange', changes: [{ path: 'a', diff: '+change' }] },
    { id: 'tool', type: 'mcpToolCall', result: { output: 'tool result', structuredContent: { type: 'commandExecution', output: 'tool data' } } },
  ];
  for (const [method, result] of [
    ['thread/read', { thread: { id: 'chat', turns: [{ id: 'turn', items }] } }],
    ['thread/turns/list', { data: [{ id: 'turn', items }], nextCursor: 'older' }],
    ['thread/items/list', { data: items, nextCursor: 'older' }],
    ['thread/resume', { thread: { id: 'chat', turns: [{ id: 'turn', items }] } }],
  ]) {
    h.broker.accept('phone', { id: 10, method, params: { threadId: 'chat' } });
    h.broker.receive({ id: h.upstream.at(-1).id, result });
    const filtered = h.phone.at(-1).result;
    const reducedItems = filtered.thread?.turns[0].items ?? (method === 'thread/items/list' ? filtered.data : filtered.data[0].items);
    assert.equal(reducedItems[0].output, undefined); assert.equal(reducedItems[0].aggregatedOutput, undefined);
    assert.deepEqual(reducedItems.slice(1), items.slice(1));
    assert.equal(filtered.nextCursor, result.nextCursor);
    assert.equal(h.phone.at(-1).bridgeSequence, h.broker.sequence);
    assert.equal(results.at(-1), result); assert.equal(items[0].aggregatedOutput.length, 30000);
    assert.ok(JSON.stringify(filtered).length < JSON.stringify(result).length / 100);
  }
});

test('filtered reconnect replay preserves cursors and leaves approval details unchanged', () => {
  const h = harness(); h.initialize();
  h.broker.receive({ method: 'item/commandExecution/outputDelta', params: { threadId: 'chat', turnId: 'turn', itemId: 'cmd', delta: 'large'.repeat(10000) } });
  const approvalParams = { threadId: 'chat', command: 'dangerous command', reason: 'Please review', availableDecisions: ['decline'],
    extra: { type: 'commandExecution', output: 'approval details' } };
  h.broker.receive({ id: 40, method: 'item/commandExecution/requestApproval', params: approvalParams });
  const replay = []; h.broker.addClient('replay', m => replay.push(m));
  h.broker.accept('replay', { id: 20, method: 'bridge/subscribe', params: { epoch: h.broker.epoch, sequence: 0, loadCommandOutput: false } });
  assert.equal(replay[0].result.replayGap, false); assert.equal(replay[0].result.loadCommandOutput, false);
  assert.deepEqual(replay[0].result.pendingRequests[0].params, approvalParams);
  assert.equal(replay[1].sequence, 1); assert.equal(replay[1].message.params.delta, '');
  assert.equal(replay[1].message.params._commandOutputOmitted, true);
  assert.equal(replay[2].sequence, 2); assert.deepEqual(replay[2].message.params.params, approvalParams);
  h.broker.accept('replay', { id: 21, method: 'bridge/subscribe', params: { epoch: h.broker.epoch, sequence: 0, loadCommandOutput: true } });
  assert.equal(replay.at(-2).message.params.delta, 'large'.repeat(10000));
});

test('invalid command output preferences are rejected without changing client settings', () => {
  const h = harness(); h.initialize(); const before = h.upstream.length;
  for (const method of ['bridge/commandOutput', 'bridge/subscribe']) {
    for (const loadCommandOutput of [null, 'false', 0, {}]) {
      h.broker.accept('phone', { id: 4, method, params: { loadCommandOutput } });
      assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.broker.clients.get('phone').loadCommandOutput, true);
    }
  }
  h.broker.accept('phone', { id: 5, method: 'bridge/commandOutput', params: { loadCommandOutput: false, extra: true } });
  assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.upstream.length, before);
});

test('chat project assignment uses the existing metadata API and broadcasts membership updates', () => {
  const h = harness(); h.initialize(); const second = [];
  h.broker.addClient('second', message => second.push(message));
  h.broker.accept('second', { id: 1, method: 'bridge/subscribe', params: {} });
  h.broker.accept('phone', { id: 50, method: 'thread/metadata/update', params: { threadId: 'c', projectId: 'p' } });
  assert.deepEqual(h.upstream.at(-1).params, { threadId: 'c', projectId: 'p' });
  h.broker.receive({ id: h.upstream.at(-1).id, result: { thread: { id: 'c', projectId: 'p' } } });
  assert.equal(h.phone.at(-1).result.thread.projectId, 'p');
  const update = { method: 'thread/project/updated', params: { threadId: 'c', projectId: 'p' } };
  h.broker.receive(update);
  assert.deepEqual(second.at(-1).message, update); assert.deepEqual(h.phone.at(-1).message, update);
});

test('project creation forwards a stable request key, name and normalized optional folders', () => {
  const h = harness(); h.initialize();
  const idempotencyKey = '12345678-1234-1234-1234-123456789abc';
  const params = { idempotencyKey, name: '  Work  ', roots: [{ path: '/work/a/../b' }, { path: '/work/b' }] };
  for (const id of [100, 101]) {
    h.broker.accept('phone', { id, method: 'project/create', params });
    assert.deepEqual(h.upstream.at(-1).params, { idempotencyKey, name: 'Work', roots: [{ path: '/work/b' }] });
    h.broker.receive({ id: h.upstream.at(-1).id, result: { project: { id: 'p', name: 'Work' } } });
    assert.equal(h.phone.at(-1).id, id);
  }
  h.broker.accept('phone', { id: 102, method: 'project/create', params: { idempotencyKey, name: 'No folders', roots: [] } });
  assert.deepEqual(h.upstream.at(-1).params.roots, []);
});

test('invalid project creation names, keys, folders and extra mutations never reach the backend', () => {
  const h = harness(); h.initialize(); const before = h.upstream.length;
  const valid = { idempotencyKey: '12345678-1234-1234-1234-123456789abc', name: 'Work', roots: [] };
  for (const change of [
    { name: ' ' }, { name: 'x'.repeat(201) }, { idempotencyKey: '' }, { roots: 'wrong' },
    { roots: [{ path: 'relative' }] }, { roots: [{ path: '/bad\u0000folder' }] },
    { roots: Array.from({ length: 9 }, () => ({ path: '/work' })) }, { roots: [{ path: '/work', extra: true }] },
    { metadata: { changed: 'yes' } },
  ]) {
    h.broker.accept('phone', { id: 103, method: 'project/create', params: { ...valid, ...change } });
    assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.upstream.length, before);
  }
});

test('phones rename chats and projects with name-only requests and receive their own result', () => {
  const h = harness(); h.initialize();
  for (const [method, identity] of [['thread/name/set', 'threadId'], ['project/update', 'projectId']]) {
    h.broker.accept('phone', { id: 10, method, params: { [identity]: 'saved', name: '  پروژه جدید  ' } });
    assert.equal(h.upstream.at(-1).method, method);
    assert.deepEqual(h.upstream.at(-1).params, { [identity]: 'saved', name: 'پروژه جدید' });
    h.broker.receive({ id: h.upstream.at(-1).id, result: { ok: true } });
    assert.equal(h.phone.at(-1).id, 10); assert.equal(h.phone.at(-1).result.ok, true);
  }
});

test('project rename cannot change roots or metadata and invalid names never reach the backend', () => {
  const h = harness(); h.initialize(); const before = h.upstream.length;
  for (const params of [
    { projectId: 'p', name: 'Valid', roots: [{ path: '/other' }] },
    { projectId: 'p', name: 'Valid', metadata: { changed: 'yes' } },
    { projectId: 'p', name: ' ' }, { projectId: '', name: 'Valid' },
    { projectId: 'p', name: 'x'.repeat(201) }, { projectId: 'p', name: 'First\nSecond' },
  ]) {
    h.broker.accept('phone', { id: 11, method: 'project/update', params });
    assert.equal(h.phone.at(-1).error.code, -32602); assert.equal(h.upstream.length, before);
  }
});

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
test('async question notifications reach phones and replies steer without a pending approval', () => {
  const h = harness(); h.initialize();
  const item = { id: 'question', type: 'agentMessage', phase: 'commentary', delivery: 'async', text: '',
    questions: [{ title: 'Which format?', options: ['PNG', 'JPEG'] }, { title: 'What size?' }] };
  h.broker.receive({ method: 'item/completed', params: { threadId: 'chat', turnId: 'turn', item } });
  assert.deepEqual(h.phone.at(-1).message.params.item, item);
  assert.equal(h.broker.snapshot().pendingRequests.length, 0);
  const params = { threadId: 'chat', expectedTurnId: 'turn', input: [{ type: 'text', text: 'Which format?\nPNG\n\nWhat size?\n1024 px' }] };
  h.broker.accept('phone', { id: 40, method: 'turn/steer', params });
  assert.equal(h.upstream.at(-1).method, 'turn/steer');
  assert.deepEqual(h.upstream.at(-1).params, params);
  h.broker.receive({ id: h.upstream.at(-1).id, result: { turnId: 'turn' } });
  assert.deepEqual(h.phone.at(-1).result, { turnId: 'turn' });
  assert.equal(h.phone.at(-1).id, 40);
  h.broker.receive({ method: 'turn/completed', params: { threadId: 'chat', turn: { id: 'turn', status: 'completed' } } });
  assert.deepEqual(h.broker.events.find(e => e.event.message.method === 'item/completed').event.message.params.item, item);
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
