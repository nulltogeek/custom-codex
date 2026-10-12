import { randomUUID } from 'node:crypto';
import { posix } from 'node:path';
import { mobileOptions } from './mobile-options.mjs';

export const HUMAN_REQUESTS = new Set([
  'item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
  'item/permissions/requestApproval', 'item/tool/requestUserInput',
  'mcpServer/elicitation/request', 'applyPatchApproval', 'execCommandApproval',
]);
const PHONE_METHODS = new Set([
  'thread/list', 'thread/read', 'thread/loaded/list', 'thread/turns/list',
  'thread/items/list', 'thread/start', 'thread/resume', 'thread/fork',
  'thread/name/set', 'thread/archive', 'thread/unarchive', 'thread/delete', 'thread/metadata/update',
  'project/update', 'project/create',
  'turn/start', 'turn/steer', 'turn/interrupt', 'model/list',
  'account/read', 'account/rateLimits/read', 'review/start',
]);
const FORBIDDEN_OVERRIDES = ['approvalPolicy', 'approvalsReviewer', 'sandbox', 'sandboxPolicy',
  'permissions', 'config', 'baseInstructions', 'developerInstructions', 'collaborationMode'];
const key = id => `${typeof id}:${JSON.stringify(id)}`;

/** One host-initialized upstream connection, with independently routed client IDs.
 * The historical 'desktop' role is also used by the standalone local initializer. */
