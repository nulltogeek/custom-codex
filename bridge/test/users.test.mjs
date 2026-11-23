import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createUserAuthentication } from '../users.mjs';

const req = token => ({ headers: { authorization: `Bearer ${token}` } });
const signup = (auth, username = 'alice', source = username) => auth.signup({ username, password: 'a-user-test-password' }, source);
function fixture(options = {}) {
  const root = mkdtempSync(join(tmpdir(), 'codex-users-'));
  const settings = { accountFile: join(root, 'account.json'), initialLoginFile: join(root, 'initial.json'),
    usersFile: join(root, 'users.json'), username: 'nulltogeek', ...options };
  const auth = createUserAuthentication(settings), credentials = JSON.parse(readFileSync(settings.initialLoginFile));
  return { auth, settings, credentials, root, owner: () => auth.login(credentials, 'admin') };
}

test('signup preserves legacy owner, persists private hashes and rejects case collisions and privilege injection', async () => {
  const f = fixture(); const before = readFileSync(f.settings.accountFile);
  const user = await signup(f.auth, 'Alice');
  assert.equal(user.username, 'alice'); assert.equal(user.user.role, 'user');
  const owner = await f.owner(); assert.equal(owner.user.role, 'admin'); assert.equal(owner.user.id, 'owner');
  await assert.rejects(signup(f.auth, 'ALICE'), { status: 409 });
  await assert.rejects(signup(f.auth, 'NULLTOGEEK'), { status: 409 });
  await assert.rejects(f.auth.signup({ username: 'bob', password: 'test-password-long', role: 'admin' }, 'b'), { status: 400 });
  await assert.rejects(signup(f.auth, '../escape'), { status: 400 });
  await assert.rejects(f.auth.signup({ username: 'short', password: 'short' }, 'short'), { status: 400 });
  assert.deepEqual(readFileSync(f.settings.accountFile), before);
  const stored = readFileSync(f.settings.usersFile, 'utf8');
  assert.equal(stored.includes('a-user-test-password'), false); assert.equal(statSync(f.settings.usersFile).mode & 0o777, 0o600);
  const restarted = createUserAuthentication(f.settings);
  assert.equal((await restarted.login({ username: 'ALICE', password: 'a-user-test-password' }, 'new')).username, 'alice');
  assert.equal((await restarted.login(f.credentials, 'admin')).user.id, 'owner');
  assert.equal(restarted.authenticate(req(user.token)), null);
});

test('only admin lists, creates, disables, resets, revokes, and controls signup; own admin is protected', async () => {
  const disabled = []; const f = fixture({ onUserDisabled: id => disabled.push(id) });
  const owner = req((await f.owner()).token), first = await signup(f.auth), user = req(first.token);
  assert.throws(() => f.auth.listUsers(user), { status: 403 });
  assert.throws(() => f.auth.listUsers(req('none')), { status: 401 });
  await assert.rejects(f.auth.createUser(user, { username: 'eve', password: 'a-test-password-long' }, 'eve'), { status: 403 });
  await assert.rejects(f.auth.updateUser(user, first.user.id, { disabled: true }), { status: 403 });
  assert.throws(() => f.auth.updateSettings(user, { signupEnabled: false }), { status: 403 });
  assert.throws(() => f.auth.revokeSessions(user, 'owner'), { status: 403 });
  await assert.rejects(f.auth.updateUser(owner, 'owner', { disabled: true }), { status: 400 });
  const managed = await f.auth.createUser(owner, { username: 'bob', password: 'b-test-password-long' }, 'bob');
  assert.equal(managed.user.role, 'user');
  assert.equal(f.auth.listUsers(owner).users.length, 3);
  assert.equal(JSON.stringify(f.auth.listUsers(owner)).includes('passwordHash'), false);
  f.auth.updateSettings(owner, { signupEnabled: false });
  await assert.rejects(signup(f.auth, 'closed'), { status: 403 });
  assert.equal(createUserAuthentication(f.settings).signupEnabled, false);
  f.auth.updateSettings(owner, { signupEnabled: true });
  let closed = 0; f.auth.track(user, () => closed++);
  await f.auth.updateUser(owner, first.user.id, { disabled: true });
  assert.equal(closed, 1); assert.deepEqual(disabled, [first.user.id]);
  assert.equal(f.auth.authenticate(user), null);
  await assert.rejects(f.auth.login({ username: 'alice', password: 'a-user-test-password' }, 'login'), { status: 401 });
  await f.auth.updateUser(owner, first.user.id, { disabled: false, password: 'a-reset-password-long' });
  const reset = await f.auth.login({ username: 'alice', password: 'a-reset-password-long' }, 'login');
  await assert.rejects(f.auth.login({ username: 'alice', password: 'a-user-test-password' }, 'login'), { status: 401 });
  f.auth.revokeSessions(owner, first.user.id); assert.equal(f.auth.authenticate(req(reset.token)), null);
  assert.ok(f.auth.authenticate(owner));
});

