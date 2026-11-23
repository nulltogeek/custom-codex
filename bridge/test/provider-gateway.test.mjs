import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createProviderGateway, selectedProvider } from '../provider-gateway.mjs';
import { createTenantPool } from '../tenants.mjs';
import { protocolFrames } from '../standalone.mjs';
import { once } from 'node:events';

test('selected provider imports only model URL/settings and keeps server credentials out of generated configuration', () => {
  const root = mkdtempSync(join(tmpdir(), 'codex-provider-'));
  writeFileSync(join(root, 'config.toml'), 'model="fixture-model"\nmodel_provider="company"\nmodel_reasoning_effort="high"\n[model_providers.company]\nbase_url="https://provider.example/v1"\nenv_key="COMPANY_KEY"\n[mcp_servers.private]\ncommand="never-copy-this"\n');
  const settings = selectedProvider(root, { COMPANY_KEY: 'fixture-key' });
  assert.deepEqual(settings, { model: 'fixture-model', effort: 'high', baseUrl: 'https://provider.example/v1', apiKey: 'fixture-key' });
  assert.throws(() => selectedProvider(root, {}), /API key/);
});

test('gateway requires per-runtime credentials, limits paths/concurrency, streams replies and revokes access', async () => {
  const seen = [];
  const upstream = createServer((req, res) => {
    seen.push(req.headers);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write('data: fixture\n\n'); res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const gateway = await createProviderGateway({ settings: { baseUrl: `http://127.0.0.1:${upstream.address().port}/v1`, apiKey: 'upstream-only-key', model: 'fixture', effort: 'medium' } });
  const base = `http://127.0.0.1:${gateway.port}`, token = gateway.token('alice'), other = gateway.token('bob');
  try {
    assert.equal((await fetch(base + '/v1/models')).status, 401);
    assert.equal((await fetch(base + '/v1/models', { headers: { Authorization: `Bearer ${token}`, Origin: 'https://browser.invalid' } })).status, 401);
    assert.equal((await fetch(base + '/not-provider', { headers: { Authorization: `Bearer ${token}` } })).status, 404);
    const response = await fetch(base + '/v1/responses', { method: 'POST', headers: { Authorization: `Bearer ${token}`, Cookie: 'do-not-forward', 'Content-Type': 'application/json' }, body: '{}' });
    assert.equal(response.status, 200); assert.equal(await response.text(), 'data: fixture\n\ndata: [DONE]\n\n');
    assert.equal(seen[0].authorization, 'Bearer upstream-only-key'); assert.equal(seen[0].cookie, undefined);
    gateway.revoke('alice');
    assert.equal((await fetch(base + '/v1/models', { headers: { Authorization: `Bearer ${token}` } })).status, 401);
    assert.equal((await fetch(base + '/v1/models', { headers: { Authorization: `Bearer ${other}` } })).status, 200);
  } finally { gateway.close(); upstream.close(); }
});

test('missing Docker never falls back to unsandboxed user execution', async () => {
  const root = mkdtempSync(join(tmpdir(), 'codex-fail-closed-'));
  let started = 0;
  const pool = createTenantPool({ directory: root, dockerPath: '/no-such-docker', providerSettings: { apiKey: 'test', baseUrl: 'https://provider.invalid/v1', model: 'fixture', effort: 'medium' }, startRuntime: () => { started++; } });
  try { await assert.rejects(pool.get({ id: '11111111-1111-1111-1111-111111111111' }), /container image/); assert.equal(started, 0); }
  finally { await pool.close(); }
});


test('backend protocol frames bound unterminated output across chunks while allowing multiple small frames', async () => {
  const frames = protocolFrames(32), chunks = [];
  frames.on('data', chunk => chunks.push(chunk.toString()));
  frames.write('small\nsmall\n'); frames.write('x'.repeat(20));
  const error = once(frames, 'error'); frames.write('x'.repeat(13));
  assert.match((await error)[0].message, /size limit/);
  assert.equal(chunks.join(''), 'small\nsmall\n' + 'x'.repeat(20));
});
