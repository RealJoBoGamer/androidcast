#!/usr/bin/env python3
"""
Control an AndroidCast stick over Bluetooth from a laptop (Linux or Windows,
Python 3.9+, no extra packages).

Pair the laptop with the Fire TV stick first, then:

    python androidcast.py AA:BB:CC:DD:EE:FF status
    python androidcast.py AA:BB:CC:DD:EE:FF next
    python androidcast.py AA:BB:CC:DD:EE:FF upload intro.mp4 studio.jpg
    python androidcast.py AA:BB:CC:DD:EE:FF wifi "My Network" "password123"
    python androidcast.py AA:BB:CC:DD:EE:FF shell        # interactive

Any other arguments are sent as a command line, e.g. `goto 3`, `fit contain`.
The RFCOMM channel is found automatically (or pass --channel N).
"""
import argparse
import os
import shlex
import socket
import sys
import time


def connect(address, channel=None):
    channels = [channel] if channel else range(1, 31)
    last_error = None
    for ch in channels:
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.settimeout(8)
        try:
            s.connect((address, ch))
            conn = Connection(s)
            greeting = conn.read_line()
            if "AndroidCast" in greeting:
                return conn
            s.close()
        except OSError as e:
            last_error = e
            s.close()
    sys.exit(f"Could not find AndroidCast on {address} ({last_error}). Is it paired and the app installed?")


def quote(arg):
    """Quotes an argument the way the stick's command parser expects."""
    if arg and not any(c in arg for c in ' "\\'):
        return arg
    return '"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"'


class Connection:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def read_line(self, timeout=30):
        self.sock.settimeout(timeout)
        while b"\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("connection closed")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return line.decode("utf-8", "replace").rstrip("\r")

    def command(self, line, timeout=60):
        """Sends one command and returns all reply lines (the last starts with OK/ERR)."""
        self.sock.sendall(line.encode("utf-8") + b"\n")
        lines = []
        while True:
            reply = self.read_line(timeout)
            lines.append(reply)
            if reply.startswith(("OK", "ERR")):
                return lines

    def upload(self, path):
        size = os.path.getsize(path)
        name = os.path.basename(path)
        self.sock.sendall(f'UPLOAD "{name}" {size}\n'.encode("utf-8"))
        reply = self.read_line()
        if reply != "READY":
            return [reply]
        sent, start = 0, time.time()
        with open(path, "rb") as f:
            while chunk := f.read(16384):
                self.sock.sendall(chunk)
                sent += len(chunk)
                rate = sent / max(time.time() - start, 0.001) / 1024
                print(f"\r  {name}: {sent * 100 // size}%  ({rate:.0f} KB/s)", end="", flush=True)
        print()
        return [self.read_line(timeout=120)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("address", help="Bluetooth MAC address of the Fire TV stick")
    ap.add_argument("--channel", type=int, help="RFCOMM channel (default: auto-detect)")
    ap.add_argument("command", nargs="+", help="command, e.g. next / upload FILE... / shell")
    args = ap.parse_args()

    conn = connect(args.address, args.channel)
    cmd = args.command[0].lower()
    ok = True

    if cmd == "upload":
        for path in args.command[1:]:
            for line in conn.upload(path):
                print(line)
                ok &= line.startswith("OK")
    elif cmd == "shell":
        print('Connected. Type HELP for commands, "upload <file>" to send a file, Ctrl+C to quit.')
        try:
            while True:
                line = input("> ").strip()
                if not line:
                    continue
                if line.lower().startswith("upload "):
                    for path in shlex.split(line)[1:]:
                        print("\n".join(conn.upload(path)))
                else:
                    print("\n".join(conn.command(line)))
        except (KeyboardInterrupt, EOFError):
            print()
    else:
        line = " ".join(quote(a) for a in args.command)
        for reply in conn.command(line):
            print(reply)
            ok = reply.startswith("OK")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
