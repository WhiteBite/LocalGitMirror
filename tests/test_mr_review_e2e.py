"""E2E snapshot-review lifecycle across the real tool surfaces: a real
encrypted postbox (uvicorn + backend routers), a real GitLab HTTP fixture,
CLI subprocesses (prepare/send/push/receipts) and MCP NDJSON subprocesses
(open/read/search/context/progress/materialize/findings/status). No fakes in
the chain; in-process calls are limited to store construction for the probe,
os.utime expiry manipulation and digest helpers."""
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, unquote, urlparse

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))
sys.path.insert(0, str(REPO_ROOT / "tests"))
sys.path.insert(0, str(REPO_ROOT / "backend"))

from lgm_core import mr_review_models as m
from lgm_core.mr_review_environment import mr_review_probe
from lgm_core.mr_review_models import mcp_result_bytes
from lgm_core.mr_review_store import MrReviewStore
from test_mr_review_store import _git, _mirror_state
from test_mr_review_transfer import _clone, _init_repo, _mr_detail

EVIDENCE_DIR = REPO_ROOT / ".omo" / "evidence" / "mr-review-workspace"
FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "mr_review_e2e"
BUDGET = 48000
WEB_BASE = "https://gitlab.corp"

SCENARIOS = {
    "main": SimpleNamespace(iid=30, pid=42, source_pid=42, target_branch="master"),
    "big": SimpleNamespace(iid=31, pid=43, source_pid=43, target_branch="master"),
    "fork": SimpleNamespace(iid=32, pid=44, source_pid=99, target_branch="master"),
    "targetbr": SimpleNamespace(iid=33, pid=45, source_pid=45,
                                target_branch="release/2.5"),
    "del": SimpleNamespace(iid=34, pid=46, source_pid=46, target_branch="master"),
    "stale": SimpleNamespace(iid=35, pid=47, source_pid=47, target_branch="master"),
}
for _name, _sc in SCENARIOS.items():
    _sc.name = _name
    _sc.repo = f"e2e-{_name}"
    _sc.project = f"dev/onyx/{_name}"
    _sc.web_url = f"{WEB_BASE}/{_sc.project}/-/merge_requests/{_sc.iid}"

TRANSCRIPT: list[dict] = []


