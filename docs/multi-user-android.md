# Android accounts and user management (0.8.0)

Codex Remote now supports separate password accounts, with an administrator account named **nulltogeek**. Each signed-in user sees their own chats, projects, attachments, and running work. The server must be upgraded and configured for private user runtimes as well as the Android app.

## Install the new Android app

Use the newly delivered **release APK** on Android 11 or later. Release builds require an HTTPS server address; debug builds are separate packages intended for local testing.

This checkout does not include the previous installation's private signing key. A newly signed APK cannot upgrade an existing package signed by a different key, and the in-app updater cannot bypass Android's signature check. The agreed installation path is to uninstall the old app and install the delivered APK when Android reports a signature mismatch.

Uninstalling removes this phone's cached history, unsent drafts, local attachments, favorites, and message queue. Save any unsent work first. Chats and projects already saved on the server remain there and load after you sign in again. Keep the new signing key for future updates so later releases can install without another uninstall.

## Create an account or sign in

1. Connect to the private network used by your server, including WireGuard if required.
2. Open Codex Remote and enter the server's HTTPS address.
3. Tap **New here? Create an account**. Choose a username containing 1–64 letters, numbers, dots, underscores, or hyphens. The server treats usernames without regard to letter case and returns their canonical lowercase form.
4. Enter a password of at least 12 characters, repeat it in **Confirm password**, and tap **Create account**. Passwords may contain spaces; matching spaces matter. The maximum is 1024 UTF-8 bytes.
5. For an existing account, choose **Already have an account? Sign in** and enter its username and password.

The app saves an encrypted session in Android Keystore-backed storage. It never saves the password. The phone's account password authenticates access to the private server; model-provider credentials are configured separately on the server.

New projects prefill your private workspace folder. You can select an existing subfolder there or clear the field to create a project without folders. Chats created outside a project, or in a project without folders, start in your private workspace.

If registrations are closed, ask **nulltogeek** to create your account or reopen registration. If a username is taken, use your existing account or choose a different username.

## Administrator controls

Sign in as **nulltogeek**, open the navigation drawer, and choose **Manage users**. This destination appears only when the server grants the user-management capability.

- View the user list and whether each account is enabled or disabled.
- Open or close public registration with **Allow new registrations**.
- Create a user directly using **Create user**, including while public registration is closed.
- Use a user's menu to disable or enable their account, reset their password, or sign out their current devices.
- Disabling an account ends its sessions and blocks new sign-ins while retaining its chats and projects. Resetting a password also ends that user's existing sessions.

The administrator cannot disable the owner account or reset its password through the user-management menu. Change the administrator's own password in **Settings → Change password**. Give passwords created or reset for other people to them privately.

User management does not provide an interface for reading another user's conversations.

## Sign out and switch accounts

Choose **Settings → Sign out**, then sign in with the other account. Signing out clears the current user's active screen, pending requests, and in-memory chat state. The app cancels requests from that session before switching identities. Cached history, drafts, attachments, chat settings, favorites, and queues are stored separately by server and account.

Upgrading with the same signing key migrates the old shared history and draft storage once, to the account previously saved on the phone. A new account does not inherit that data. An uninstall starts with empty device storage instead.

Signing out asks the server to revoke that device's session. If the server is unreachable, the app removes its local session and reports that server revocation could not be completed; the administrator can sign out that user's devices later.

## Account created, but chats do not open

Registration and private runtime startup are separate steps. A username can be successfully created even if the server cannot subsequently start that user's private Codex runtime. A runtime setup failure is not proof that the password or registration failed.

Keep the username and password, ask the administrator to check private-runtime setup, then use **Sign in** with the same account after the server is repaired. Do not create duplicate accounts to work around a runtime error. The server deployment guide covers runtime requirements, model-provider configuration, and workspace isolation.

If a session expires or the administrator disables the account, resets its password, or signs out its devices, the app returns to sign-in. Use the current password, or contact the administrator if the account remains unavailable.