test('ordinary password changes affect only that user and maintain its current session', async () => {
  const f = fixture(), alice = await signup(f.auth), bob = await signup(f.auth, 'bob');
  const anotherAlice = await f.auth.login({ username: 'alice', password: 'a-user-test-password' }, 'alice');
  await f.auth.changePassword(req(alice.token), { currentPassword: 'a-user-test-password', newPassword: 'new-alice-password-long' }, 'alice');
  assert.ok(f.auth.authenticate(req(alice.token))); assert.equal(f.auth.authenticate(req(anotherAlice.token)), null);
  assert.ok(f.auth.authenticate(req(bob.token)));
  const restarted = createUserAuthentication(f.settings);
  assert.equal((await restarted.login({ username: 'alice', password: 'new-alice-password-long' }, 'alice')).username, 'alice');
  const owner = await f.owner();
  await f.auth.changePassword(req(owner.token), { currentPassword: f.credentials.password, newPassword: 'new-owner-password-long' }, 'admin');
  assert.equal((await createUserAuthentication(f.settings).login({ username: 'nulltogeek', password: 'new-owner-password-long' }, 'owner')).user.role, 'admin');
});

test('signup and login are throttled and all password work shares a bounded concurrency budget', async () => {
  let now = 100000; const f = fixture({ now: () => now, maxFailures: 2 });
  await assert.rejects(f.auth.login({ username: 'unknown', password: 'x' }, 'ip'), { status: 401 });
  await assert.rejects(f.auth.login({ username: 'unknown', password: 'x' }, 'ip'), { status: 401 });
  await assert.rejects(f.auth.login({ username: 'unknown', password: 'x' }, 'ip'), { status: 429 });
  now += 600001; assert.equal((await f.auth.login(f.credentials, 'ip')).username, 'nulltogeek');
  for (let i = 0; i < 5; i++) await assert.rejects(f.auth.signup({ username: '../bad', password: 'bad' }, 'signup-ip'), { status: 400 });
  await assert.rejects(signup(f.auth, 'blocked', 'signup-ip'), { status: 429 });
  const first = signup(f.auth, 'one'), second = signup(f.auth, 'two');
  await assert.rejects(f.auth.login(f.credentials, 'admin-other'), { status: 429 });
  await Promise.all([first, second]);
});

test('concurrent case-equivalent registrations create exactly one account', async () => {
  const f = fixture();
  const results = await Promise.allSettled([signup(f.auth, 'same'), signup(f.auth, 'SAME', 'other')]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.status, 409);
  assert.equal(f.auth.listUsers(req((await f.owner()).token)).users.length, 2);
});


test('successful attacker logins cannot reset the owner target failure budget', async () => {
  const f = fixture({ maxFailures: 2 }); await signup(f.auth, 'attacker');
  for (let i = 0; i < 2; i++) {
    await assert.rejects(f.auth.login({ username: 'NULLTOGEEK', password: 'wrong' }, 'same-ip'), { status: 401 });
    await f.auth.login({ username: 'attacker', password: 'a-user-test-password' }, 'same-ip');
  }
  await assert.rejects(f.auth.login({ username: 'nulltogeek', password: f.credentials.password }, 'same-ip'), { status: 429 });
});