@pytest.fixture(scope="session", autouse=True)
def _transcript_dump():
    yield
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    (EVIDENCE_DIR / "task-16-transcript.json").write_text(
        json.dumps(TRANSCRIPT, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8")


def _trim(value):
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if isinstance(item, list):
                out[key] = f"<list:{len(item)}>" if len(item) > 4 else _trim(item)
            elif isinstance(item, dict):
                out[key] = _trim(item)
            else:
                out[key] = item
        return out
    if isinstance(value, list):
        return [_trim(item) for item in value]
    return value


def _record(entry: dict) -> None:
    TRANSCRIPT.append(entry)


# ── real encrypted postbox (uvicorn + backend routers) ──────────────────────


class _PostboxControl:
    fail_uploads = False


@pytest.fixture(scope="session")
def postbox(tmp_path_factory):
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from fastapi import FastAPI
    from fastapi.responses import JSONResponse

    from app.core import sync_envelope
    from app.core.repo_manager import RepoManager
    from app.routers import auth as auth_mod
    from app.routers import file_sync as file_sync_mod

    storage = tmp_path_factory.mktemp("postbox") / "storage"
    storage.mkdir(parents=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8")
    rm = RepoManager(storage)
    key = X25519PrivateKey.generate()
    file_sync_mod.repo_manager = rm
    file_sync_mod.system_logger = None
    file_sync_mod.server_private_key = key
    file_sync_mod.relay_key = os.urandom(32)
    sync_envelope.server_private_key = key
    control = _PostboxControl()
    app = FastAPI()

    @app.middleware("http")
    async def _fault_injection(request, call_next):
        if control.fail_uploads \
                and request.url.path.endswith("/attachment-upload"):
            return JSONResponse({"detail": "fault injected"}, status_code=500)
        return await call_next(request)

    app.include_router(auth_mod.router)
    app.include_router(file_sync_mod.router)
    import uvicorn
    config = uvicorn.Config(app, host="127.0.0.1", port=0, log_level="error")
    server = uvicorn.Server(config)
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.time() + 30
    while not server.started:
        if time.time() > deadline:
            raise RuntimeError("postbox server did not start")
        time.sleep(0.05)
    port = server.servers[0].sockets[0].getsockname()[1]
    yield SimpleNamespace(base_url=f"http://127.0.0.1:{port}", control=control,
                          storage=storage)
    server.should_exit = True
    file_sync_mod.repo_manager = None
    file_sync_mod.server_private_key = None
    file_sync_mod.relay_key = None
    sync_envelope.server_private_key = None


def _postbox_repo_dir(postbox, repo: str) -> Path:
    digest = hashlib.sha256(repo.encode("utf-8")).hexdigest()[:16]
    return postbox.storage / ".lgm" / "files" / digest


# ── real GitLab HTTP fixture ────────────────────────────────────────────────


class _GitLabState:
    def __init__(self):
        self.details: dict[int, dict] = {}
        self.tips: dict[tuple[str, str], str] = {}
        self.discussions: dict[int, list] = {}
        self.requests: list[tuple[str, str]] = []
        self.writes: list[tuple[str, str, dict]] = []
        self.positioned = 0
        self.thread_notes = 0
        self.general_notes = 0
        self.lock = threading.Lock()


GITLAB_STATE = _GitLabState()

_RE_DETAIL = re.compile(r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)$")
_RE_BRANCH = re.compile(r"^/api/v4/projects/([^/]+)/repository/branches/(.+)$")
_RE_DISCUSSIONS = re.compile(
    r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)/discussions$")
_RE_THREAD_NOTE = re.compile(
    r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)/discussions/([^/]+)/notes$")
_RE_GENERAL_NOTE = re.compile(
    r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)/notes$")
_RE_RESOLVE = re.compile(
    r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)/discussions/([^/]+)$")
_RE_COMPARE = re.compile(r"^/api/v4/projects/([^/]+)/repository/compare$")


class _GitLabHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, payload, extra_headers: dict | None = None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw.decode("utf-8")) if raw else {}

    def _route(self, method: str):
        parsed = urlparse(self.path)
        path = parsed.path
        state = GITLAB_STATE
        with state.lock:
            state.requests.append((method, path))
        if method in ("POST", "PUT"):
            payload = self._body()
            with state.lock:
                state.writes.append((method, path, payload))
        else:
            payload = {}
        match = _RE_DETAIL.match(path)
        if match and method == "GET":
            iid = int(match.group(2))
            with state.lock:
                detail = state.details.get(iid)
            if detail is None:
                self._send(404, {"message": "404 Not found"})
            else:
                self._send(200, detail)
            return
        match = _RE_BRANCH.match(path)
        if match and method == "GET":
            proj = unquote(match.group(1))
            branch = unquote(match.group(2))
            with state.lock:
                tip = state.tips.get((proj, branch))
            if tip is None:
                self._send(404, {"message": "404 Branch Not found"})
            else:
                self._send(200, {"name": branch, "commit": {"id": tip}})
            return
        match = _RE_DISCUSSIONS.match(path)
        if match and method == "GET":
            iid = int(match.group(2))
            query = parse_qs(parsed.query)
            page = int((query.get("page") or ["1"])[0])
            per_page = int((query.get("per_page") or ["100"])[0])
            with state.lock:
                items = list(state.discussions.get(iid, []))
            chunk = items[(page - 1) * per_page: page * per_page]
            headers = {}
            if page * per_page < len(items):
                headers["X-Next-Page"] = str(page + 1)
            self._send(200, chunk, headers)
            return
        match = _RE_COMPARE.match(path)
        if match and method == "GET":
            self._send(200, {"diffs": []})
            return
        match = _RE_THREAD_NOTE.match(path)
        if match and method == "POST":
            iid = int(match.group(2))
            discussion_id = unquote(match.group(3))
            with state.lock:
                state.thread_notes += 1
                note_id = 700 + state.thread_notes
                for discussion in state.discussions.get(iid, []):
                    if discussion.get("id") == discussion_id:
                        discussion["notes"].append({
                            "id": note_id, "author": {"name": "Agent"},
                            "created_at": "2026-10-30T12:00:00Z",
                            "system": False, "body": payload.get("body", ""),
                            "resolvable": False})
                        break
            self._send(201, {"id": note_id, "body": payload.get("body", "")})
            return
        match = _RE_GENERAL_NOTE.match(path)
        if match and method == "POST":
            iid = int(match.group(2))
            with state.lock:
                state.general_notes += 1
                note_id = 800 + state.general_notes
                state.discussions.setdefault(iid, []).append({
                    "id": f"d-gen-{state.general_notes}",
                    "notes": [{"id": note_id, "author": {"name": "Agent"},
                               "created_at": "2026-10-30T12:00:00Z",
                               "system": False,
                               "body": payload.get("body", ""),
                               "resolvable": False}]})
            self._send(201, {"id": note_id, "body": payload.get("body", "")})
            return
        match = _RE_RESOLVE.match(path)
        if match and method == "PUT":
            iid = int(match.group(2))
            discussion_id = unquote(match.group(3))
            with state.lock:
                for discussion in state.discussions.get(iid, []):
                    if discussion.get("id") == discussion_id:
                        for note in discussion.get("notes", []):
                            if note.get("resolvable"):
                                note["resolved"] = True
            self._send(200, {"id": discussion_id})
            return
        match = _RE_DETAIL.match(path)
        if match and method == "POST" and path.endswith("/discussions"):
            pass
        match = re.match(
            r"^/api/v4/projects/([^/]+)/merge_requests/(\d+)/discussions$", path)
        if match and method == "POST":
            iid = int(match.group(2))
            with state.lock:
                state.positioned += 1
                n = state.positioned
                note_id = 900 + n
                discussion = {
                    "id": f"disc-{n}",
                    "notes": [{"id": note_id, "author": {"name": "Agent"},
                               "created_at": "2026-10-30T12:00:00Z",
                               "system": False,
                               "body": payload.get("body", ""),
                               "resolvable": True, "resolved": False,
                               "position": payload.get("position")}]}
                state.discussions.setdefault(iid, []).append(discussion)
            self._send(201, {"id": discussion["id"],
                             "notes": [{"id": note_id}]})
            return
        self._send(404, {"message": f"no fixture route for {method} {path}"})

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_PUT(self):
        self._route("PUT")

    def do_DELETE(self):
        self._route("DELETE")


@pytest.fixture(scope="session")
def gitlab():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _GitLabHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield SimpleNamespace(base_url=f"http://127.0.0.1:{server.server_address[1]}",
                          state=GITLAB_STATE)
    server.shutdown()
    server.server_close()


# ── scenario repos ──────────────────────────────────────────────────────────


def _feature_repo(root: Path, name: str) -> tuple[Path, dict]:
    origin = root / f"{name}-origin"
    _init_repo(origin)
    (origin / "F.txt").write_text("base\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "base")
    base = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-qb", "feature")
    (origin / "F.txt").write_text("base\nfeature\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "feature")
    head = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-q", "master")
    return origin, {"base": base, "head": head, "start": base, "tip": base}


def _build_main(root: Path) -> tuple[Path, dict]:
    origin = root / "main-origin"
    _init_repo(origin)
    (origin / "src").mkdir()
    lines = "".join(f"line{i}\n" for i in range(1, 16))
    (origin / "src" / "config.py").write_text(
        lines.replace("line12", "old = 12"), encoding="utf-8")
    (origin / "README.md").write_text("main\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "base")
    base = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-qb", "feature")
    (origin / "src" / "config.py").write_text(
        lines.replace("line12", "new = 12"), encoding="utf-8")
    (origin / "src" / "new.py").write_text("value = 1\n", encoding="utf-8")
    (origin / "README.md").write_text("main feature\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "feature")
    head = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-q", "master")
    (origin / "T.txt").write_text("target-only\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "target only")
    start = _git(origin, "rev-parse", "HEAD").stdout.strip()
    return origin, {"base": base, "head": head, "start": start, "tip": start}


def _build_big(root: Path) -> tuple[Path, dict]:
    origin = root / "big-origin"
    _init_repo(origin)
    (origin / "seed.txt").write_text("seed\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "base")
    base = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-qb", "feature")
    files = origin / "files"
    files.mkdir()
    for i in range(1000):
        (files / f"f{i:04d}.py").write_text(f"# file {i}\nvalue = {i}\n",
                                            encoding="utf-8")
    (origin / "vocab.txt").write_text(
        " ".join(f"word{i}" for i in range(200000)), encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "1000 files and one huge line")
    head = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-q", "master")
    return origin, {"base": base, "head": head, "start": base, "tip": base}


def _build_fork(root: Path) -> tuple[Path, dict]:
    origin, shas = _feature_repo(root, "fork")
    _git(origin, "update-ref", "refs/merge-requests/32/head", shas["head"])
    return origin, shas


def _build_targetbr(root: Path) -> tuple[Path, dict]:
    origin = root / "targetbr-origin"
    _init_repo(origin)
    (origin / "F.txt").write_text("base\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "base")
    _git(origin, "checkout", "-qb", "release/2.5")
    (origin / "R.txt").write_text("release\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "release")
    start = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-qb", "feature")
    (origin / "F.txt").write_text("base\nfeature\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "feature")
    head = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-q", "master")
    return origin, {"base": start, "head": head, "start": start, "tip": start}


def _build_del(root: Path) -> tuple[Path, dict]:
    origin = root / "del-origin"
    _init_repo(origin)
    for name in ("a.txt", "b.txt", "c.txt"):
        (origin / name).write_text(f"{name}\n", encoding="utf-8")
    _git(origin, "add", ".")
    _git(origin, "commit", "-qm", "base")
    base = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-qb", "feature")
    (origin / "a.txt").unlink()
    (origin / "b.txt").unlink()
    _git(origin, "add", "-A")
    _git(origin, "commit", "-qm", "delete only")
    head = _git(origin, "rev-parse", "HEAD").stdout.strip()
    _git(origin, "checkout", "-q", "master")
    return origin, {"base": base, "head": head, "start": base, "tip": base}


def _build_stale(root: Path) -> tuple[Path, dict]:
    return _feature_repo(root, "stale")


_BUILDERS = {"main": _build_main, "big": _build_big, "fork": _build_fork,
             "targetbr": _build_targetbr, "del": _build_del,
             "stale": _build_stale}


def _seed_discussions(sc, shas: dict) -> list:
    if sc.name != "main":
        return []
    refs = {"base_sha": shas["base"], "start_sha": shas["start"],
            "head_sha": shas["head"]}
    return [
        {"id": "d-thread-1", "notes": [
            {"id": 11, "author": {"name": "Reviewer"},
             "created_at": "2026-10-01T10:00:00Z", "system": False,
             "body": "seed thread on config.py", "resolvable": True,
             "resolved": False,
             "position": {"old_path": "src/config.py",
                          "new_path": "src/config.py",
                          "old_line": None, "new_line": 12, **refs}}]},
        {"id": "d-sys", "notes": [
            {"id": 12, "author": {"name": "GitLab"},
             "created_at": "2026-10-01T09:00:00Z", "system": True,
             "body": "assigned to @reviewer"}]},
    ]


@pytest.fixture(scope="module")
def world(tmp_path_factory, postbox, gitlab):
    root = tmp_path_factory.mktemp("e2e")
    scenarios: dict[str, SimpleNamespace] = {}
    for name, sc in SCENARIOS.items():
        origin, shas = _BUILDERS[name](root)
        checkout = _clone(origin, root / f"{name}-checkout")
        detail = _mr_detail(
            shas, iid=sc.iid, project_id=sc.pid,
            source_project_id=sc.source_pid, target_project_id=sc.pid,
            source_branch="feature", target_branch=sc.target_branch,
            title=f"E2E {name}", web_url=sc.web_url)
        discussions = _seed_discussions(sc, shas)
        state = gitlab.state
        with state.lock:
            state.details[sc.iid] = detail
            state.discussions[sc.iid] = discussions
            state.tips[(str(sc.pid), sc.target_branch)] = shas["tip"]
            state.tips[(sc.project, sc.target_branch)] = shas["tip"]
        scenarios[name] = SimpleNamespace(
            **{k: getattr(sc, k) for k in
               ("name", "iid", "pid", "source_pid", "repo", "project",
                "target_branch", "web_url")},
            origin=origin, checkout=checkout, shas=shas, detail=detail,
            discussions=discussions)

    work_home = root / "work-home"
    home_home = root / "home-home"
    work_reviews = root / "work-reviews"
    home_reviews = root / "home-reviews"
    for directory in (work_home, home_home, work_reviews, home_reviews):
        directory.mkdir(parents=True, exist_ok=True)
    registry_dir = work_home / ".lgm"
    registry_dir.mkdir(parents=True)
    (registry_dir / "mr-projects.json").write_text(json.dumps({
        "projects": [{"repo": sc.repo, "path": str(sc.checkout),
                      "gitlab": sc.project} for sc in scenarios.values()]
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    home_registry = home_home / ".lgm"
    home_registry.mkdir(parents=True)
    (home_registry / "mr-projects.json").write_text(
        json.dumps({"projects": []}), encoding="utf-8")

    def env(role: str) -> dict:
        reviews = work_reviews if role == "work" else home_reviews
        profile = work_home if role == "work" else home_home
        base = dict(os.environ)
        base.update({
            "BASE_URL": postbox.base_url,
            "GITLAB_URL": gitlab.base_url,
            "GITLAB_TOKEN": "tok",
            "GITLAB_PROJECT": scenarios["main"].project,
            "LGM_MACHINE_ROLE": role,
            "LGM_REVIEWS_HOME": str(reviews),
            "USERPROFILE": str(profile),
            "GIT_TERMINAL_PROMPT": "0",
        })
        return base

    digests_before = {}
    for name, sc in scenarios.items():
        digests_before[f"{name}-origin"] = _mirror_state(sc.origin)
        digests_before[f"{name}-checkout"] = _mirror_state(sc.checkout)

    return SimpleNamespace(
        root=root, scenarios=scenarios, postbox=postbox, gitlab=gitlab,
        work_env=env("work"), home_env=env("home"),
        work_reviews=work_reviews, home_reviews=home_reviews,
        digests_before=digests_before)


# ── real surfaces: CLI and MCP subprocesses ─────────────────────────────────


def _cli(world, op: str, env: dict, expect_exit: int = 0, **flags):
    cmd = [sys.executable, str(REPO_ROOT / "lgm.py"), "--json",
           "--base-url", world.postbox.base_url, "--api-key", "test-key", op]
    for key, value in flags.items():
        if value is None or value is False:
            continue
        flag = f"--{key.replace('_', '-')}"
        if value is True:
            cmd.append(flag)
        else:
            cmd += [flag, str(value)]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=300,
                          cwd=str(REPO_ROOT), env=env)
    result = None
    stdout = (proc.stdout or "").strip()
    if stdout.startswith("{"):
        result = json.loads(stdout)
    _record({"surface": "cli", "op": op, "args": _trim(flags),
             "exit": proc.returncode, "result": _trim(result)})
    assert proc.returncode == expect_exit, \
        f"lgm.py {op} exit {proc.returncode}: {proc.stdout}\n{proc.stderr}"
    return result


class McpError(AssertionError):
    pass


class _McpProcess:
    def __init__(self, env: dict):
        self._proc = subprocess.Popen(
            [sys.executable, str(REPO_ROOT / "lgm_mcp.py")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=str(REPO_ROOT), env=env,
            text=True, encoding="utf-8", errors="replace")
        self._next_id = 0
        self._request("initialize", {})

    def _send(self, message: dict) -> None:
        self._proc.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        self._proc.stdin.flush()

    def _request(self, method: str, params: dict) -> dict:
        self._next_id += 1
        mid = self._next_id
        self._send({"jsonrpc": "2.0", "id": mid, "method": method,
                    "params": params})
        while True:
            line = self._proc.stdout.readline()
            if not line:
                stderr = self._proc.stderr.read()
                raise McpError(f"mcp subprocess died: {stderr}")
            line = line.strip()
            if not line.startswith("{"):
                continue
            response = json.loads(line)
            if response.get("id") == mid:
                return response

    def notify_initialized(self) -> None:
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, tool: str, arguments: dict) -> tuple[bool, dict]:
        response = self._request(
            "tools/call", {"name": tool, "arguments": arguments})
        assert "error" not in response, response
        result = response.get("result") or {}
        is_error = bool(result.get("isError"))
        text = result["content"][0]["text"]
        data = json.loads(text)
        _record({"surface": "mcp", "tool": tool, "args": _trim(arguments),
                 "isError": is_error, "result": _trim(data)})
        return (not is_error), data

    def close(self) -> None:
        try:
            self._proc.stdin.close()
        except OSError:
            pass
        try:
            self._proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self._proc.kill()


@pytest.fixture(scope="module")
def mcp_home(world):
    proc = _McpProcess(world.home_env)
    proc.notify_initialized()
    yield proc
    proc.close()


@pytest.fixture(scope="module")
def mcp_work(world):
    proc = _McpProcess(world.work_env)
    proc.notify_initialized()
    yield proc
    proc.close()


def _mcp_open(mcp, world, sc, **extra):
    arguments = {"repo": sc.repo, "iid": sc.iid,
                 "home": str(world.home_reviews), "budget_bytes": BUDGET}
    arguments.update(extra)
    ok, data = mcp.call("mr_review_open", arguments)
    assert ok, data
    return data


# ── stage 1: prepare (CLI/WORK) -> postbox -> open (MCP/HOME) ───────────────


@pytest.fixture(scope="module")
def prepared(world, mcp_work, mcp_home):
    out = {}
    for name in ("main", "big", "fork", "targetbr", "del"):
        sc = world.scenarios[name]
        result = _cli(world, "mr_review_prepare", world.work_env,
                      repo=sc.repo, iid=sc.iid, project=sc.project,
                      checkout=sc.checkout, work_dir=world.root / "artifacts")
        assert result["status"] == "prepared", result
        out[name] = result
    ok, items = mcp_work.call("file_list", {"repo": world.scenarios["main"].repo})
    assert ok, items
    opened = _mcp_open(mcp_home, world, world.scenarios["main"])
    return SimpleNamespace(prepare=out, postbox_items=items, open=opened)


def test_stage1_prepare_uploads_code_and_envelope(prepared, world):
    sc = world.scenarios["main"]
    result = prepared.prepare["main"]
    assert re.fullmatch(r"[0-9a-f]{64}", result["snapshot_id"])
    assert result["bundle"]["action"] == "uploaded"
    assert result["manifest_path"] == \
        f"mr-review/snapshots/{result['snapshot_id']}.json"
    paths = {item["path"] for item in prepared.postbox_items["files"]}
    assert result["manifest_path"] in paths
    assert f"mr-review/code/{result['code_id']}.bundle" in paths
    assert sc.iid == 30


def test_stage1_open_imports_snapshot_over_real_postbox(prepared, world):
    opened = prepared.open
    sc = world.scenarios["main"]
    assert opened["status"] == "imported", opened
    assert opened["snapshot_id"] == prepared.prepare["main"]["snapshot_id"]
    assert opened["identity"]["iid"] == sc.iid
    assert opened["identity"]["target_branch"] == "master"
    assert opened["summary"]["bundle_downloaded"] is True
    assert opened["summary"]["discussions"] == 2
    assert opened["current_snapshot_id"] == opened["snapshot_id"]
    inventory = opened["inventory"]
    assert inventory["complete"] is True
    entry_paths = {entry["path"] for entry in inventory["entries"]}
    assert {"src/config.py", "src/new.py", "README.md"} <= entry_paths
    assert mcp_result_bytes(opened) <= BUDGET


# ── stage 2: paging, huge lines, fork, non-default target, deletion-only ────


def _chunk_bytes(chunk: dict) -> bytes:
    import base64
    if chunk["encoding"] == "utf-8":
        return chunk["content"].encode("utf-8")
    return base64.b64decode(chunk["content"])


@pytest.fixture(scope="module")
def big_opened(world, mcp_home, prepared):
    sc = world.scenarios["big"]
    opened = _mcp_open(mcp_home, world, sc)
    assert opened["status"] == "imported", opened
    return opened


def test_stage2_big_mr_inventory_fully_paged(world, mcp_home, big_opened):
    sc = world.scenarios["big"]
    pages = [big_opened["inventory"]]
    cursor = big_opened["inventory"]["next_cursor"]
    while cursor:
        ok, data = mcp_home.call("mr_review_open", {
            "repo": sc.repo, "iid": sc.iid, "cursor": cursor,
            "budget_bytes": BUDGET, "home": str(world.home_reviews)})
        assert ok, data
        assert data["status"] == "paged"
        pages.append(data["inventory"])
        cursor = data["inventory"]["next_cursor"]
    assert len(pages) > 1
    assert [page["complete"] for page in pages] == \
        [False] * (len(pages) - 1) + [True]
    assert pages[-1]["next_cursor"] is None
    paths = [entry["path"] for page in pages for entry in page["entries"]]
    expected = {f"files/f{i:04d}.py" for i in range(1000)} | {"vocab.txt"}
    assert len(paths) == len(set(paths)), "duplicate entries across pages"
    assert set(paths) == expected
    assert pages[0]["totals"]["files"] == 1001
    for page in pages:
        assert mcp_result_bytes(page) <= BUDGET


def test_stage2_big_mr_diff_walk_covers_every_file_once(
        world, mcp_home, big_opened):
    sc = world.scenarios["big"]
    sid = big_opened["snapshot_id"]
    pages = []
    cursor = ""
    while True:
        arguments = {"repo": sc.repo, "snapshot_id": sid, "view": "diff",
                     "budget_bytes": BUDGET, "home": str(world.home_reviews)}
        if cursor:
            arguments["cursor"] = cursor
        ok, page = mcp_home.call("mr_review_read", arguments)
        assert ok, page
        pages.append(page)
        if page["complete"]:
            break
        cursor = page["next_cursor"]
    assert len(pages) > 1
    assert [page["complete"] for page in pages] == \
        [False] * (len(pages) - 1) + [True]
    for page in pages:
        assert mcp_result_bytes(page) <= BUDGET
    text = b"".join(_chunk_bytes(chunk)
                    for page in pages for chunk in page["chunks"])
    for i in range(1000):
        name = f"files/f{i:04d}.py"
        header = f"diff --git a/{name} b/{name}".encode()
        assert text.count(header) == 1, name
    assert b"diff --git a/vocab.txt b/vocab.txt" in text


def test_stage2_huge_line_bounded_no_full_line_echo(world, mcp_home,
                                                    big_opened):
    sc = world.scenarios["big"]
    ok, page = mcp_home.call("mr_review_read", {
        "repo": sc.repo, "snapshot_id": big_opened["snapshot_id"],
        "view": "file", "paths": ["vocab.txt"], "side": "new",
        "budget_bytes": BUDGET, "home": str(world.home_reviews)})
    assert ok, page
    assert mcp_result_bytes(page) <= BUDGET
    entry = [item for item in page["files"]
             if item["path"] == "vocab.txt"][0]
    assert entry["chunks"]
    delivered = sum(len(_chunk_bytes(chunk)) for chunk in entry["chunks"])
    assert delivered < entry["size"]
    assert page["complete"] is False
    assert "word199999" not in json.dumps(page)


def test_stage2_search_and_context_over_real_mcp(world, mcp_home, prepared):
    sc = world.scenarios["main"]
    sid = prepared.open["snapshot_id"]
    ok, page = mcp_home.call("mr_review_search", {
        "repo": sc.repo, "snapshot_id": sid, "query": "new = 12",
        "side": "new", "scope": "changed", "budget_bytes": BUDGET,
        "home": str(world.home_reviews)})
    assert ok, page
    assert page["complete"] is True
    assert [hit["path"] for hit in page["hits"]] == ["src/config.py"]
    assert page["hits"][0]["side"] == "new"
    assert mcp_result_bytes(page) <= BUDGET

    ok, ctx = mcp_home.call("mr_review_context", {
        "repo": sc.repo, "snapshot_id": sid, "path": "src/config.py",
        "line": 12, "side": "new", "context_lines": 3,
        "budget_bytes": BUDGET, "home": str(world.home_reviews)})
    assert ok, ctx
    assert [row["line"] for row in ctx["lines"]] == [9, 10, 11, 12, 13, 14, 15]
    assert ctx["lines"][3]["text"] == "new = 12"
    assert mcp_result_bytes(ctx) <= BUDGET


def test_stage2_fork_mr_flows_through_merge_request_ref(world, mcp_home,
                                                        prepared):
    sc = world.scenarios["fork"]
    ref_sha = _git(sc.origin, "rev-parse",
                   "refs/merge-requests/32/head").stdout.strip()
    assert ref_sha == sc.shas["head"]
    opened = _mcp_open(mcp_home, world, sc)
    assert opened["status"] == "imported", opened
    assert opened["identity"]["source_project_id"] == 99
    assert opened["identity"]["target_project_id"] == sc.pid
    assert opened["identity"]["source_sha"] == sc.shas["head"]
    assert "source branch fetched through target-project MR ref" \
        in opened["limitations"]


def test_stage2_non_default_target_branch(world, mcp_home, prepared):
    sc = world.scenarios["targetbr"]
    opened = _mcp_open(mcp_home, world, sc)
    assert opened["status"] == "imported", opened
    assert opened["identity"]["target_branch"] == "release/2.5"
    assert opened["identity"]["source_sha"] == sc.shas["head"]
    assert opened["identity"]["target_tip_sha"] == sc.shas["tip"]


def test_stage2_deletion_only_mr(world, mcp_home, prepared):
    sc = world.scenarios["del"]
    opened = _mcp_open(mcp_home, world, sc)
    assert opened["status"] == "imported", opened
    inventory = opened["inventory"]
    assert inventory["complete"] is True
    entries = {entry["path"]: entry for entry in inventory["entries"]}
    assert set(entries) == {"a.txt", "b.txt"}
    assert all(entry["status"] == "deleted" for entry in entries.values())
    assert inventory["totals"]["deleted"] == 2


# ── stage 3: progress CAS, materialize + probe, dirty env refusal ───────────


def test_stage3_materialize_probe_checked_mark(world, mcp_home, prepared):
    sc = world.scenarios["main"]
    sid = prepared.open["snapshot_id"]
    ok, created = mcp_home.call("mr_review_materialize", {
        "repo": sc.repo, "gitlab_url": world.gitlab.base_url,
        "target_project_id": sc.pid, "action": "create",
        "environment_id": "", "purpose": "e2e probe", "fresh": False,
        "snapshot_id": sid, "home": str(world.home_reviews)})
    assert ok, created
    env_id = created["environment_id"]
    assert created["status"] == "clean"
    assert created["sha"] == sc.shas["head"]
    assert Path(created["path"]).is_dir()

    ok, status = mcp_home.call("mr_review_materialize", {
        "repo": sc.repo, "gitlab_url": world.gitlab.base_url,
        "target_project_id": sc.pid, "action": "status",
        "environment_id": env_id, "purpose": "", "fresh": False,
        "snapshot_id": "", "home": str(world.home_reviews)})
    assert ok, status
    assert status["status"] == "clean"

    store = MrReviewStore(world.gitlab.base_url, sc.pid, sc.repo,
                          home=world.home_reviews)
    probe = mr_review_probe(
        store, env_id,
        [sys.executable, "-c",
         "import sys; print('probe-out'); "
         "sys.stderr.write('probe-err-token\\n')"],
        test_count=1, timeout=120)
    assert probe["exit_code"] == 0
    assert probe["source_sha"] == sc.shas["head"]
    assert probe["tracked_diff_digest"] == probe["tracked_diff_digest_before"]
    stdout_path = store.root / probe["log_paths"]["stdout"]
    stderr_path = store.root / probe["log_paths"]["stderr"]
    assert "probe-out" in stdout_path.read_text(encoding="utf-8")
    assert "probe-err-token" in stderr_path.read_text(encoding="utf-8")
    probe_id = stdout_path.name.split(".")[0]

    ok, refused = mcp_home.call("mr_review_progress", {
        "repo": sc.repo, "iid": sc.iid, "action": "mark",
        "text": json.dumps({"path": "src/config.py", "state": "checked"}),
        "expected_revision": 0, "home": str(world.home_reviews)})
    assert not ok
    assert refused["error"]["code"] == "invalid-schema"

    ok, marked = mcp_home.call("mr_review_progress", {
        "repo": sc.repo, "iid": sc.iid, "action": "mark",
        "text": json.dumps({
            "path": "src/config.py", "state": "checked",
            "evidence": [f"probe:{env_id}:{probe_id}", f"snapshot:{sid}"]}),
        "expected_revision": 0, "home": str(world.home_reviews)})
    assert ok, marked
    assert marked["status"] == "marked"
    assert marked["revision"] == 1


def test_stage3_concurrent_progress_writers_one_revision_conflict(
        world, prepared):
    sc = world.scenarios["main"]
    results: list[tuple[bool, dict]] = []
    barrier = threading.Barrier(2, timeout=120)

    def writer():
        proc = _McpProcess(world.home_env)
        proc.notify_initialized()
        try:
            barrier.wait()
            results.append(proc.call("mr_review_progress", {
                "repo": sc.repo, "iid": sc.iid, "action": "mark",
                "text": json.dumps({"path": "src/new.py",
                                    "state": "delivered"}),
                "expected_revision": 0,
                "home": str(world.home_reviews)}))
        finally:
            proc.close()

    threads = [threading.Thread(target=writer) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=180)
    assert len(results) == 2
    oks = sorted(ok for ok, _ in results)
    assert oks == [False, True]
    loser = [data for ok, data in results if not ok][0]
    assert loser["error"]["code"] == "revision-conflict"
    winner = [data for ok, data in results if ok][0]
    assert winner["revision"] == 1


def test_stage3_dirty_env_release_refused_typed(world, mcp_home, prepared):
    sc = world.scenarios["main"]
    sid = prepared.open["snapshot_id"]
    ok, created = mcp_home.call("mr_review_materialize", {
        "repo": sc.repo, "gitlab_url": world.gitlab.base_url,
        "target_project_id": sc.pid, "action": "create",
        "environment_id": "", "purpose": "e2e dirty", "fresh": True,
        "snapshot_id": sid, "home": str(world.home_reviews)})
    assert ok, created
    env_id = created["environment_id"]
    env_path = Path(created["path"])
    (env_path / "untracked-dirty.txt").write_text("dirty\n", encoding="utf-8")

    ok, status = mcp_home.call("mr_review_materialize", {
        "repo": sc.repo, "gitlab_url": world.gitlab.base_url,
        "target_project_id": sc.pid, "action": "status",
        "environment_id": env_id, "purpose": "", "fresh": False,
        "snapshot_id": "", "home": str(world.home_reviews)})
    assert ok, status
    assert status["status"] == "dirty"

    ok, refused = mcp_home.call("mr_review_materialize", {
        "repo": sc.repo, "gitlab_url": world.gitlab.base_url,
        "target_project_id": sc.pid, "action": "release",
        "environment_id": env_id, "purpose": "", "fresh": False,
        "snapshot_id": "", "home": str(world.home_reviews)})
    assert not ok
    assert refused["error"]["code"] == "incomplete-checkout"
    assert (env_path / "untracked-dirty.txt").is_file()
    assert env_path.is_dir()
