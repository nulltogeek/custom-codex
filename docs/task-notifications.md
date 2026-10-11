# Task completion notifications

Enable **Task completion notifications** in Settings. Android 13 and later asks
for notification permission. If alerts have been disabled at the app or channel
level, Android's notification settings lets you enable them. Notifications are
opt-in and can be turned off in Settings or the monitoring notification.

Successful `turn/completed` events produce an alert for any chat, including a chat
that isn't open. Failed or interrupted work does not produce a success alert.
Tapping an alert opens that chat after the phone connects. Links are scoped to
the signed-in server and account. Recent completion identities are saved per
server/account to avoid duplicate alerts on reconnect or relaunch. Notifications
do not include message contents and hide chat titles on the lock screen.

A separate, read-only foreground service subscribes to the host event stream.
Its quiet ongoing notification keeps the subscription available when the app is
backgrounded. It does not send messages, approve requests or dispatch queues.
Signing out, turning notifications off, permission revocation and session expiry
stop monitoring. Connection loss triggers bounded reconnect attempts; the bridge
can replay events while its recent event buffer still contains them.

Android can stop background monitoring, including the Android 15 `dataSync`
foreground-service time limit (six background hours per 24 hours), force-stop and
battery restrictions. The service stops promptly on its timeout callback and
resumes when you reopen the app. It does not start on boot or restart itself
while the app is closed. Tasks completed while monitoring is stopped or after a
replay gap may not generate an alert. The host must be reachable through the
phone's network/VPN. This uses the existing authenticated WebSocket and does not
require a push notification provider or a backend restart.
