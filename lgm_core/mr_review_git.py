"""Safe Git object access for the HOME MR review store.

Every command runs against one bare repository (the store's objects.git);
nothing here reads or writes a caller worktree. Unknown objects, unsafe
paths and unsafe refs raise typed MrReviewError instead of falling back to
disk. Path containment reimplements backend/app/core/path_safety.py
(lgm_core must not import backend).
"""
from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .mr_review_models import MrReviewError

GIT_TIMEOUT_SECONDS = 120
_READ_CHUNK = 1 << 16

_SHA_RE = re.compile(r"[0-9a-f]{40}")
_REF_RE = re.compile(r"refs/[A-Za-z0-9_][A-Za-z0-9_./-]*")
_DRIVE_RE = re.compile(r"[A-Za-z]:")


@dataclass(frozen=True)
class ObjectInfo:
    type: str
    size: int


@dataclass(frozen=True)
class TreeEntry:
    mode: str
    type: str
    oid: str


@dataclass(frozen=True)
class DiffEntry:
    status: str
    src_mode: str
    dst_mode: str
    src_sha: str
    dst_sha: str
    path: str


RENAME_LIMIT = 1000


@dataclass(frozen=True)
class RawDiffEntry:
    """One raw diff-tree record with byte-exact paths (non-UTF-8 tree paths
    survive as raw bytes; the reader encodes them for display)."""

    status: str
    src_mode: str
    dst_mode: str
    src_sha: str
    dst_sha: str
    old_path: bytes | None
    new_path: bytes | None


def _parse_raw_diff(stdout: bytes) -> list[RawDiffEntry]:
    tokens = stdout.split(b"\x00")
    entries: list[RawDiffEntry] = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        if not token.startswith(b":"):
            i += 1
            continue
        fields = token[1:].split(b" ")
        if len(fields) != 5:
            raise MrReviewError(
                "review-object-missing", f"unexpected git diff-tree record: {token[:80]!r}")
        status = fields[4].decode("ascii", "replace")
        if status.startswith(("R", "C")):
            if i + 2 >= len(tokens):
                raise MrReviewError(
                    "review-object-missing", f"truncated rename record: {token[:80]!r}")
            old_path = tokens[i + 1]
            new_path = tokens[i + 2]
            i += 3
        else:
            if i + 1 >= len(tokens):
                raise MrReviewError(
                    "review-object-missing", f"truncated diff record: {token[:80]!r}")
            path = tokens[i + 1]
            old_path = path if fields[0] != b"000000" else None
            new_path = path if fields[1] != b"000000" else None
            i += 2
        entries.append(RawDiffEntry(
            status=status,
            src_mode=fields[0].decode("ascii", "replace"),
            dst_mode=fields[1].decode("ascii", "replace"),
            src_sha=fields[2].decode("ascii", "replace"),
            dst_sha=fields[3].decode("ascii", "replace"),
            old_path=old_path,
            new_path=new_path))
    return entries


def validate_sha(sha: str) -> str:
    if not isinstance(sha, str) or not _SHA_RE.fullmatch(sha):
        raise MrReviewError("invalid-schema", f"expected a 40-char lowercase hex git SHA, got {sha!r}")
    return sha


def validate_ref(ref: str) -> str:
    if not isinstance(ref, str) or not _REF_RE.fullmatch(ref):
        raise MrReviewError("invalid-schema", f"expected a ref under refs/, got {ref!r}")
    if ".." in ref or ref.endswith((".lock", "/")):
        raise MrReviewError("invalid-schema", f"ref is not a valid git ref name: {ref!r}")
    parts = ref.split("/")
    if any(part in ("", ".", "-") or part.startswith(".") for part in parts):
        raise MrReviewError("invalid-schema", f"ref is not a valid git ref name: {ref!r}")
    return ref


def validate_tree_path(path: str) -> str:
    """Literal in-tree path: '/'-separated, never a filesystem path.

    Backslashes inside a path stay literal (git tree paths may contain them);
    they are never treated as separators because these paths are only ever
    passed to git as :(literal) pathspecs, never to the filesystem.
    """
    if not isinstance(path, str) or not path or path == ".":
        raise MrReviewError("invalid-schema", f"expected a non-empty tree path, got {path!r}")
    if "\x00" in path:
        raise MrReviewError("invalid-schema", "tree path contains a NUL byte")
    if path.startswith(("-", "/", "\\")):
        raise MrReviewError("invalid-schema", f"tree path must be relative and not option-like: {path!r}")
    if path.startswith(("//", "\\\\")) or _DRIVE_RE.fullmatch(path[:2]):
        raise MrReviewError("invalid-schema", f"tree path must not be absolute, UNC or a drive: {path!r}")
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise MrReviewError("invalid-schema", f"tree path has empty/'.'/'..' components: {path!r}")
    return path


