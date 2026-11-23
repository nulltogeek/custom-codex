#!/usr/bin/env node
import { execFile, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { homedir, userInfo } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { isIP } from 'node:net';
import { Transform } from 'node:stream';
import { Broker, HUMAN_REQUESTS } from './broker.mjs';
import { createHost } from './host.mjs';
import { createUserAuthentication } from './users.mjs';
import { createTenantPool } from './tenants.mjs';
const execute = promisify(execFile);

export function protocolFrames(maxBytes = 16 * 1024 * 1024) {
  let bytes = 0;
  return new Transform({ transform(chunk, _encoding, done) {
    for (let start = 0; start < chunk.length;) {
      const newline = chunk.indexOf(10, start), end = newline < 0 ? chunk.length : newline;
      bytes += end - start;
      if (bytes > maxBytes) { done(Error('Codex app-server protocol frame exceeded its size limit')); return; }
      if (newline < 0) break;
      bytes = 0; start = newline + 1;
    }
    done(null, chunk);
  } });
}

/** One independently initialized backend; HTTP routing is handled separately. */
export async function startRuntime({ backendPath, backendArgs = ['app-server', '--listen', 'stdio://'], codexHome,
  cwd = homedir(), backendVersion, startupTimeoutMs = 30000, environment = { ...process.env, CODEX_HOME: codexHome },
  spawnCommand, spawnArgs, authorize, onStopped = () => {}, onProtocolError = () => {}, maxProtocolFrameBytes }) {
  const backend = spawn(spawnCommand ?? backendPath, spawnArgs ?? backendArgs, { cwd, env: environment, stdio: ['pipe', 'pipe', 'pipe'] });
  // Never echo provider errors or model content into the service log.
  backend.stderr.resume();
  const exited = new Promise(resolveExit => backend.once('close', (code, signal) => resolveExit({ code, signal })));
  let initialized, initializationFailed, stopping = false, closing;
  const ready = new Promise((yes, no) => { initialized = yes; initializationFailed = no; });
  // Attach rejection handling before listen() awaits, including an early spawn error.
  ready.catch(() => {});
  let broker, internalSerial = 0;
  const internal = new Map();
  const owner = message => {
    const request = internal.get(message.id);
    if (request) { internal.delete(message.id); clearTimeout(request.timeout); message.error ? request.reject(Error(message.error.message)) : request.resolve(message.result); return; }
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
    if (backend.stdin.destroyed) return false;
    const frame = `${JSON.stringify(message)}\n`;
    if (backend.stdin.writableLength + Buffer.byteLength(frame) > 8 * 1024 * 1024) return false;
    backend.stdin.write(frame); return true;
  }, desktop: owner, authorize });
  const frames = protocolFrames(maxProtocolFrameBytes);
  frames.on('error', error => { initializationFailed(error); Promise.resolve(onProtocolError()).catch(() => {}); void close(); });
  backend.stdout.pipe(frames);
  const output = createInterface({ input: frames });
  output.on('line', line => {
    try { broker.receive(JSON.parse(line)); }
    catch { initializationFailed(Error('Codex app-server emitted an invalid protocol frame')); void close(); }
  });
  backend.stdin.on('error', () => {});
  backend.on('error', () => initializationFailed(Error('Codex app-server could not start')));
  backend.on('close', () => {
    broker.ready = false;
    initializationFailed(Error('Codex app-server stopped before initialization'));
    for (const request of internal.values()) { clearTimeout(request.timeout); request.reject(Error('Private runtime stopped')); }
    internal.clear(); onStopped(); output.close();
  });
  function close() {
    if (closing) return closing;
    stopping = true; broker.ready = false; backend.stdin.end();
    closing = (async () => {
      const timeout = setTimeout(() => backend.kill('SIGTERM'), 10000); timeout.unref();
      try { return await exited; } finally { clearTimeout(timeout); output.close(); }
    })();
    return closing;
  }
  const timeout = setTimeout(() => initializationFailed(Error('Codex app-server initialization timed out')), startupTimeoutMs);
  try {
    broker.accept('desktop', { id: 'standalone:init', method: 'initialize', params: {
      clientInfo: { name: 'custom_codex_android', title: 'Codex Remote', version: '0.1.0' },
      capabilities: { experimentalApi: true, requestAttestation: false },
    } });
    await ready;
    broker.accept('desktop', { method: 'initialized', params: {} });
    return { broker, backend, codexHome, backendPid: backend.pid, backendVersion, runtimeMode: 'standalone', exited, close,
      call(method, params) { return new Promise((resolve, reject) => {
        const id = 'runtime:' + ++internalSerial;
        const timeout = setTimeout(() => { internal.delete(id); reject(Error('Private runtime request timed out')); }, 30000);
        internal.set(id, { resolve, reject, timeout }); broker.accept('desktop', { id, method, params });
      }); },
      get stopping() { return stopping; } };
  } catch (error) {
    await close();
    throw error;
  } finally { clearTimeout(timeout); }
}