export class Broker {
  constructor({ upstream, desktop, maxEvents = 2048, maxBytes = 8 * 1024 * 1024, onResult = () => {}, onNotification = () => {} }) {
    this.upstream = upstream; this.desktop = desktop; this.onResult = onResult;
    this.onNotification = onNotification;
    this.epoch = randomUUID(); this.sequence = 0; this.serial = 0;
    this.ready = false; this.initResult = null;
    this.initializationError = null; this.desktopNotificationSeen = false;
    this.requests = new Map(); this.serverRequests = new Map(); this.clients = new Map();
    this.threadModels = new Map();
    this.events = []; this.bytes = 0; this.maxEvents = maxEvents; this.maxBytes = maxBytes;
  }
  addClient(id, send) { this.clients.set(id, { send, subscribed: false }); }
  removeClient(id) {
    this.clients.delete(id);
    // A disconnected caller's mutation remains upstream. It is never retried.
    for (const request of this.requests.values()) if (request.client === id) request.client = null;
  }
  snapshot() {
    return { epoch: this.epoch, sequence: this.sequence, ready: this.ready,
      pendingRequests: [...this.serverRequests.values()].filter(r => HUMAN_REQUESTS.has(r.method))
        .map(({ requestId, method, params }) => ({ requestId, method, params })) };
  }
  sendTo(client, message) {
    if (client === 'desktop') this.desktop(message);
    else this.clients.get(client)?.send(message);
  }
  error(client, id, code, message) { this.sendTo(client, { id, error: { code, message } }); }
  event(message) {
    const event = { type: 'event', epoch: this.epoch, sequence: ++this.sequence, message };
    const size = Buffer.byteLength(JSON.stringify(event));
    this.events.push({ event, size }); this.bytes += size;
    while (this.events.length > this.maxEvents || this.bytes > this.maxBytes) {
      this.bytes -= this.events.shift().size;
    }
    for (const c of this.clients.values()) if (c.subscribed) c.send(event);
  }
  accept(client, message) {
    if (!message || typeof message !== 'object' || Array.isArray(message)) return;
    if (typeof message.method === 'string') {
      const { method, params = {}, id } = message;
      let forwardedParams = params;
      if (client !== 'desktop') {
        if (method === 'bridge/subscribe') {
          const c = this.clients.get(client); if (!c) return;
          const last = Number.isSafeInteger(params.sequence) ? params.sequence : 0;
          const earliest = this.events[0]?.event.sequence ?? this.sequence + 1;
          const replay = params.epoch === this.epoch && last >= earliest - 1 && last <= this.sequence;
          this.sendTo(client, { id, result: { ...this.snapshot(), replayGap: !replay } });
          if (replay) for (const { event } of this.events) if (event.sequence > last) c.send(event);
          c.subscribed = true; return;
        }
        if (method === 'bridge/respond') {
          const pending = [...this.serverRequests.values()].find(r => r.requestId === params.requestId);
          if (!pending || !HUMAN_REQUESTS.has(pending.method)) {
            this.error(client, id, -32004, 'Request was resolved or belongs to a previous connection'); return;
          }
          if (!validHumanAnswer(pending, params.result)) {
            this.error(client, id, -32602, 'Invalid answer for this request'); return;
          }
          this.resolve(pending, { id: pending.id, result: params.result });
          this.sendTo(client, { id, result: {} }); return;
        }
        if (method === 'initialize') {
          if (!this.ready) { this.error(client, id, -32002, 'Codex backend is not initialized'); return; }
          this.sendTo(client, { id, result: this.initResult }); return;
        }
        if (method === 'initialized') return;
        if (!PHONE_METHODS.has(method)) { this.error(client, id, -32601, 'Method is outside the mobile client interface'); return; }
        if (FORBIDDEN_OVERRIDES.some(field => Object.hasOwn(params, field)) ||
            (method === 'account/read' && params.refreshToken === true) ||
            (method === 'thread/metadata/update' && Object.keys(params).some(k => !['threadId', 'isPinned', 'projectId'].includes(k)))) {
          this.error(client, id, -32602, 'Host policy and configuration cannot be overridden by the phone'); return;
        }
        if (method === 'thread/delete' && (typeof params.threadId !== 'string' || !params.threadId || Object.keys(params).some(k => k !== 'threadId'))) {
          this.error(client, id, -32602, 'Delete requires only a threadId'); return;
        }
        if (['thread/name/set', 'project/update'].includes(method)) {
          const identity = method === 'project/update' ? 'projectId' : 'threadId';
          if (typeof params[identity] !== 'string' || !params[identity].trim() ||
              typeof params.name !== 'string' || !params.name.trim() || params.name.trim().length > 200 ||
              /[\u0000-\u001f\u007f]/u.test(params.name) || Object.keys(params).some(k => ![identity, 'name'].includes(k))) {
            this.error(client, id, -32602, 'Rename requires an ID and a single-line name of 1–200 characters'); return;
          }
          forwardedParams = { [identity]: params[identity], name: params.name.trim() };
        }
        if (method === 'project/create') {
          if (Object.keys(params).some(k => !['idempotencyKey', 'name', 'roots'].includes(k)) ||
              typeof params.idempotencyKey !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(params.idempotencyKey) ||
              typeof params.name !== 'string' || !params.name.trim() || params.name.trim().length > 200 || /[\u0000-\u001f\u007f]/u.test(params.name) ||
              !Array.isArray(params.roots) || params.roots.length > 8 || params.roots.some(r =>
                !r || typeof r !== 'object' || Array.isArray(r) || Object.keys(r).some(k => k !== 'path') ||
                typeof r.path !== 'string' || !r.path.startsWith('/') || r.path.length > 4096 || /[\u0000-\u001f\u007f]/u.test(r.path))) {
            this.error(client, id, -32602, 'Create a project with a name, request key and at most 8 absolute folder paths'); return;
          }
          forwardedParams = { idempotencyKey: params.idempotencyKey, name: params.name.trim(),
            roots: [...new Set(params.roots.map(r => posix.normalize(r.path)))].map(path => ({ path })) };
        }
        if (!this.ready) { this.error(client, id, -32002, 'Codex backend is not initialized'); return; }
        try { forwardedParams = mobileOptions(method, forwardedParams, this.threadModels.get(params.threadId)); }
        catch (error) { this.error(client, id, -32602, error.message); return; }
      }
      if (id !== undefined) {
        const upstreamId = `android-bridge:${++this.serial}`;
        this.requests.set(key(upstreamId), { client, id, method, threadId: params.threadId });
        this.upstream({ ...message, params: forwardedParams, id: upstreamId });
      } else {
        if (client === 'desktop' && method === 'initialized') this.ready = this.initResult !== null;
        if (client === 'desktop' && method === 'initialized') this.desktopNotificationSeen = true;
        this.upstream({ ...message, params: forwardedParams });
      }
    } else if (message.id !== undefined && client === 'desktop') {
      const pending = this.serverRequests.get(key(message.id));
      if (pending) this.resolve(pending, message);
      // Drop late answers: they must never resolve a subsequent request accidentally.
    }
  }
  resolve(pending, answer) {
    this.serverRequests.delete(key(pending.id));
    this.upstream(answer);
    if (HUMAN_REQUESTS.has(pending.method)) {
      const message = { method: 'serverRequest/resolved', params: {
        threadId: pending.params?.threadId ?? null, requestId: pending.id } };
      this.desktop(message); this.event({ method: 'bridge/requestResolved', params: { requestId: pending.requestId } });
    }
  }
  receive(message) {
    if (typeof message?.method === 'string') {
      if (message.id !== undefined) {
        const pending = { ...message, requestId: `${this.epoch}:${++this.serial}` };
        this.serverRequests.set(key(message.id), pending); this.desktop(message);
        if (HUMAN_REQUESTS.has(message.method)) this.event({ method: 'bridge/requestPending', params: {
          requestId: pending.requestId, method: pending.method, params: pending.params } });
      } else {
        this.onNotification(message);
        if (message.method === 'serverRequest/resolved') {
          const r = this.serverRequests.get(key(message.params?.requestId));
          if (r) { this.serverRequests.delete(key(r.id)); this.event({ method: 'bridge/requestResolved', params: { requestId: r.requestId } }); }
        }
        // Completion/interrupt invalidates all unanswered input belonging to that turn.
        if (message.method === 'turn/completed') {
          for (const r of [...this.serverRequests.values()]) if (r.params?.threadId === message.params?.threadId &&
              (!r.params?.turnId || r.params.turnId === message.params?.turn?.id)) {
            this.serverRequests.delete(key(r.id));
            if (HUMAN_REQUESTS.has(r.method)) this.event({ method: 'bridge/requestResolved', params: { requestId: r.requestId } });
          }
        }
        this.desktop(message); this.event(message);
      }
    } else if (message?.id !== undefined) {
      const request = this.requests.get(key(message.id)); if (!request) return;
      this.requests.delete(key(message.id));
      if (request.method === 'initialize' && request.client === 'desktop') {
        if (message.result) { this.initResult = message.result; this.ready = true; }
        else this.initializationError = message.error?.code ?? -1;
      }
      if (message.result) this.onResult(request.method, message.result);
      if (message.result?.thread?.id && (message.result.model || message.result.thread.model))
        this.threadModels.set(message.result.thread.id, message.result.model || message.result.thread.model);
      if (request.client !== null) this.sendTo(request.client, { ...message, id: request.id,
        ...(request.client !== 'desktop' ? { bridgeEpoch: this.epoch, bridgeSequence: this.sequence } : {}) });
    }
  }
}

