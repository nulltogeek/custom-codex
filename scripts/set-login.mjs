#!/usr/bin/env node
// Apply an owner-only username/password JSON file; never accept credentials in arguments.
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { privateJson, passwordAccount } from '../bridge/auth.mjs';

const accountFile = process.env.CODEX_LOGIN_ACCOUNT_FILE ?? join(homedir(), '.local/share/custom-codex/account.json');
const inputFile = process.argv[2] ?? process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json');
try {
  const input = privateJson(inputFile), account = passwordAccount(input.username, input.password);
  if (!existsSync(accountFile)) throw Error('Start the server once to provision its account directory first');
  privateJson(accountFile);
  const suffix = randomBytes(6).toString('hex');
  const backup = `${accountFile}.backup-${suffix}`;
  writeFileSync(backup, readFileSync(accountFile), { flag: 'wx', mode: 0o600 });
  const temporary = `${accountFile}.new-${suffix}`;
  writeFileSync(temporary, JSON.stringify(account, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  renameSync(temporary, accountFile);
  console.log(`Login updated for ${account.username}. Previous account hash preserved at ${backup}.`);
  console.log('When server-owned work is idle, restart codex-remote.service to load the new login. Existing sessions will expire.');
} catch (error) {
  console.error(error instanceof SyntaxError ? 'Invalid login JSON file' : error.message);
  process.exitCode = 1;
}
