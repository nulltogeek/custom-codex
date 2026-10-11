import { createServer } from 'node:http';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir, hostname } from 'node:os';
import { createRequire } from 'node:module';
import { WebSocketServer } from 'ws';
import { receiveUpload } from './uploads.mjs';
import { serveAppUpdate, defaultUpdateDirectory } from './app-updates.mjs';
const require = createRequire(import.meta.url);

export function pairingToken(path) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  try { writeFileSync(path, `${randomBytes(32).toString('base64url')}\n`, { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const stat = statSync(path);
  if ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()) throw Error('Pairing token must be owned by this user and have mode 0600');
  const token = readFileSync(path, 'utf8').trim();
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw Error('Pairing token must contain 32–256 base64url characters');
  return token;
}

export function readProjects(codexHome, liveProjects = []) {
  let state = {};
  try { state = JSON.parse(readFileSync(join(codexHome, '.codex-global-state.json'), 'utf8')); }
  catch (e) { if (e.code !== 'ENOENT') return { projects: [], assignments: {}, error: 'Desktop project metadata is temporarily unavailable' }; }
  const registry = state['local-projects'] ?? {};
  const projects = new Map(); const assignments = {};
  const migrations = Object.values(state['app-server-project-id-by-legacy-project-id-by-host'] ?? {})
    .reduce((all, map) => ({ ...all, ...map }), {});
  const canonical = id => migrations[id] ?? id;
  for (const p of Array.isArray(registry) ? registry : Object.values(registry)) {
    if (!p?.id || !Array.isArray(p.rootPaths)) continue;
    projects.set(canonical(p.id), { id: canonical(p.id), name: p.name || p.rootPaths[0] || 'Project',
      rootPaths: p.rootPaths, legacyId: p.id });
  }
  // SQLite is read-only; query only project structure and thread membership, never credentials.
  try {
    const { DatabaseSync } = require('node:sqlite');
    const db = new DatabaseSync(join(codexHome, 'state_5.sqlite'), { readOnly: true });
    try {
      for (const p of db.prepare('SELECT id,name,position FROM projects ORDER BY position').all()) {
        const roots = db.prepare('SELECT path FROM project_roots WHERE project_id=? ORDER BY position').all(p.id).map(r => r.path);
        const previous = projects.get(p.id);
        projects.set(p.id, { ...previous, id: p.id, name: p.name, rootPaths: roots, position: p.position });
      }
      for (const t of db.prepare('SELECT id,project_id FROM threads WHERE project_id IS NOT NULL').all()) assignments[t.id] = t.project_id;
    } finally { db.close(); }
  } catch { /* Version-specific optional fallback; legacy project registry remains usable. */ }
  for (const [threadId, value] of Object.entries(state['thread-project-assignments'] ?? {})) {
    const id = typeof value === 'string' ? value : value?.projectId;
    if (id && !assignments[threadId]) assignments[threadId] = canonical(id);
  }
  for (const p of liveProjects) {
    if (!p?.id) continue;
    const roots = p.rootPaths ?? p.roots?.map(r => typeof r === 'string' ? r : r.path) ?? projects.get(p.id)?.rootPaths ?? [];
    projects.set(p.id, { ...projects.get(p.id), id: p.id, name: p.name || 'Project', rootPaths: roots });
  }
  const order = (state['project-order'] ?? []).map(canonical);
  const sorted = [...projects.values()].sort((a, b) => {
    const ai = order.indexOf(a.id), bi = order.indexOf(b.id);
    return (ai < 0 ? 10000 + (a.position ?? 0) : ai) - (bi < 0 ? 10000 + (b.position ?? 0) : bi);
  });
  return { projects: sorted, assignments };
}

export function readHistory(codexHome, archived = false, cursor = null, searchTerm = '') {
  let offset = 0;
  if (cursor) {
    try { offset = Number(Buffer.from(cursor, 'base64url').toString()); } catch { throw Error('Invalid history cursor'); }
    if (!Number.isSafeInteger(offset) || offset < 0) throw Error('Invalid history cursor');
  }
  const { DatabaseSync } = require('node:sqlite');
  const db = new DatabaseSync(join(codexHome, 'state_5.sqlite'), { readOnly: true });
  try {
    // This intentionally includes persisted child-agent records hidden from root thread/list.
    const search = `%${searchTerm.slice(0, 300).replace(/[\\%_]/g, c => `\\${c}`)}%`;
    const rows = db.prepare(`SELECT id,name,title,preview,cwd,source,updated_at,archived,is_pinned,project_id
      FROM threads WHERE archived=? AND (coalesce(name,'') LIKE ? ESCAPE '\\' OR coalesce(title,'') LIKE ? ESCAPE '\\' OR coalesce(preview,'') LIKE ? ESCAPE '\\')
      ORDER BY updated_at DESC,id LIMIT 51 OFFSET ?`).all(archived ? 1 : 0, search, search, search, offset);
    const data = rows.slice(0, 50).map(r => {
      let source = r.source; try { source = JSON.parse(r.source); } catch { /* Interactive source string. */ }
      return { id: r.id, name: r.name || r.title || '', preview: r.preview || '', cwd: r.cwd,
        source, updatedAt: r.updated_at, archived: Boolean(r.archived), isPinned: Boolean(r.is_pinned),
        projectId: r.project_id, status: { type: 'notLoaded' } };
    });
    return { data, nextCursor: rows.length > 50 ? Buffer.from(String(offset + 50)).toString('base64url') : null };
  } finally { db.close(); }
}

export function createHost({ broker, token, authentication, port = 8787, bindAddress = '127.0.0.1', codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex'),
  backendPid, backendVersion = '0.159.2', runtimeMode = 'desktop', liveProjects = () => [],
  uploadDirectory = process.env.CODEX_UPLOAD_DIRECTORY ?? join(homedir(), '.local/share/custom-codex/uploads'),
  updateDirectory = process.env.CODEX_APP_UPDATE_DIRECTORY ?? defaultUpdateDirectory }) {
  if (!authentication && !token) throw Error('Host authentication is required');
  const digest = v => createHash('sha256').update(v).digest(); const expected = digest(`Bearer ${token}`);
  const authorized = req => authentication ? Boolean(authentication.authenticate(req)) :
    !req.headers.origin && timingSafeEqual(expected, digest(req.headers.authorization ?? ''));
  const server = createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json'); res.setHeader('Cache-Control', 'no-store');
    const reply = (status, body) => { res.writeHead(status); res.end(JSON.stringify(body)); };
    if (req.url === '/healthz' && req.method === 'GET' && !req.headers.origin) return reply(200, { ok: true });
    if (req.url === '/v1/login' && authentication) {
      if (req.headers.origin) return reply(401, { error: 'Authentication required' });
      if (req.method !== 'POST') return reply(405, { error: 'Method not allowed' });
      if ((req.headers['content-type'] ?? '').split(';')[0].trim() !== 'application/json') return reply(415, { error: 'Use application/json' });
      let size = 0, chunks = [];
      req.setTimeout(15000, () => req.destroy());
      req.on('data', chunk => {
        size += chunk.length;
        if (size > 4096) { if (!res.writableEnded) reply(413, { error: 'Login request too large' }); chunks = []; }
        else if (!res.writableEnded) chunks.push(chunk);
      });
      req.on('end', async () => {
        if (res.writableEnded) return;
        try {
          let input;
          try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
          catch { return reply(400, { error: 'Invalid login request' }); }
          reply(200, await authentication.login(input, req.socket.remoteAddress));
        } catch (error) {
          const status = error.status ?? 503;
          if (status === 429) res.setHeader('Retry-After', '600');
          reply(status, { error: error.status ? error.message : 'Login temporarily unavailable' });
        }
      });
      req.on('error', () => {});
      return;
    }
    if (!authorized(req)) return reply(401, { error: 'Authentication required' });
    if (req.url === '/v1/attachments' && req.method === 'POST') return receiveUpload(req, res, uploadDirectory, reply);
    if (req.url === '/v1/logout' && authentication && req.method === 'POST') {
      authentication.logout(req); return reply(200, { ok: true });
    }
    if (req.method !== 'GET') return reply(405, { error: 'Method not allowed' });
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/v1/app-update' || url.pathname.startsWith('/v1/app-update/')) return serveAppUpdate(url, res, updateDirectory, reply);
    if (url.pathname === '/v1/history') {
      try { return reply(200, readHistory(codexHome, url.searchParams.get('archived') === 'true', url.searchParams.get('cursor'), url.searchParams.get('searchTerm') ?? '')); }
      catch { return reply(503, { error: 'Local history catalogue unavailable for this backend version' }); }
    }
    if (req.url === '/v1/host') return reply(200, { name: hostname(), backendPid, backendVersion,
      bridgeVersion: '1', runtimeMode, codexHome, configFile: join(codexHome, 'config.toml'), ready: broker.ready, epoch: broker.epoch,
      capabilities: { attachments: true, appUpdates: true, maxUploadBytes: 20 * 1024 * 1024, mobileModes: ['codex', 'chat'], accessModes: ['host', 'ask', 'read-only', 'full'] },
      initialization: { resultReceived: Boolean(broker.initResult), notificationReceived: broker.desktopNotificationSeen, errorCode: broker.initializationError } });
    if (req.url === '/v1/projects') return reply(200, readProjects(codexHome, liveProjects()));
    if (req.url === '/v1/snapshot') return reply(200, broker.snapshot());
    reply(404, { error: 'Not found' });
  });
  const ws = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 * 1024 }); let serial = 0;
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/v1/rpc' || !authorized(req)) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return; }
    ws.handleUpgrade(req, socket, head, peer => ws.emit('connection', peer, req));
  });
  ws.on('connection', (peer, req) => {
    const id = `phone:${++serial}`;
    const send = message => {
      if (peer.bufferedAmount > 4 * 1024 * 1024) { peer.close(1013, 'Slow client: reconnect and refresh'); return; }
      if (peer.readyState === peer.OPEN) peer.send(JSON.stringify(message));
    };
    broker.addClient(id, send); send({ type: 'hello', ...broker.snapshot() });
    const untrack = authentication?.track(req, () => peer.close(1008, 'Please sign in again'));
    peer.on('message', data => {
      if (!authorized(req)) { peer.close(1008, 'Please sign in again'); return; }
      try { broker.accept(id, JSON.parse(data.toString())); }
      catch { send({ error: { code: -32700, message: 'Invalid message' } }); }
    });
    peer.on('close', () => { untrack?.(); broker.removeClient(id); }); peer.on('error', () => {});
  });
  const heartbeat = setInterval(() => { authentication?.expire(); for (const p of ws.clients) p.ping(); }, 25000); heartbeat.unref();
  server.on('close', () => clearInterval(heartbeat));
  return { server, ws, listen: () => new Promise((resolve, reject) => {
    server.once('error', reject); server.listen(port, bindAddress, () => { server.removeListener('error', reject); resolve(server.address().port); });
  }), close: () => { for (const p of ws.clients) p.close(1012, 'Backend stopped'); authentication?.close(); ws.close(); server.close(); } };
}