function validHumanAnswer(request, result) {
  if (!result || typeof result !== 'object' || Array.isArray(result)) return false;
  if (request.method === 'item/commandExecution/requestApproval' || request.method === 'item/fileChange/requestApproval')
    return ['accept', 'acceptForSession', 'decline', 'cancel'].includes(result.decision) &&
      (!Array.isArray(request.params?.availableDecisions) || request.params.availableDecisions.length === 0 || request.params.availableDecisions.includes(result.decision));
  if (request.method === 'applyPatchApproval' || request.method === 'execCommandApproval')
    return ['approved', 'approved_for_session', 'denied', 'abort'].includes(result.decision);
  if (request.method === 'item/tool/requestUserInput') {
    const questions = request.params?.questions ?? [];
    return questions.every(q => Array.isArray(result.answers?.[q.id]?.answers) &&
      result.answers[q.id].answers.every(a => typeof a === 'string'));
  }
  if (request.method === 'mcpServer/elicitation/request') return ['accept', 'decline', 'cancel'].includes(result.action) &&
    (result.action !== 'accept' || !request.params?.requestedSchema || matchesSchema(result.content, request.params.requestedSchema));
  if (request.method === 'item/permissions/requestApproval') {
    if (!['turn', 'session'].includes(result.scope) || !result.permissions || typeof result.permissions !== 'object') return false;
    // Permissions can only be granted exactly as requested, or rejected entirely.
    return Object.keys(result.permissions).length === 0 || JSON.stringify(result.permissions) === JSON.stringify(request.params?.permissions);
  }
  return false;
}

function matchesSchema(value, schema) {
  if (!schema || typeof schema !== 'object') return false;
  if (schema.enum && !schema.enum.some(v => JSON.stringify(v) === JSON.stringify(value))) return false;
  if (schema.type === 'object' || schema.properties) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    if ((schema.required ?? []).some(k => !Object.hasOwn(value, k))) return false;
    for (const [k, v] of Object.entries(value)) {
      if (schema.properties?.[k] && !matchesSchema(v, schema.properties[k])) return false;
      if (!schema.properties?.[k] && schema.additionalProperties === false) return false;
    }
  }
  if (schema.type === 'string' && typeof value !== 'string') return false;
  if (schema.type === 'boolean' && typeof value !== 'boolean') return false;
  if (schema.type === 'number' && (typeof value !== 'number' || !Number.isFinite(value))) return false;
  if (schema.type === 'integer' && !Number.isSafeInteger(value)) return false;
  if (schema.type === 'array' && (!Array.isArray(value) || (schema.items && !value.every(v => matchesSchema(v, schema.items))))) return false;
  if (typeof value === 'number' && ((schema.minimum !== undefined && value < schema.minimum) || (schema.maximum !== undefined && value > schema.maximum))) return false;
  if (typeof value === 'string') {
    if ((schema.minLength !== undefined && value.length < schema.minLength) || (schema.maxLength !== undefined && value.length > schema.maxLength)) return false;
    if (schema.pattern) { try { if (!new RegExp(schema.pattern).test(value)) return false; } catch { return false; } }
  }
  return true;
}
