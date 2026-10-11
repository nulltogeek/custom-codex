import test from 'node:test';
import assert from 'node:assert/strict';
import { mobileOptions } from '../mobile-options.mjs';
import { Broker } from '../broker.mjs';

test('named access presets expand to supported app-server policies', () => {
  assert.deepEqual(mobileOptions('thread/start', { mobileMode: 'codex', accessMode: 'ask' }),
    { approvalPolicy: 'on-request', sandbox: 'workspace-write', historyMode: 'legacy' });
  assert.deepEqual(mobileOptions('turn/start', { mobileMode: 'codex', accessMode: 'full' }),
    { approvalPolicy: 'never', sandboxPolicy: { type: 'dangerFullAccess' } });
  assert.deepEqual(mobileOptions('thread/resume', { mobileMode: 'codex', accessMode: 'read-only', threadId: 't' }),
    { threadId: 't', approvalPolicy: 'on-request', sandbox: 'read-only' });
  assert.throws(() => mobileOptions('turn/steer', { mobileMode: 'chat' }), /next turn/);
  assert.throws(() => mobileOptions('thread/start', { accessMode: 'anything' }), /Invalid/);
});

test('conversation mode stays read-only and its fixed instructions clear on coding turns', () => {
  const chat = mobileOptions('turn/start', { threadId: 't', mobileMode: 'chat', accessMode: 'full' }, 'model-one');
  assert.equal(chat.approvalPolicy, 'never'); assert.equal(chat.sandboxPolicy.type, 'readOnly');
  assert.equal(chat.collaborationMode.settings.model, 'model-one');
  assert.match(chat.collaborationMode.settings.developer_instructions, /Do not execute commands/);
  const coding = mobileOptions('turn/start', { threadId: 't', mobileMode: 'codex', accessMode: 'ask', model: 'model-two' }, 'model-one');
  assert.equal(coding.collaborationMode.settings.model, 'model-two');
  assert.equal(coding.collaborationMode.settings.developer_instructions, null);
  assert.throws(() => mobileOptions('turn/start', { mobileMode: 'chat' }), /model details/);
});

test('broker expands owner-selected controls while rejecting raw configuration bypasses', () => {
  const upstream = [], phone = [];
  const broker = new Broker({ upstream: message => upstream.push(message), desktop() {} });
  broker.ready = true; broker.initResult = {}; broker.addClient('phone', message => phone.push(message));
  broker.accept('phone', { id: 1, method: 'thread/start', params: { mobileMode: 'codex', accessMode: 'ask' } });
  broker.receive({ id: upstream.at(-1).id, result: { model: 'fixture-model', thread: { id: 't' } } });
  broker.accept('phone', { id: 2, method: 'turn/start', params: { threadId: 't', mobileMode: 'chat', accessMode: 'full', input: [] } });
  assert.equal(upstream.at(-1).params.collaborationMode.settings.model, 'fixture-model');
  assert.equal(upstream.at(-1).params.sandboxPolicy.type, 'readOnly');
  const before = upstream.length;
  broker.accept('phone', { id: 3, method: 'turn/start', params: { mobileMode: 'codex', accessMode: 'full', developerInstructions: 'bypass' } });
  broker.accept('phone', { id: 4, method: 'turn/start', params: { collaborationMode: { settings: { developer_instructions: 'bypass' } } } });
  assert.equal(upstream.length, before); assert.equal(phone.at(-1).error.code, -32602);
});
