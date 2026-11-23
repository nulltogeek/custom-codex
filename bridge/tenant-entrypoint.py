"""Run Codex with no network, relaying only its authenticated model connection over a Unix socket."""
import os
import select
import signal
import socket
import socketserver
import subprocess
import sys
import threading


class ProviderRelay(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            upstream.connect('/run/codex-provider.sock')
            peers = [self.request, upstream]
            while True:
                readable, _, _ = select.select(peers, [], [], 300)
                if not readable:
                    return
                for current in readable:
                    data = current.recv(65536)
                    if not data:
                        return
                    target = upstream if current is self.request else self.request
                    target.sendall(data)
        except (OSError, ValueError):
            return
        finally:
            upstream.close()


class RelayServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


with RelayServer(('127.0.0.1', 3030), ProviderRelay) as relay:
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    backend = subprocess.Popen(sys.argv[1:])
    def forward(signum, _frame):
        if backend.poll() is None:
            backend.send_signal(signum)
    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    try:
        sys.exit(backend.wait())
    finally:
        relay.shutdown()
