// Temporary standalone real-backend fixture for Android instrumentation. No model calls.
import { resolve } from 'node:path';
import { mkdirSync, writeFileSync } from 'node:fs';
import { startStandalone } from '../standalone.mjs';

mkdirSync('.runtime', { recursive: true, mode: 0o700 });
const runtime = await startStandalone({ port: 18787,
  accountFile: resolve('.runtime/android-test-account.json'),
  initialLoginFile: resolve('.runtime/android-test-login.json'), username: 'android-test' });
console.log('Android integration host ready on 127.0.0.1:18787. Login is in the private task runtime directory.');
process.stdin.on('data', data => { if (data.toString().includes('stop')) void runtime.close(); });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => void runtime.close());
await runtime.exited;
writeFileSync('.runtime/android-integration-host.json', JSON.stringify({ runtimeMode: 'standalone', backendExited: true }));