/** Keep the existing owner runtime, with separate private runtimes for signed-up users. */
export async function startStandalone({
  backendPath = process.env.CODEX_REAL_CLI_PATH ?? '/usr/lib/chatgpt/resources/codex',
  backendArgs = ['app-server', '--listen', 'stdio://'],
  codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex'),
  accountFile = process.env.CODEX_LOGIN_ACCOUNT_FILE ?? join(homedir(), '.local/share/custom-codex/account.json'),
  initialLoginFile = process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'),
  username = process.env.CODEX_LOGIN_USERNAME ?? userInfo().username,
  usersFile = join(dirname(accountFile), 'users.json'), tenantDirectory = join(dirname(accountFile), 'users'),
  port = Number(process.env.CODEX_BRIDGE_PORT ?? 8787), bindAddress = process.env.CODEX_BRIDGE_BIND ?? '127.0.0.1',
  cwd = homedir(), backendVersion, startupTimeoutMs = 30000, tenantOptions = {},
} = {}) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('Invalid bridge port');
  if (!isIP(bindAddress)) throw Error('Bridge bind address must be a literal interface IP address');
  codexHome = resolve(codexHome);
  if (!backendVersion) {
    try {
      const { stdout } = await execute(backendPath, ['--version'], { timeout: 5000, maxBuffer: 2048 });
      backendVersion = stdout.match(/\b\d+\.\d+\.\d+[a-zA-Z0-9.-]*/)?.[0] ?? 'unknown';
    } catch { throw Error('Configured Codex binary is unavailable; check CODEX_REAL_CLI_PATH'); }
  }
  let pool, host;
  const authentication = createUserAuthentication({ accountFile, initialLoginFile, username, usersFile,
    onUserDisabled: id => { void pool?.stop(id); }, onUserDeleted: id => pool?.retire(id) });
  const runtime = await startRuntime({ backendPath, backendArgs, codexHome, cwd, backendVersion, startupTimeoutMs,
    onStopped: () => { host?.close(); void pool?.close(); } });
  pool = createTenantPool({ directory: tenantDirectory, ownerHome: codexHome, backendPath, backendVersion,
    startRuntime, ...tenantOptions });
  host = createHost({ ...runtime, authentication, port, bindAddress, resolveRuntime: async session => {
    if (!session?.user) throw Object.assign(Error('Authentication required'), { status: 401 });
    return session.user.id === 'owner' ? { ...runtime,
      uploadDirectory: process.env.CODEX_UPLOAD_DIRECTORY ?? join(dirname(accountFile), 'uploads') } : pool.get(session.user);
  } });
  const close = async () => { host.close(); await pool.close(); return runtime.close(); };
  try {
    const listeningPort = await host.listen();
    return { ...runtime, host, port: listeningPort, bindAddress, codexHome, accountFile, initialLoginFile, usersFile,
      authentication, pool, close, get stopping() { return runtime.stopping; } };
  } catch (error) {
    await close();
    if (error.code === 'EADDRINUSE') throw Error('Bridge port is already in use; existing processes were left running');
    throw error;
  }
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
