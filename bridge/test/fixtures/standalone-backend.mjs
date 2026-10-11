// Controlled backend for standalone lifecycle/approval tests; no model or filesystem operations.
import { createInterface } from 'node:readline';
const lines = createInterface({ input: process.stdin });
const send = value => process.stdout.write(`${JSON.stringify(value)}\n`);
lines.on('line', line => {
  const message = JSON.parse(line);
  if (message.method === 'initialize') {
    send({ id: message.id, result: { userAgent: 'standalone-fixture', codexHome: process.env.CODEX_HOME,
      client: message.params.clientInfo, attestation: message.params.capabilities.requestAttestation } });
  } else if (message.method === 'thread/start') {
    send({ id: message.id, result: { thread: { id: 'fixture-thread' } } });
    if (message.params.simulate) send({ id: 'fixture-request', method: message.params.simulate, params: {
      threadId: 'fixture-thread', turnId: 'fixture-turn', availableDecisions: ['accept', 'decline'] } });
  } else if (!message.method && message.id === 'fixture-request') {
    send({ method: 'fixture/answered', params: { answer: message.result ?? null, errorCode: message.error?.code ?? null } });
  } else if (message.id !== undefined) {
    send({ id: message.id, result: {} });
  }
});
