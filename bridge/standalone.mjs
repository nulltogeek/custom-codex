#!/usr/bin/env node
import { execFile, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { homedir, userInfo } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { isIP } from 'node:net';
import { Broker, HUMAN_REQUESTS } from './broker.mjs';
import { createHost } from './host.mjs';
import { loginAccount, createAuthentication } from './auth.mjs';

const execute = promisify(execFile);

/** Own a public app-server connection without an Electron/desktop connection. */
export async function startStandalone({
  backendPath = process.env.CODEX_REAL_CLI_PATH ?? '/usr/lib/chatgpt/resources/codex',
  backendArgs = ['app-server', '--listen', 'stdio://'],
  codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex'),
  accountFile = process.env.CODEX_LOGIN_ACCOUNT_FILE ?? join(homedir(), '.local/share/custom-codex/account.json'),
  initialLoginFile = process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'),
  username = process.env.CODEX_LOGIN_USERNAME ?? userInfo().username,
  port = Number(process.env.CODEX_BRIDGE_PORT ?? 8787),
  bindAddress = process.env.CODEX_BRIDGE_BIND ?? '127.0.0.1',
  cwd = homedir(), backendVersion, startupTimeoutMs = 30000,
} = {}) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('Invalid bridge port');
  if (!isIP(bindAddress)) throw Error('Bridge bind address must be a literal interface IP address');
  codexHome = resolve(codexHome);
  const environment = { ...process.env, CODEX_HOME: codexHome };
  if (!backendVersion) {
    try {
      const { stdout } = await execute(backendPath, ['--version'], { env: environment, timeout: 5000, maxBuffer: 2048 });
      backendVersion = stdout.match(/\b\d+\.\d+\.\d+[a-zA-Z0-9.-]*/)?.[0] ?? 'unknown';
    } catch { throw Error('Configured Codex binary is unavailable; check CODEX_REAL_CLI_PATH'); }
  }
  const authentication = createAuthentication(loginAccount(accountFile, initialLoginFile, username));
  const backend = spawn(backendPath, backendArgs, { cwd, env: environment, stdio: ['pipe', 'pipe', 'inherit'] });
  const exited = new Promise(resolveExit => backend.once('close', (code, signal) => resolveExit({ code, signal })));
  let initialized, initializationFailed, stopping = false, closing;
  const ready = new Promise((yes, no) => { initialized = yes; initializationFailed = no; });
  // Attach rejection handling before listen() awaits, including an early spawn error.
  ready.catch(() => {});
  let broker;
  const owner = message => {
    if (message.id === 'standalone:init') {
      if (message.result) initialized();
      else initializationFailed(Error(`Codex app-server initialization failed (${message.error?.code ?? -1})`));
    } else if (message.method && message.id !== undefined && !HUMAN_REQUESTS.has(message.method)) {
      // A closed desktop cannot execute its dynamic tools or produce its attestation.
      broker.accept('desktop', { id: message.id, error: { code: -32601,
        message: 'Client-executed desktop tools are unavailable in standalone mode; use a server-side tool or desktop mode' } });
    }
  };
  broker = new Broker({ upstream: message => {
    if (!backend.stdin.destroyed) backend.stdin.write(`${JSON.stringify(message)}\n`);
  }, desktop: owner });
  const host = createHost({ broker, authentication, port, bindAddress, codexHome, backendPid: backend.pid, backendVersion, runtimeMode: 'standalone' });
  const output = createInterface({ input: backend.stdout });
  output.on('line', line => {
    try { broker.receive(JSON.parse(line)); }
    catch { initializationFailed(Error('Codex app-server emitted an invalid protocol frame')); void close(); }
  });
  backend.stdin.on('error', () => {});
  backend.on('error', () => initializationFailed(Error('Codex app-server could not start')));
  backend.on('close', () => {
    broker.ready = false;
    initializationFailed(Error('Codex app-server stopped before initialization'));
    host.close(); output.close();
  });
  function close() {
    if (closing) return closing;
    stopping = true; broker.ready = false; host.close(); backend.stdin.end();
    closing = (async () => {
      const timeout = setTimeout(() => backend.kill('SIGTERM'), 10000); timeout.unref();
      try { return await exited; } finally { clearTimeout(timeout); output.close(); }
    })();
    return closing;
  }
  const timeout = setTimeout(() => initializationFailed(Error('Codex app-server initialization timed out')), startupTimeoutMs);
  try {
    const listeningPort = await host.listen();
    broker.accept('desktop', { id: 'standalone:init', method: 'initialize', params: {
      clientInfo: { name: 'custom_codex_android', title: 'Codex Remote', version: '0.1.0' },
      capabilities: { experimentalApi: true, requestAttestation: false },
    } });
    await ready;
    broker.accept('desktop', { method: 'initialized', params: {} });
    return { host, broker, backend, port: listeningPort, bindAddress, codexHome, accountFile, initialLoginFile, exited, close,
      get stopping() { return stopping; } };
  } catch (error) {
    await close();
    if (error.code === 'EADDRINUSE') throw Error('Bridge port is already in use; existing processes were left running');
    throw error;
  } finally { clearTimeout(timeout); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const runtime = await startStandalone();
    process.stdout.write(`Codex Remote standalone server ready at http://${isIP(runtime.bindAddress) === 6 ? `[${runtime.bindAddress}]` : runtime.bindAddress}:${runtime.port}\n`);
    process.stdout.write(`Codex home: ${runtime.codexHome}; initial login file: ${runtime.initialLoginFile}\n`);
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => void runtime.close());
    await runtime.exited;
    if (!runtime.stopping) { process.stderr.write('Codex app-server stopped unexpectedly\n'); process.exitCode = 1; }
  } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
