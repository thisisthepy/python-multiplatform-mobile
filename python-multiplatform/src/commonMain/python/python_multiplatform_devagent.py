"""The dev agent: a debug-only channel from a notebook or a hot reload into the running app's interpreter.

python-multiplatform SPEC L-12, issue #233; the design is toolchain's docs/design/live-connection.md.

The runtime starts it from `Python3.initialize()` only when the launcher sets the environment variable
`PYTHON_MULTIPLATFORM_DEV_AGENT` to `port=<n>;token=<t>[;overlay=<dir>]` (a debug launch does; a release
build never does). It listens on 127.0.0.1 only. A connection's first message must be `hello` with the
token; anything else closes the connection.

Messages are JSON objects, one per line, each carrying the request's `id`:

- `{"op": "hello", "token": t}` -> `{"type": "hello", "protocol": 1, "python": ..., "pid": ...}`
- `{"op": "execute", "code": src}` -> any number of `{"type": "stream", "name": "stdout"|"stderr", "text"}`,
  a `{"type": "result", "data": {"text/plain": repr}}` when the last statement is an expression whose value
  is not None, then `{"type": "done", "status": "ok"|"error", "count": n}` (on error also `ename`, `evalue`,
  `traceback`). The code runs in `__main__`'s namespace, which persists across requests.
- `{"op": "complete", "code": src, "cursor_pos": n}` -> `done` with `matches`, `cursor_start`, `cursor_end`.
- `{"op": "push", "files": {relative path: text}}` -> the files are written into the overlay, a writable
  directory first on `sys.path` (an app's payload can be read-only: a desktop jar, an iOS bundle), and every
  already-imported module among them is re-imported, children first. `done` lists them in `reloaded`. If
  `typedpython.importgate` is in the app, the gate covers the overlay (SPEC N-7b), so a module that does not
  type-check is refused; a refused or failing re-import leaves the old module in place.

Stdlib only: it has to run inside any app, on every platform python-multiplatform supports.
"""
import ast
import hmac
import importlib
import importlib.util
import json
import os
import rlcompleter
import socket
import sys
import tempfile
import threading
import traceback

PROTOCOL = 1
ENVIRONMENT_VARIABLE = "PYTHON_MULTIPLATFORM_DEV_AGENT"
LOOPBACK = "127.0.0.1"


def parse_request(value):
    """`port=<n>;token=<t>[;overlay=<dir>]` -> `{"port", "token", "overlay"}`; ValueError when malformed."""
    fields = {}
    for part in (value or "").split(";"):
        if "=" in part:
            key, _, item = part.partition("=")
            fields[key.strip()] = item.strip()
    try:
        port = int(fields["port"])
        token = fields["token"]
    except (KeyError, ValueError):
        raise ValueError(f"{ENVIRONMENT_VARIABLE} must be port=<n>;token=<t>[;overlay=<dir>], not {value!r}")
    if not token or not 0 <= port <= 65535:
        raise ValueError(f"{ENVIRONMENT_VARIABLE} must be port=<n>;token=<t>[;overlay=<dir>], not {value!r}")
    return {"port": port, "token": token, "overlay": fields.get("overlay") or None}


def start_from_env():
    """Start an agent when the launcher asked for one; return it, or None when it did not."""
    value = os.environ.get(ENVIRONMENT_VARIABLE)
    if not value:
        return None
    request = parse_request(value)
    agent = start(port=request["port"], token=request["token"], overlay=request["overlay"])
    print(f"python-multiplatform dev agent: listening on {agent.host}:{agent.port}", file=sys.stderr, flush=True)
    return agent


def start(port, token, overlay=None):
    """Start an agent on loopback; `port=0` picks a free one (the agent's `port` says which)."""
    agent = Agent(token, overlay or tempfile.mkdtemp(prefix="python-multiplatform-overlay-"))
    agent.listen(port)
    return agent


class _Stream:
    """Replaces sys.stdout or sys.stderr during a request: every write goes to the client."""

    def __init__(self, connection, request_id, name):
        self.connection = connection
        self.request_id = request_id
        self.name = name

    def write(self, text):
        if text:
            self.connection.send({"id": self.request_id, "type": "stream", "name": self.name, "text": text})
        return len(text)

    def flush(self):
        pass

    def isatty(self):
        return False


class Agent:
    def __init__(self, token, overlay):
        self.token = token
        self.overlay = os.path.abspath(overlay)
        os.makedirs(self.overlay, exist_ok=True)
        if self.overlay not in sys.path:
            sys.path.insert(0, self.overlay)
        self.namespace = sys.modules["__main__"].__dict__
        self.count = 0
        # One request at a time across connections: requests share __main__, sys.stdout and the modules.
        self.lock = threading.Lock()
        self.host = LOOPBACK
        self.port = None
        self.server = None
        self.connections = []
        self.gate = self._install_gate()

    def _install_gate(self):
        try:
            from typedpython import importgate
        except ImportError:
            return None
        importgate.install([self.overlay])
        return importgate

    def listen(self, port):
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((LOOPBACK, port))
        self.server.listen()
        self.port = self.server.getsockname()[1]
        threading.Thread(target=self._accept, name="python-multiplatform-devagent", daemon=True).start()

    def stop(self):
        try:
            self.server.close()
        except OSError:
            pass
        for connection in list(self.connections):
            connection.close()

    def _accept(self):
        while True:
            try:
                sock, _ = self.server.accept()
            except OSError:
                return
            connection = _Connection(self, sock)
            self.connections.append(connection)
            threading.Thread(target=connection.serve, name="python-multiplatform-devagent-client", daemon=True).start()


