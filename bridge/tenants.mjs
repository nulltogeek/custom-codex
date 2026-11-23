import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdirSync, writeFileSync, renameSync, lstatSync, existsSync } from 'node:fs';
import { dirname, join, resolve, relative, isAbsolute } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createProviderGateway, selectedProvider } from './provider-gateway.mjs';

const execute = promisify(execFile);
const failure = message => Object.assign(Error(message), { status: 503 });
function directory(path) {
  mkdirSync(path, { recursive: true, mode: 0o700 });
  const stat = lstatSync(path);
  if (!stat.isDirectory() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw failure('Private workspace directory is unavailable');
}
export function tenantPolicy(root) {
  const within = path => typeof path === 'string' && isAbsolute(path) && (path === root || (relative(root, path) !== '..' && !relative(root, path).startsWith('../') && !isAbsolute(relative(root, path))));
  return (method, params) => {
    if (params.accessMode === 'full') throw Error('Full host access is reserved for the administrator');
    if (Object.hasOwn(params, 'cwd') && !within(params.cwd)) throw Error('Choose a folder inside your private workspace');
    if (method === 'project/create' && params.roots?.some(root => !within(root.path))) throw Error('Projects must use folders inside your private workspace');
    // Rollout paths and configuration files are backend implementation details, never client-selected paths.
    if (['path', 'rolloutPath', 'configPath', 'environment'].some(key => Object.hasOwn(params, key))) throw Error('Host paths and configuration cannot be selected by users');
  };
}

/** Docker is a required execution boundary. There is deliberately no unsandboxed fallback. */
export function createTenantPool({ directory: root, ownerHome, backendPath, backendVersion, startRuntime,
  dockerPath = process.env.CODEX_TENANT_DOCKER ?? '/usr/bin/docker',
  image = process.env.CODEX_TENANT_IMAGE ?? 'python:3.12.14-slim-bookworm', maxRuntimes = 8, idleLifetimeMs = 600000,
  providerSettings, runtimeFactory }) {
  const entries = new Map(), retired = new Set(); let gateway, gatewayStarting, closed = false;
  root = resolve(root);
  async function provider() {
    if (gateway) return gateway;
    if (!gatewayStarting) gatewayStarting = (async () => {
      directory(root);
      const settings = providerSettings ?? selectedProvider(ownerHome);
      gateway = await createProviderGateway({ settings, socketPath: join(root, '.provider-' + randomBytes(8).toString('hex') + '.sock') });
      return gateway;
    })().catch(error => { gatewayStarting = null; throw error; });
    return gatewayStarting;
  }
  async function start(user, entry) {
    if (runtimeFactory) return runtimeFactory(user, root);
    const shared = await provider();
    await execute(dockerPath, ['image', 'inspect', '--format', '{{.Id}}', image], { timeout: 10000, maxBuffer: 2048 }).catch(() => { throw failure('Install the configured user workspace container image before enabling user workspaces'); });
    directory(root);
    const userRoot = join(root, user.id), home = join(userRoot, 'home'), codexHome = join(home, '.codex');
    const workspaceRoot = join(userRoot, 'workspace'), uploadDirectory = join(userRoot, 'uploads');
    for (const path of [userRoot, home, codexHome, workspaceRoot, uploadDirectory]) directory(path);
    const configPath = join(codexHome, 'config.toml');
    if (existsSync(configPath) && !lstatSync(configPath).isFile()) throw failure('Private workspace configuration is unavailable');
    const token = shared.token(user.id);
    const config = `model = ${JSON.stringify(shared.model)}\nmodel_provider = "shared"\nmodel_reasoning_effort = ${JSON.stringify(shared.effort)}\napproval_policy = "on-request"\nsandbox_mode = "danger-full-access"\n[model_providers.shared]\nname = "Shared model service"\nbase_url = "http://127.0.0.1:3030/v1"\nwire_api = "responses"\nenv_key = "CODEX_USER_GATEWAY_TOKEN"\nrequires_openai_auth = false\nsupports_websockets = false\n`;
    const temporary = join(codexHome, '.config-' + randomBytes(8).toString('hex'));
    writeFileSync(temporary, config, { flag: 'wx', mode: 0o600 }); renameSync(temporary, configPath);
    const name = 'codex-user-' + user.id + '-' + randomBytes(5).toString('hex');
    // Separate mounts keep host-managed uploads and parent directories immutable to user commands.
    const args = ['run', '--rm', '--interactive', '--name', name, '--init', '--read-only',
      '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=128', '--memory=1g', '--cpus=1',
      '--user', `${process.getuid()}:${process.getgid()}`, '--network', 'none',
      '--tmpfs', '/tmp:rw,nosuid,nodev,size=256m', '--tmpfs', '/run:rw,nosuid,nodev,size=16m',
      '--mount', `type=bind,src=${dirname(backendPath)},dst=/opt/codex-runtime,readonly`,
      '--mount', `type=bind,src=${shared.socketPath},dst=/run/codex-provider.sock,readonly`,
      '--mount', `type=bind,src=${fileURLToPath(new URL('./tenant-entrypoint.py', import.meta.url))},dst=/opt/tenant-entrypoint.py,readonly`,
      '--mount', `type=bind,src=${home},dst=${home}`,
      '--mount', `type=bind,src=${codexHome},dst=${codexHome}`,
      '--mount', `type=bind,src=${configPath},dst=${configPath},readonly`,
      '--mount', `type=bind,src=${workspaceRoot},dst=${workspaceRoot}`,
      '--mount', `type=bind,src=${uploadDirectory},dst=${uploadDirectory},readonly`,
      '--env', `HOME=${home}`, '--env', `CODEX_HOME=${codexHome}`,
      '--env', 'PATH=/opt/codex-runtime:/usr/local/bin:/usr/bin:/bin',
      '--env', 'CODEX_USER_GATEWAY_TOKEN', '--workdir', workspaceRoot,
      image, 'python3', '/opt/tenant-entrypoint.py', '/opt/codex-runtime/' + backendPath.split('/').at(-1), 'app-server', '--listen', 'stdio://'];
    let runtime;
    try {
      runtime = await startRuntime({ backendPath, codexHome, cwd: workspaceRoot, backendVersion,
        spawnCommand: dockerPath, spawnArgs: args, environment: { PATH: process.env.PATH ?? '/usr/bin:/bin',
          CODEX_USER_GATEWAY_TOKEN: token }, authorize: tenantPolicy(workspaceRoot),
        onProtocolError: () => execute(dockerPath, ['rm', '--force', name], { timeout: 5000 }).catch(() => {}),
        onStopped: () => { shared.revoke(user.id); if (!entry.stopping && entries.get(user.id) === entry) entries.delete(user.id); } });
    } catch { shared.revoke(user.id); await execute(dockerPath, ['rm', '--force', name], { timeout: 5000 }).catch(() => {});
      throw failure('The private workspace could not start. Ask the administrator to check Docker and the configured runtime image.'); }
    const stop = runtime.close;
    runtime.close = async () => { shared.revoke(user.id); await execute(dockerPath, ['stop', '--time', '5', name], { timeout: 10000 }).catch(() => {}); return stop(); };
    return { ...runtime, workspaceRoot, uploadDirectory,
      // These reads happen inside the container, so user-created symlinks cannot target host history.
      readHistory: (archived, cursor, searchTerm) => runtime.call('thread/list', { archived, cursor, searchTerm, limit: 50,
        sourceKinds: ['cli', 'vscode', 'exec', 'appServer', 'subAgent', 'unknown'] }),
      readProjects: async () => {
        const value = await runtime.call('project/list', {});
        const projects = (value.projects ?? value.data ?? []).map(project => ({ ...project, rootPaths: project.rootPaths ?? (project.roots ?? []).map(root => typeof root === 'string' ? root : root.path) }));
        return { projects, assignments: value.assignments ?? {} };
      },
    };
  }
  const idle = entry => entry.runtime && !entry.stopping && entry.runtime.broker.clients.size === 0 &&
    entry.runtime.broker.requests.size === 0 && entry.runtime.broker.serverRequests.size === 0 &&
    (entry.runtime.broker.activeTurns?.size ?? 0) === 0;
  const api = {
    get(user) {
      if (closed || retired.has(user.id) || user.disabled || !/^[0-9a-f-]{36}$/.test(user.id)) throw failure('Private workspace unavailable');
      const previous = entries.get(user.id);
      if (previous) { previous.lastUsed = Date.now(); return previous.stopping ? previous.stopping.then(() => this.get(user)) : previous.promise; }
      if (entries.size >= maxRuntimes) {
        const candidate = [...entries].filter(([, entry]) => idle(entry)).sort((a, b) => a[1].lastUsed - b[1].lastUsed)[0];
        if (candidate) return this.stop(candidate[0]).then(() => this.get(user));
        throw failure('All user workspaces are busy. Try again when a user disconnects or running work finishes.');
      }
      const entry = { promise: null, stopping: null, runtime: null, lastUsed: Date.now() };
      entry.promise = Promise.resolve().then(() => start(user, entry)).then(runtime => { entry.runtime = runtime; return runtime; })
        .catch(error => { if (!entry.stopping && entries.get(user.id) === entry) entries.delete(user.id); throw error; });
      entries.set(user.id, entry); return entry.promise;
    },
    retire(id) { retired.add(id); return this.stop(id); },
    stop(id) {
      const entry = entries.get(id);
      if (!entry) return Promise.resolve();
      if (entry.stopping) return entry.stopping;
      gateway?.revoke(id);
      entry.stopping = (async () => {
        const runtime = await entry.promise.catch(() => null);
        await runtime?.close();
      })().finally(() => { if (entries.get(id) === entry) entries.delete(id); });
      return entry.stopping;
    },
    async close() { closed = true; clearInterval(reap); await Promise.allSettled([...entries.keys()].map(id => this.stop(id))); gateway?.close(); },
    get activeCount() { return entries.size; },
  };
  const reap = setInterval(() => { for (const [id, entry] of entries) if (idle(entry) &&
    Date.now() - Math.max(entry.lastUsed, entry.runtime.broker.lastActivityAt ?? 0) >= idleLifetimeMs) void api.stop(id); }, Math.min(30000, idleLifetimeMs));
  reap.unref();
  return api;
}