def resolve_contained(base: Path, relative: str) -> Path:
    """Resolve base/relative and guarantee the result stays inside base.

    Rejects ``..`` traversal, absolute paths and drive/UNC overrides; resolves
    symlinks and junctions, so a link inside base cannot point outside it.
    """
    if not isinstance(relative, str) or not relative:
        raise MrReviewError("invalid-schema", f"expected a non-empty relative path, got {relative!r}")
    if "\x00" in relative:
        raise MrReviewError("invalid-schema", "relative path contains a NUL byte")
    if relative.startswith("-"):
        raise MrReviewError("invalid-schema", f"relative path must not be option-like: {relative!r}")
    if relative.startswith(("/", "\\")) or _DRIVE_RE.fullmatch(relative[:2]):
        raise MrReviewError("invalid-schema", f"relative path must not be absolute, UNC or a drive: {relative!r}")
    if any(part == ".." for part in re.split(r"[\\/]", relative)):
        raise MrReviewError("invalid-schema", f"relative path must not traverse upwards: {relative!r}")
    base_abs = base.resolve()
    resolved = (base_abs / relative).resolve()
    if resolved != base_abs and base_abs not in resolved.parents:
        raise MrReviewError("invalid-schema", f"path escapes {base_abs}: {relative!r}")
    return resolved


def init_bare(path: Path) -> None:
    if (path / "HEAD").exists():
        return
    path.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(["git", "init", "--bare", "-q", str(path)],
                          capture_output=True, text=True, timeout=GIT_TIMEOUT_SECONDS,
                          check=False)
    if proc.returncode != 0:
        raise MrReviewError("review-object-missing", f"git init --bare failed: {proc.stderr.strip()}")


def _check_file_arg(path: Path) -> Path:
    value = str(path)
    if value.startswith("-"):
        raise MrReviewError("invalid-schema", f"file argument must not be option-like: {value!r}")
    return path


