import { createServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { readFileSync, chmodSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes, createHash } from 'node:crypto';

const digest = text => createHash('sha256').update(text).digest('hex');
function string(value) {
  if (value?.startsWith('"')) { try { return JSON.parse(value); } catch {} }
  if (value?.startsWith("'") && value.endsWith("'")) return value.slice(1, -1);
  return null;
}

/** Read only the selected provider, never plugins, MCP commands, project trust or history. */
export function selectedProvider(codexHome, environment = process.env) {
  const tables = new Map(); let table = '';
  for (const raw of readFileSync(join(codexHome, 'config.toml'), 'utf8').split('\n')) {
    const line = raw.trim();
    if (/^\[.*\]$/.test(line)) { table = line.slice(1, -1); continue; }
    const match = /^([A-Za-z0-9_-]+)\s*=\s*(.+?)\s*$/.exec(line);
    if (match) { if (!tables.has(table)) tables.set(table, {}); tables.get(table)[match[1]] = string(match[2]); }
  }
  const root = tables.get('') ?? {}, providerId = root.model_provider ?? 'openai';
  const provider = tables.get('model_providers.' + providerId) ?? tables.get('model_providers.' + JSON.stringify(providerId)) ?? {};
  let apiKey = provider.env_key ? environment[provider.env_key] : environment.OPENAI_API_KEY;
  if (!apiKey && providerId === 'openai') {
    try { apiKey = JSON.parse(readFileSync(join(codexHome, 'auth.json'), 'utf8')).OPENAI_API_KEY; } catch {}
  }
  if (!apiKey || typeof apiKey !== 'string') throw Error('Configure the selected model provider API key in the server environment before starting user workspaces');
  const baseUrl = new URL(provider.base_url ?? 'https://api.openai.com/v1');
  if (baseUrl.protocol !== 'https:' || baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash)
    throw Error('The shared model provider must have an HTTPS base URL without embedded credentials');
  return { baseUrl: baseUrl.toString().replace(/\/$/, ''), apiKey,
    model: root.model ?? 'gpt-5.4', effort: root.model_reasoning_effort ?? 'medium' };
}

/** User containers see only revocable gateway tokens, never the upstream provider credential. */
export async function createProviderGateway({ settings, bindAddress = '127.0.0.1', socketPath }) {
  const tokens = new Map();
  const server = createServer((req, res) => {
    const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
    const entry = token && tokens.get(digest(token));
    if (!entry || req.headers.origin) { res.writeHead(401); res.end(); req.resume(); return; }
    if (entry.requests.size >= 2 || [...tokens.values()].reduce((count, token) => count + token.requests.size, 0) >= 16) {
      res.writeHead(429); res.end(); req.resume(); return;
    }
    if (!((req.method === 'POST' && ['/v1/responses', '/v1/chat/completions'].includes(req.url)) ||
        (req.method === 'GET' && req.url === '/v1/models'))) { res.writeHead(404); res.end(); req.resume(); return; }
    if (Number(req.headers['content-length']) > 24 * 1024 * 1024) { res.writeHead(413); res.end(); req.resume(); return; }
    const url = new URL(settings.baseUrl.replace(/\/$/, '') + req.url.slice(3));
    const request = url.protocol === 'https:' ? httpsRequest : httpRequest;
    const upstream = request(url, { method: req.method, headers: { Authorization: `Bearer ${settings.apiKey}`,
      'Content-Type': 'application/json', Accept: req.headers.accept ?? 'application/json' }, timeout: 300000 }, response => {
      res.writeHead(response.statusCode, { 'Content-Type': response.headers['content-type'] ?? 'application/json',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      response.pipe(res); response.on('error', () => res.destroy());
    });
    entry.requests.add(upstream);
    upstream.on('close', () => entry.requests.delete(upstream));
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    upstream.on('timeout', () => upstream.destroy());
    let size = 0;
    req.on('data', chunk => { size += chunk.length; if (size > 24 * 1024 * 1024) { upstream.destroy(); req.destroy(); } });
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  });
  server.maxConnections = 64;
  server.headersTimeout = 10000;
  server.requestTimeout = 60000;
  await new Promise((resolve, reject) => { server.once('error', reject); if (socketPath) server.listen(socketPath, resolve); else server.listen(0, bindAddress, resolve); });
  if (socketPath) chmodSync(socketPath, 0o600);
  return { port: socketPath ? null : server.address().port, socketPath, model: settings.model, effort: settings.effort,
    token(id) {
      const token = randomBytes(32).toString('base64url'); tokens.set(digest(token), { id, requests: new Set() }); return token;
    },
    revoke(id) { for (const [key, entry] of tokens) if (entry.id === id) { tokens.delete(key); for (const request of entry.requests) request.destroy(); } },
    close() { for (const entry of tokens.values()) for (const request of entry.requests) request.destroy(); tokens.clear(); server.close(); },
  };
}
