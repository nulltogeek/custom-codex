# Connection recovery

The app previously used coroutine timeouts for WebSocket opening and RPC replies.
Those deadlines throw `TimeoutCancellationException`, which the foreground and
notification reconnect loops treated as cancellation of their entire job. A slow
handshake, initialization, subscription, command-output negotiation or later
metadata request could stop automatic reconnection until another app lifecycle
event. Timed-out live requests could leave the screen reporting “Connected”. A
cancelled connection attempt could also leave its socket open. Foreground retry
delays accumulated across successful connections, eventually reaching 30 seconds
even for brief, separate interruptions.

Transport deadlines now raise a recoverable `RpcTimeoutException` (an IO error).
The timeout discards the affected socket, marks it lost and releases other pending
requests. Both existing retry loops stay alive. Connection-attempt failures clean
up their socket; real caller cancellation still propagates. Cancelling an ordinary
history read preserves a healthy shared socket. Socket cleanup checks ownership
so a delayed callback cannot close a replacement connection.

Retry waits remain bounded at 1, 2, 4, 8, 16 and 30 seconds during an outage.
A successful subscription resets the next wait to one second in both foreground
and task monitoring. WebSocket opening and RPC deadlines remain 20 and 30 seconds
in production. Tests inject shorter deadlines without changing release behavior.
The existing WebSocket heartbeat remains enabled.

Reconnection resumes the saved replay cursor and reloads current history.
It does not resend prompts. A message accepted before the connection or its reply
failed still requires checking history before resending; queued uncertain sends
retain their existing safeguards. HTTP 401 continues to require signing in again.

This repair changes Android only. The Linux service, proxy, WireGuard configuration
and backend were not restarted or modified. Healthy server/proxy probes from Linux
do not prove that a particular phone's authenticated path works. Native phone
testing remains necessary to establish the cause of that device's symptom.