class _Connection:
    def __init__(self, agent, sock):
        self.agent = agent
        self.sock = sock
        self.reader = sock.makefile("r", encoding="utf-8")
        self.send_lock = threading.Lock()
        self.authenticated = False

    def send(self, message):
        data = (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")
        with self.send_lock:
            self.sock.sendall(data)

    def close(self):
        for closable in (self.reader, self.sock):
            try:
                closable.close()
            except OSError:
                pass
        if self in self.agent.connections:
            self.agent.connections.remove(self)

    def serve(self):
        try:
            for line in self.reader:
                if not line.strip():
                    continue
                if not self._handle(json.loads(line)):
                    break
        except (OSError, ValueError):
            pass
        finally:
            self.close()

    def _handle(self, message):
        """Answer one request; False closes the connection."""
        request_id = message.get("id")
        op = message.get("op")
        if not self.authenticated:
            token = message.get("token") if op == "hello" else None
            if token is None or not hmac.compare_digest(str(token), self.agent.token):
                self.send({"id": request_id, "type": "error", "ename": "AuthError", "evalue": "hello with the token first"})
                return False
            self.authenticated = True
            self.send({"id": request_id, "type": "hello", "protocol": PROTOCOL, "python": sys.version.split()[0], "pid": os.getpid()})
            return True
        handler = {"hello": self._hello, "execute": self._execute, "complete": self._complete, "push": self._push}.get(op)
        if handler is None:
            self._done(request_id, error=("UnknownOp", f"unknown op {op!r}", []))
            return True
        with self.agent.lock:
            handler(request_id, message)
        return True

    def _done(self, request_id, error=None, **fields):
        reply = {"id": request_id, "type": "done", "status": "ok" if error is None else "error", **fields}
        if error is not None:
            reply["ename"], reply["evalue"], reply["traceback"] = error
        self.send(reply)

    @staticmethod
    def _error(exception):
        return type(exception).__name__, str(exception), traceback.format_exception(type(exception), exception, exception.__traceback__)

    def _hello(self, request_id, message):
        self.send({"id": request_id, "type": "hello", "protocol": PROTOCOL, "python": sys.version.split()[0], "pid": os.getpid()})

    def _execute(self, request_id, message):
        self.agent.count += 1
        count = self.agent.count
        namespace = self.agent.namespace
        stdout, stderr = sys.stdout, sys.stderr
        sys.stdout = _Stream(self, request_id, "stdout")
        sys.stderr = _Stream(self, request_id, "stderr")
        try:
            tree = ast.parse(message.get("code", ""), filename=f"<cell {count}>", mode="exec")
            last = tree.body.pop() if tree.body and isinstance(tree.body[-1], ast.Expr) else None
            exec(compile(tree, f"<cell {count}>", "exec"), namespace)
            if last is not None:
                value = eval(compile(ast.Expression(last.value), f"<cell {count}>", "eval"), namespace)
                if value is not None:
                    namespace["_"] = value
                    self.send({"id": request_id, "type": "result", "data": {"text/plain": repr(value)}})
        except BaseException as exception:  # a cell's SystemExit or KeyboardInterrupt must not end the agent
            sys.stdout, sys.stderr = stdout, stderr
            self._done(request_id, error=self._error(exception), count=count)
            return
        finally:
            sys.stdout, sys.stderr = stdout, stderr
        self._done(request_id, count=count)

    def _complete(self, request_id, message):
        code = message.get("code", "")
        cursor = int(message.get("cursor_pos", len(code)))
        before = code[:cursor]
        start = cursor
        while start > 0 and (before[start - 1].isalnum() or before[start - 1] in "_."):
            start -= 1
        text = before[start:]
        completer = rlcompleter.Completer(self.agent.namespace)
        matches = []
        state = 0
        while True:
            match = completer.complete(text, state)
            if match is None:
                break
            matches.append(match.rstrip("("))
            state += 1
        self._done(request_id, matches=sorted(set(matches)), cursor_start=start, cursor_end=cursor)

    def _push(self, request_id, message):
        overlay = self.agent.overlay
        written = []
        try:
            for relative, text in (message.get("files") or {}).items():
                normal = os.path.normpath(relative)
                if os.path.isabs(relative) or normal.startswith("..") or normal == "." or not normal.endswith(".py"):
                    raise ValueError(f"{relative!r} is not a .py path inside the overlay")
                target = os.path.join(overlay, normal)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "w", encoding="utf-8") as f:
                    f.write(text)
                # Bytecode is validated by mtime in seconds and size; a same-size edit within the second
                # would load the stale .pyc, so it goes.
                cached = importlib.util.cache_from_source(target)
                if os.path.exists(cached):
                    os.remove(cached)
                written.append(normal)
        except (ValueError, OSError) as exception:
            self._done(request_id, error=self._error(exception))
            return
        importlib.invalidate_caches()
        names = [self._module_name(path) for path in written]
        loaded = sorted((n for n in names if n in sys.modules), key=lambda n: n.count("."), reverse=True)
        reloaded = []
        for name in loaded:
            try:
                importlib.reload(sys.modules[name])
            except BaseException as exception:
                self._done(request_id, error=self._error(exception), written=written, reloaded=reloaded)
                return
            reloaded.append(name)
        self._done(request_id, written=written, reloaded=reloaded)

    @staticmethod
    def _module_name(path):
        parts = path[: -len(".py")].replace(os.sep, "/").split("/")
        if parts[-1] == "__init__":
            parts.pop()
        return ".".join(parts)
