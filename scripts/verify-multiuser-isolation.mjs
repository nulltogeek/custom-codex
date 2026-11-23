#!/usr/bin/env node
// Disposable local verification: no owner sessions, model calls or production service changes.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createTenantPool } from '../bridge/tenants.mjs';
import { startRuntime } from '../bridge/standalone.mjs';

const backendPath = process.env.CODEX_REAL_CLI_PATH ?? '/home/nulltogeek/.local/lib/custom-codex/runtime/bin/codex';
const root = mkdtempSync(join(tmpdir(), 'codex-tenant-check-'));
const owner = join(root, 'owner'); mkdirSync(owner, { mode: 0o700 });
const ownerSecret = join(owner, 'owner-proof.txt'); writeFileSync(ownerSecret, 'owner-private', { mode: 0o600 });
let proxied = 0;
const provider = createServer((req, res) => {
  assert.equal(req.headers.authorization, 'Bearer fixture-upstream-key');
  proxied++; res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"data":[{"id":"fixture-model"}]}');
});
await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
const pool = createTenantPool({ directory: join(root, 'users'), ownerHome: owner, backendPath, backendVersion: 'verification', startRuntime,
  providerSettings: { baseUrl: `http://127.0.0.1:${provider.address().port}/v1`, apiKey: 'fixture-upstream-key', model: 'gpt-5.4', effort: 'medium' } });
try {
  const a = await pool.get({ id: randomUUID(), username: 'alice', role: 'user' });
  const b = await pool.get({ id: randomUUID(), username: 'bob', role: 'user' });
  const models = await a.call('model/list', {}); assert.ok(Array.isArray(models.data));
  const command = (runtime, script) => runtime.call('command/exec', { command: ['python3', '-c', script], cwd: runtime.workspaceRoot, sandboxPolicy: { type: 'dangerFullAccess' } });
  assert.equal((await command(a, "from pathlib import Path; Path('private.txt').write_text('alice-only'); print(Path('private.txt').read_text())")).stdout.trim(), 'alice-only');
  const denied = await command(b, `from pathlib import Path\npaths=${JSON.stringify([ownerSecret, join(a.workspaceRoot, 'private.txt'), '/var/run/docker.sock'])}\nassert all(not Path(path).exists() for path in paths)\nprint('filesystem-isolated')`);
  assert.equal(denied.exitCode, 0); assert.equal(denied.stdout.trim(), 'filesystem-isolated');
  const network = await command(a, `import socket\nfor host,port in [('127.0.0.1',${provider.address().port}),('1.1.1.1',443),('172.17.0.1',8787)]:\n try:\n  sock=socket.create_connection((host,port),timeout=1)\n except OSError:\n  continue\n else:\n  sock.close(); raise AssertionError('Network egress unexpectedly succeeded')\nprint('network-isolated')`);
  assert.equal(network.exitCode, 0); assert.equal(network.stdout.trim(), 'network-isolated');
  const gateway = await command(a, "import os,urllib.request\nr=urllib.request.Request('http://127.0.0.1:3030/v1/models',headers={'Authorization':'Bearer '+os.environ['CODEX_USER_GATEWAY_TOKEN']})\nassert b'fixture-model' in urllib.request.urlopen(r,timeout=5).read()\nassert 'fixture-upstream-key' not in str(os.environ)\nprint('provider-proxied')");
  assert.equal(gateway.exitCode, 0); assert.equal(gateway.stdout.trim(), 'provider-proxied'); assert.ok(proxied >= 1);
  const thread = (await a.call('thread/start', { cwd: a.workspaceRoot, historyMode: 'legacy' })).thread;
  await assert.rejects(b.call('thread/read', { threadId: thread.id, includeTurns: false }));
  const project = await a.call('project/create', { idempotencyKey: randomUUID(), name: 'Alice private project', roots: [{ path: a.workspaceRoot }] });
  assert.ok(project.project?.id ?? project.id);
  assert.equal(JSON.stringify(await b.readProjects()).includes('Alice private project'), false);
  console.log(JSON.stringify({ initializedTwoRealCodexRuntimes: true, modelDiscovery: true, codexCommandExecution: true, filesystemIsolation: true,
    noTcpEgress: true, providerGateway: true, ownerCredentialNotInTenantEnvironment: true, threadIdIsolation: true, projectIsolation: true }));
} finally { await pool.close(); provider.close(); rmSync(root, { recursive: true, force: true }); }