test('admin deletion revokes all sessions, persists removal and reserves the old username', async () => {
  const stopped = [], f = fixture({ onUserDeleted: async id => stopped.push(id) });
  const owner = req((await f.owner()).token), alice = await signup(f.auth), bob = await signup(f.auth, 'bob');
  const otherSession = await f.auth.login({ username: 'alice', password: 'a-user-test-password' }, 'another-device');
  let closed = 0;
  f.auth.track(req(alice.token), () => closed++); f.auth.track(req(otherSession.token), () => closed++);
  const before = readFileSync(f.settings.accountFile);
  assert.deepEqual(await f.auth.deleteUser(owner, alice.user.id), { ok: true });
  assert.equal(closed, 2); assert.deepEqual(stopped, [alice.user.id]);
  assert.equal(f.auth.user(alice.user.id), null);
  assert.equal(f.auth.authenticate(req(alice.token)), null); assert.equal(f.auth.authenticate(req(otherSession.token)), null);
  assert.ok(f.auth.authenticate(req(bob.token))); assert.ok(f.auth.authenticate(owner));
  assert.deepEqual(f.auth.listUsers(owner).users.map(user => user.id), ['owner', bob.user.id]);
  const restarted = createUserAuthentication(f.settings);
  await assert.rejects(restarted.login({ username: 'alice', password: 'a-user-test-password' }, 'deleted'), { status: 401 });
  assert.equal(restarted.user(alice.user.id), null);
  await assert.rejects(signup(f.auth, 'ALICE', 'replacement'), { status: 409 });
  await assert.rejects(signup(restarted, 'ALICE', 'replacement'), { status: 409 });
  await assert.rejects(f.auth.createUser(owner, { username: 'alice', password: 'a-new-password-long' }, 'admin-create'), { status: 409 });
  assert.deepEqual(readFileSync(f.settings.accountFile), before);
  await assert.rejects(f.auth.deleteUser(owner, alice.user.id), { status: 404 });
});

test('deletion protects the owner, requires admin and rejects concurrent account changes', async () => {
  const f = fixture(), owner = req((await f.owner()).token), alice = await signup(f.auth);
  await assert.rejects(f.auth.deleteUser(req('invalid'), alice.user.id), { status: 401 });
  await assert.rejects(f.auth.deleteUser(req(alice.token), alice.user.id), { status: 403 });
  await assert.rejects(f.auth.deleteUser(owner, 'owner'), { status: 400 });
  const reset = f.auth.updateUser(owner, alice.user.id, { password: 'a-reset-password-long' });
  await assert.rejects(f.auth.deleteUser(owner, alice.user.id), { status: 409 });
  await reset;
  // A password verification started before deletion cannot mint a new session.
  const login = f.auth.login({ username: 'alice', password: 'a-reset-password-long' }, 'racing-login');
  const rejected = assert.rejects(login, { status: 409 });
  await f.auth.deleteUser(owner, alice.user.id); await rejected;
  assert.ok(f.auth.authenticate(owner));
});

test('identity-aware clients recreate deleted names with a new ID and old clients cannot log in', async () => {
  const f = fixture(), ownerToken = (await f.owner()).token, owner = req(ownerToken);
  const alice = await signup(f.auth);
  await f.auth.deleteUser(owner, alice.user.id);
  const modern = { headers: { ...owner.headers, 'x-codex-account-identity': '2' } };
  const replacement = await f.auth.createUser(modern, { username: 'ALICE', password: 'replacement-password-long' }, 'admin');
  assert.notEqual(replacement.user.id, alice.user.id);
  assert.equal(replacement.user.accountIdentityVersion, 2);
  assert.equal(f.auth.authenticate(req(alice.token)), null);
  assert.equal(f.auth.listUsers(owner).users.length, 2);
  await assert.rejects(f.auth.login({ username: 'alice', password: 'replacement-password-long' }, 'legacy'), { status: 426 });
  await assert.rejects(f.auth.login({ username: 'alice', password: 'a-user-test-password' }, 'old-password', '2'), { status: 401 });
  const login = await f.auth.login({ username: 'alice', password: 'replacement-password-long' }, 'modern', '2');
  assert.equal(login.user.id, replacement.user.id);
  const restarted = createUserAuthentication(f.settings);
  await assert.rejects(restarted.login({ username: 'alice', password: 'replacement-password-long' }, 'legacy'), { status: 426 });
  assert.equal((await restarted.login({ username: 'alice', password: 'replacement-password-long' }, 'modern', '2')).user.id, replacement.user.id);
  await f.auth.deleteUser(owner, replacement.user.id);
  const signupAgain = await f.auth.signup({ username: 'alice', password: 'third-password-long' }, 'modern-signup', '2');
  assert.notEqual(signupAgain.user.id, replacement.user.id);
  assert.equal(signupAgain.user.accountIdentityVersion, 2);
});
