#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Broker } from './broker.mjs';
import { createHost, pairingToken } from './host.mjs';

const real = process.env.CODEX_REAL_CLI_PATH ?? '/usr/lib/chatgpt/resources/codex';
if (resolve(real) === fileURLToPath(import.meta.url)) throw Error('Real CLI cannot be the bridge wrapper');
const args = process.argv.slice(2);
const isServer = args.includes('app-server') && !args.some(a => ['generate-ts', 'generate-json-schema', 'daemon', 'proxy', 'help'].includes(a));
if (!isServer) {
  const child = spawn(real, args, { stdio: 'inherit' });
  child.on('error', () => { process.stderr.write('Cannot start the configured Codex executable\n'); process.exitCode = 1; });
  child.on('exit', (code, signal) => { process.exitCode = code ?? (signal ? 1 : 0); });
} else {
  const child = spawn(real, args, { stdio: ['pipe', 'pipe', 'inherit'] });
  let mode = 'unknown', host, liveProjects = [];
  const emitDesktop = message => process.stdout.write(`${JSON.stringify(message)}\n`);
  const broker = new Broker({ upstream: message => child.stdin.write(`${JSON.stringify(message)}\n`), desktop: emitDesktop,
    onResult: (method, result) => { if (method === 'project/list') {
      const map = new Map(liveProjects.map(p => [p.id, p])); for (const p of result.data ?? []) map.set(p.id, p); liveProjects = [...map.values()];
    } }, onNotification: message => { if (message.method === 'project/changed') liveProjects = []; } });
  const input = createInterface({ input: process.stdin });
  input.on('line', line => {
    let message; try { message = JSON.parse(line); } catch { child.stdin.write(`${line}\n`); return; }
    if (mode === 'unknown' && message.method === 'initialize') {
      mode = message.params?.clientInfo?.title === 'Codex Desktop' || message.params?.clientInfo?.name === 'codex_desktop' ? 'desktop' : 'auxiliary';
      if (mode === 'desktop') {
        try {
          const tokenPath = process.env.CODEX_BRIDGE_TOKEN_FILE ?? join(homedir(), '.local/share/custom-codex/pairing-token');
          const token = pairingToken(tokenPath);
          const port = Number(process.env.CODEX_BRIDGE_PORT ?? 8787);
          if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('Invalid port');
          host = createHost({ broker, token, port, backendPid: child.pid, liveProjects: () => liveProjects });
          host.listen().catch(() => process.stderr.write('Remote bridge unavailable: check port availability; desktop remains usable\n'));
        } catch { process.stderr.write('Remote bridge unavailable: check token ownership, permissions and port; desktop remains usable\n'); }
      }
    }
    if (mode === 'desktop') broker.accept('desktop', message);
    else child.stdin.write(`${line}\n`);
  });
  const output = createInterface({ input: child.stdout });
  output.on('line', line => {
    if (mode !== 'desktop') { process.stdout.write(`${line}\n`); return; }
    try { broker.receive(JSON.parse(line)); } catch { process.stderr.write('Codex emitted an invalid protocol frame\n'); }
  });
  input.on('close', () => child.stdin.end());
  child.stdin.on('error', () => {});
  child.on('error', () => { process.stderr.write('Cannot start the configured Codex executable\n'); input.close(); host?.close(); process.exitCode = 1; });
  child.on('exit', code => { input.close(); host?.close(); process.exitCode = code ?? 1; });
}