class _CatFileBatch:
    """Persistent ``git cat-file --batch`` stream with bounded reads.

    Only ``length`` bytes of a record are ever pulled into memory; the rest
    of the record (plus its trailing LF terminator) is drained in chunks
    before the next request.
    """

    def __init__(self, git_dir: Path) -> None:
        self._git_dir = git_dir
        self._proc: subprocess.Popen[bytes] | None = None
        self._pending = 0

    def _ensure(self) -> subprocess.Popen[bytes]:
        if self._proc is None or self._proc.poll() is not None:
            self._proc = subprocess.Popen(
                ["git", "-C", str(self._git_dir), "cat-file", "--batch"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            self._pending = 0
        return self._proc

    def _drain(self, proc: subprocess.Popen[bytes]) -> None:
        stdout = proc.stdout
        assert stdout is not None
        while self._pending > 0:
            chunk = stdout.read(min(self._pending, _READ_CHUNK))
            if not chunk:
                raise MrReviewError(
                    "review-object-missing", "cat-file stream ended mid-object")
            self._pending -= len(chunk)

    def read(self, sha: str, offset: int, length: int) -> tuple[ObjectInfo, bytes]:
        if not isinstance(offset, int) or offset < 0:
            raise MrReviewError("invalid-schema", f"offset must be a non-negative integer, got {offset!r}")
        if not isinstance(length, int) or length < 0:
            raise MrReviewError("invalid-schema", f"length must be a non-negative integer, got {length!r}")
        proc = self._ensure()
        self._drain(proc)
        try:
            proc.stdin.write(f"{sha}\n".encode("ascii"))
            proc.stdin.flush()
        except OSError:
            raise MrReviewError("review-object-missing", f"cat-file pipe failed for {sha}") from None
        header = proc.stdout.readline()
        if not header:
            raise MrReviewError("review-object-missing", f"cat-file stream failed for {sha}")
        parts = header.decode("ascii", "replace").split()
        if len(parts) == 2 and parts[1] == "missing":
            raise MrReviewError("review-object-missing", f"object {sha} is not present in the review store")
        if len(parts) != 3 or not parts[2].isdigit():
            raise MrReviewError("review-object-missing", f"unexpected cat-file header for {sha}")
        size = int(parts[2])
        skip = min(offset, size)
        while skip > 0:
            chunk = proc.stdout.read(min(skip, _READ_CHUNK))
            if not chunk:
                raise MrReviewError("review-object-missing", f"cat-file stream ended in {sha}")
            skip -= len(chunk)
        want = min(length, size - min(offset, size))
        data = proc.stdout.read(want) if want else b""
        self._pending = size - min(offset, size) - want + 1
        return ObjectInfo(parts[1], size), data

    def close(self) -> None:
        proc = self._proc
        self._proc = None
        self._pending = 0
        if proc is not None:
            try:
                if proc.stdin is not None:
                    proc.stdin.close()
                proc.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                proc.kill()
                proc.wait()


class MrReviewGit:
    """Object/blob/diff primitives bound to one bare repository.

    Bundle ingest fetches explicit tip refs into a per-snapshot namespace
    under refs/mr-review/; ref writes are rejected outside that namespace,
    so ordinary refs can never be touched. Reads never fall back to any
    worktree or filesystem path: unknown SHAs raise
    ``review-object-missing``.
    """

    def __init__(self, objects_git: Path) -> None:
        self._git_dir = Path(objects_git)
        self._batch: _CatFileBatch | None = None

    @property
    def git_dir(self) -> Path:
        return self._git_dir

    def _run(self, *args: str, timeout: int = GIT_TIMEOUT_SECONDS) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(self._git_dir), *args],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, check=False)

    def close(self) -> None:
        if self._batch is not None:
            self._batch.close()
            self._batch = None


    def bundle_heads(self, bundle_path: Path) -> list[tuple[str, str]]:
        proc = self._run("bundle", "list-heads", str(_check_file_arg(bundle_path)))
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing", f"git bundle list-heads failed: {proc.stderr.strip()}")
        heads: list[tuple[str, str]] = []
        for line in proc.stdout.splitlines():
            parts = line.split()
            if len(parts) != 2:
                continue
            sha, ref = parts
            if _SHA_RE.fullmatch(sha) and (ref == "HEAD" or _REF_RE.fullmatch(ref)):
                heads.append((sha, ref))
        return heads

    def verify_bundle(self, bundle_path: Path) -> None:
        """Fail with review-object-missing when prerequisites are absent."""
        proc = self._run("bundle", "verify", str(_check_file_arg(bundle_path)))
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout).strip().splitlines()
            reason = detail[-1] if detail else f"exit {proc.returncode}"
            raise MrReviewError("review-object-missing", f"git bundle verify failed: {reason}")

    def fetch_bundle(self, bundle_path: Path, namespace: str, refs: list[str]) -> None:
        """Fetch the given bundle refs into refs/mr-review/<sid>/bundle/<ref>.

        The destination namespace must live under refs/mr-review/, so no ref
        outside the review namespace can be created or moved by bundle
        content.
        """
        validate_ref(namespace)
        if not namespace.startswith("refs/mr-review/"):
            raise MrReviewError(
                "invalid-schema", f"bundle namespace must be under refs/mr-review/: {namespace!r}")
        bundle = _check_file_arg(bundle_path)
        if not bundle.is_file():
            raise MrReviewError("review-object-missing", f"bundle file not found: {bundle}")
        refspecs: list[str] = []
        for ref in refs:
            if ref != "HEAD":
                validate_ref(ref)
            refspecs.append(f"+{ref}:{namespace}/bundle/{ref}")
        if not refspecs:
            return
        proc = self._run("fetch", "--force", "--no-tags", str(bundle), *refspecs)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing", f"git fetch from bundle failed: {proc.stderr.strip()}")


    def update_ref(self, ref: str, sha: str) -> None:
        validate_ref(ref)
        if not ref.startswith("refs/mr-review/"):
            raise MrReviewError(
                "invalid-schema", f"review store refs must be under refs/mr-review/: {ref!r}")
        validate_sha(sha)
        if sha == "0" * 40:
            raise MrReviewError(
                "invalid-schema", "the all-zero SHA is not a valid ref target")
        proc = self._run("update-ref", "--", ref, sha)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing",
                f"git update-ref {ref} failed: {proc.stderr.strip()}")

    def ref_value(self, ref: str) -> str | None:
        validate_ref(ref)
        proc = self._run("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
        if proc.returncode != 0:
            return None
        sha = proc.stdout.strip()
        return sha if _SHA_RE.fullmatch(sha) else None

    def list_refs(self, prefix: str = "refs/") -> dict[str, str]:
        if not isinstance(prefix, str) or not prefix.startswith("refs/") or prefix.startswith("-"):
            raise MrReviewError("invalid-schema", f"invalid ref prefix: {prefix!r}")
        proc = self._run("for-each-ref", "--format=%(objectname) %(refname)", prefix)
        if proc.returncode != 0:
            raise MrReviewError("review-object-missing", f"git for-each-ref failed: {proc.stderr.strip()}")
        out: dict[str, str] = {}
        for line in proc.stdout.splitlines():
            parts = line.split()
            if len(parts) == 2 and _SHA_RE.fullmatch(parts[0]):
                out[parts[1]] = parts[0]
        return out


    def object_info(self, sha: str) -> ObjectInfo:
        validate_sha(sha)
        if self._batch is None:
            self._batch = _CatFileBatch(self._git_dir)
        info, _ = self._batch.read(sha, 0, 0)
        return info

    def object_exists(self, sha: str) -> bool:
        try:
            self.object_info(sha)
        except MrReviewError:
            return False
        return True

    def read_blob(self, sha: str, offset: int = 0, length: int = _READ_CHUNK) -> tuple[ObjectInfo, bytes]:
        """Bounded read of blob content: at most ``length`` bytes from
        ``offset``; empty when the offset is past the end of the blob."""
        validate_sha(sha)
        if self._batch is None:
            self._batch = _CatFileBatch(self._git_dir)
        info, data = self._batch.read(sha, offset, length)
        if info.type != "blob":
            raise MrReviewError(
                "review-object-missing", f"object {sha} is {info.type}, not a blob")
        return info, data


    def ls_tree(self, commit_sha: str, path: str) -> TreeEntry | None:
        validate_sha(commit_sha)
        validate_tree_path(path)
        proc = self._run("ls-tree", "-z", commit_sha, "--", f":(literal){path}")
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing", f"git ls-tree failed: {proc.stderr.strip()}")
        for record in proc.stdout.split("\x00"):
            if not record:
                continue
            meta, _, found_path = record.partition("\t")
            if found_path != path:
                continue
            fields = meta.split(" ")
            if len(fields) == 3 and _SHA_RE.fullmatch(fields[2]):
                return TreeEntry(mode=fields[0], type=fields[1], oid=fields[2])
        return None

    def diff_tree(self, base_sha: str, head_sha: str) -> list[DiffEntry]:
        """Raw recursive diff entries between two commits, no rename
        detection (rename heuristics belong to the reader)."""
        validate_sha(base_sha)
        validate_sha(head_sha)
        proc = self._run("diff-tree", "-r", "-z", "--raw", "--no-renames", base_sha, head_sha)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing", f"git diff-tree failed: {proc.stderr.strip()}")
        tokens = proc.stdout.split("\x00")
        entries: list[DiffEntry] = []
        i = 0
        while i < len(tokens):
            token = tokens[i]
            if not token.startswith(":"):
                i += 1
                continue
            fields = token[1:].split(" ")
            if len(fields) != 5:
                i += 1
                continue
            path = tokens[i + 1] if i + 1 < len(tokens) else ""
            entries.append(DiffEntry(
                status=fields[4], src_mode=fields[0], dst_mode=fields[1],
                src_sha=fields[2], dst_sha=fields[3], path=path))
            i += 2
        return entries

    def merge_bases(self, a_sha: str, b_sha: str) -> list[str]:
        validate_sha(a_sha)
        validate_sha(b_sha)
        proc = self._run("merge-base", "--all", a_sha, b_sha)
        if proc.returncode != 0:
            if not proc.stdout.strip():
                return []
            raise MrReviewError(
                "review-object-missing", f"git merge-base failed: {proc.stderr.strip()}")
        return [sha for sha in proc.stdout.split() if _SHA_RE.fullmatch(sha)]

    def _run_bytes(self, *args: str,
                   timeout: int = GIT_TIMEOUT_SECONDS) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(self._git_dir), *args],
                              capture_output=True, timeout=timeout, check=False)

    def diff_tree_entries(self, base_sha: str, head_sha: str, *,
                          renames: bool = False) -> tuple[list[RawDiffEntry], bool]:
        """Raw recursive diff records with byte-exact paths. ``renames=True``
        adds ``-M -l1000`` similarity detection; the returned flag is True when
        git reported that rename detection was skipped past the rename limit
        (exact OID matches still pair, similarity search does not)."""
        validate_sha(base_sha)
        validate_sha(head_sha)
        args = ["diff-tree", "-r", "-z", "--raw"]
        args += ["-M", f"-l{RENAME_LIMIT}"] if renames else ["--no-renames"]
        proc = self._run_bytes(*args, base_sha, head_sha)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing",
                f"git diff-tree failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
        limited = b"rename detection was skipped" in proc.stderr
        return _parse_raw_diff(proc.stdout), limited

    def ls_tree_long(self, commit_sha: str) -> dict[bytes, tuple[str, str, str, int | None]]:
        """Full recursive tree listing with blob sizes: raw path bytes ->
        (mode, type, oid, size). Size is None for gitlinks (git prints '-')."""
        validate_sha(commit_sha)
        proc = self._run_bytes("ls-tree", "-r", "-l", "-z", commit_sha)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing",
                f"git ls-tree failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
        out: dict[bytes, tuple[str, str, str, int | None]] = {}
        for record in proc.stdout.split(b"\x00"):
            if not record:
                continue
            meta, _, path = record.partition(b"\t")
            fields = meta.split()
            if len(fields) != 4 or not _SHA_RE.fullmatch(fields[2].decode("ascii", "replace")):
                raise MrReviewError(
                    "review-object-missing", f"unexpected git ls-tree record: {record[:80]!r}")
            size = None if fields[3] == b"-" else int(fields[3].decode("ascii"))
            out[path] = (fields[0].decode("ascii", "replace"),
                         fields[1].decode("ascii", "replace"),
                         fields[2].decode("ascii"),
                         size)
        return out

    def diff_numstat(self, base_sha: str, head_sha: str) -> tuple[
            dict[bytes, tuple[int | None, int | None]],
            dict[bytes, tuple[int | None, int | None, bytes]]]:
        """Per-file line counts from ``git diff --numstat -z -M``: plain paths
        -> (added, deleted) and rename new-paths -> (added, deleted, old_path).
        Binary and gitlink files carry None counts (git prints '-')."""
        validate_sha(base_sha)
        validate_sha(head_sha)
        proc = self._run_bytes("diff", "--numstat", "-z", "-M", f"-l{RENAME_LIMIT}",
                               base_sha, head_sha)
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing",
                f"git diff --numstat failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
        by_path: dict[bytes, tuple[int | None, int | None]] = {}
        renames: dict[bytes, tuple[int | None, int | None, bytes]] = {}
        tokens = proc.stdout.split(b"\x00")
        try:
            i = 0
            while i < len(tokens):
                token = tokens[i]
                if not token:
                    i += 1
                    continue
                parts = token.split(b"\t", 2)
                if len(parts) != 3:
                    raise ValueError(token[:80])
                added = None if parts[0] == b"-" else int(parts[0].decode("ascii"))
                deleted = None if parts[1] == b"-" else int(parts[1].decode("ascii"))
                if parts[2] == b"":
                    if i + 2 >= len(tokens):
                        raise ValueError(token[:80])
                    renames[tokens[i + 2]] = (added, deleted, tokens[i + 1])
                    i += 3
                else:
                    by_path[parts[2]] = (added, deleted)
                    i += 1
        except ValueError as exc:
            raise MrReviewError(
                "review-object-missing", f"unexpected git numstat record: {exc!r}") from None
        return by_path, renames

    def open_diff_patch(self, base_sha: str, head_sha: str,
                        context_lines: int) -> subprocess.Popen:
        """Streamed unified diff between two commits: bytes stdout, read
        incrementally by the caller; the full patch is never materialized.
        Flags pin quoting, algorithm and rename detection so output is
        deterministic regardless of user git config."""
        validate_sha(base_sha)
        validate_sha(head_sha)
        if not isinstance(context_lines, int) or isinstance(context_lines, bool) \
                or context_lines < 0:
            raise MrReviewError(
                "invalid-schema", f"context_lines must be a non-negative integer, got {context_lines!r}")
        args = ["-c", "core.quotepath=true", "diff", "--no-color", "--no-ext-diff",
                "--no-textconv", "--full-index", "--diff-algorithm=myers",
                "-M", f"-l{RENAME_LIMIT}", f"-U{context_lines}", base_sha, head_sha]
        return subprocess.Popen(["git", "-C", str(self._git_dir), *args],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def open_blob_stream(self, sha: str) -> subprocess.Popen:
        """Streamed blob content: bytes stdout read incrementally by the
        caller; the blob is never fully materialized by this call."""
        validate_sha(sha)
        return subprocess.Popen(["git", "-C", str(self._git_dir), "cat-file", "blob", sha],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
