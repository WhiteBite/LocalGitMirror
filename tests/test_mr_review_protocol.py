"""Kotlin<->Python parity contract for the snapshot-bound MR review protocol:
one shared fixture directory (tests/fixtures/mr_review_protocol/), two runners -
this file and idea-plugin MrReviewProtocolTest.kt. Spec: docs/MR_REVIEW_PROTOCOL.md."""
import copy
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import mr_review_models as m
from lgm_core.mr_review_models import MrReviewError

FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "mr_review_protocol"

EXPECTED_FIXTURES = {
    "snapshot_non_default_target", "snapshot_fork_mr", "snapshot_deletion",
    "snapshot_rename", "snapshot_unicode_null", "finding_stale", "batch_v2",
    "receipt_recovery", "canonical_probe",
    "invalid_tampered_snapshot_id", "invalid_side", "invalid_negative_line",
    "invalid_unknown_schema_version", "invalid_diff_not_ready",
}

VALIDATORS = {
    "snapshot": m.verify_snapshot,
    "finding": m.validate_finding,
    "batch": m.validate_batch,
    "receipt": m.validate_receipt,
}


def _fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))


def _by_expect(expectation: str) -> list[dict]:
    out = []
    for path in sorted(FIXTURE_DIR.glob("*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        if fixture["expect"] == expectation:
            out.append(fixture)
    return out


def _identity_triple(fixture: dict) -> dict:
    obj = fixture["object"]
    kind = fixture["object_kind"]
    if kind == "snapshot":
        return m.snapshot_digests(obj)
    if kind == "finding":
        rev = m.finding_latest_revision(obj)
    elif kind == "batch":
        rev = m.batch_identity(obj)
        return rev
    elif kind == "receipt":
        rev = obj
    else:
        raise AssertionError(f"unexpected object_kind {kind}")
    return {key: rev[key] for key in ("snapshot_id", "code_id", "version_fingerprint")}


def test_fixture_directory_is_complete():
    assert {p.stem for p in FIXTURE_DIR.glob("*.json")} == EXPECTED_FIXTURES


@pytest.mark.parametrize("fixture", _by_expect("valid"), ids=lambda f: f["fixture"])
def test_valid_fixtures_pass_and_digests_match(fixture):
    if fixture["object_kind"] == "probe":
        return
    validated = VALIDATORS[fixture["object_kind"]](fixture["object"])
    assert validated is not None
    assert _identity_triple(fixture) == fixture["expected_digests"]


@pytest.mark.parametrize("fixture", _by_expect("invalid"), ids=lambda f: f["fixture"])
def test_invalid_fixtures_rejected_with_expected_code(fixture):
    with pytest.raises(MrReviewError) as excinfo:
        VALIDATORS[fixture["object_kind"]](fixture["object"])
    assert excinfo.value.code == fixture["expected_error"]


def test_tampered_hash_is_structurally_valid_but_rejected_by_digest_gate():
    fixture = _fixture("invalid_tampered_snapshot_id")
    m.validate_snapshot(fixture["object"])
    with pytest.raises(MrReviewError) as excinfo:
        m.verify_snapshot(fixture["object"])
    assert excinfo.value.code == "hash-mismatch"


def test_stale_finding_detected_against_newer_snapshot():
    fixture = _fixture("finding_stale")
    finding = m.validate_finding(fixture["object"])
    context = m.verify_snapshot(fixture["context_snapshot"])
    assert m.snapshot_digests(context) == fixture["expected_digests"]
    assert m.finding_stale_against(finding, context) is True

    fresh = copy.deepcopy(finding)
    for key, value in fixture["expected_digests"].items():
        m.finding_latest_revision(fresh)[key] = value
    assert m.finding_stale_against(fresh, context) is False


def test_empty_string_diff_refs_are_diff_not_ready():
    fixture = copy.deepcopy(_fixture("invalid_diff_not_ready"))
    fixture["object"]["gitlab_diff_refs"] = {
        "base_sha": "", "start_sha": "", "head_sha": ""}
    with pytest.raises(MrReviewError) as excinfo:
        m.verify_snapshot(fixture["object"])
    assert excinfo.value.code == "diff-not-ready"


@pytest.mark.parametrize("fixture", _by_expect("valid") + _by_expect("invalid"),
                         ids=lambda f: f["fixture"])
def test_canonical_json_matches_stdlib_dumps(fixture):
    if fixture["object_kind"] == "probe":
        return
    obj = fixture["object"]
    assert m.canonical_json(obj) == json.dumps(
        obj, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def test_canonical_probe_is_byte_identical():
    fixture = _fixture("canonical_probe")
    assert m.canonical_json(fixture["object"]) == fixture["expected_canonical"]


def test_canonical_json_rejects_floats_and_non_ascii_keys():
    with pytest.raises(MrReviewError) as excinfo:
        m.canonical_json({"a": 1.5})
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError):
        m.canonical_json({"ключ": 1})


def test_receipt_url_is_the_note_permalink():
    receipt = _fixture("receipt_recovery")["object"]
    assert receipt["url"] == m.receipt_note_url(receipt["mr_url"], receipt["note_id"])
    assert receipt["url"].endswith(f"#note_{receipt['note_id']}")


def test_error_registry_is_closed_and_kebab_case():
    assert m.ERROR_CODES == frozenset({
        "snapshot-raced", "diff-not-ready", "review-object-missing",
        "review-package-too-large", "stale-snapshot", "possible-duplicate",
        "incomplete-checkout", "invalid-schema", "unknown-schema-version",
        "invalid-side", "invalid-line", "hash-mismatch", "invalid-marker",
        "invalid-budget",
    })
    assert all(code == code.lower() and " " not in code for code in m.ERROR_CODES)
    with pytest.raises(ValueError):
        MrReviewError("not-in-registry", "x")


def test_marker_roundtrip_and_rejections():
    batch_id = "9f1e8d7c-6b5a-4938-2716-05f4e3d2c1b0"
    finding_id = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
    marker = m.review_marker(batch_id, finding_id, 2)
    assert marker == f"lgm-review:{batch_id}:{finding_id}:2"
    found = m.parse_review_markers(f"body text <!-- {marker} --> tail")
    assert found == [(batch_id, finding_id, 2)]
    assert m.parse_review_markers("no markers here") == []
    with pytest.raises(MrReviewError) as excinfo:
        m.review_marker(batch_id.upper(), finding_id, 2)
    assert excinfo.value.code == "invalid-marker"
    with pytest.raises(MrReviewError) as excinfo:
        m.review_marker(batch_id, finding_id, 0)
    assert excinfo.value.code == "invalid-marker"


def test_artifact_paths():
    batch_id = "9f1e8d7c-6b5a-4938-2716-05f4e3d2c1b0"
    code_id = _fixture("snapshot_non_default_target")["expected_digests"]["code_id"]
    snapshot_id = _fixture("snapshot_non_default_target")["expected_digests"]["snapshot_id"]
    assert m.batch_artifact_path(30, batch_id) == \
        f"mr-replies/mr-!30-{batch_id}.review.json"
    assert m.code_artifact_path(code_id) == f"mr-review/code/{code_id}.bundle"
    assert m.snapshot_artifact_path(snapshot_id) == \
        f"mr-review/snapshots/{snapshot_id}.json"
    with pytest.raises(MrReviewError) as excinfo:
        m.code_artifact_path("nothex")
    assert excinfo.value.code == "invalid-schema"


def test_budget_bounds_and_measurement():
    assert m.validate_budget(m.BUDGET_BYTES_MIN) == 4096
    assert m.validate_budget(m.BUDGET_BYTES_MAX) == 48000
    for bad in (4095, 48001, 0, -1, True, "24000", 24000.0, None):
        with pytest.raises(MrReviewError) as excinfo:
            m.validate_budget(bad)
        assert excinfo.value.code == "invalid-budget"

    payload = {"success": True, "data": {"строки": ["a" * 100, None, 42]}}
    text = json.dumps(payload, ensure_ascii=False, indent=2, default=str)
    envelope = {"content": [{"type": "text", "text": text}]}
    expected = len(json.dumps(envelope, ensure_ascii=False).encode("utf-8"))
    assert m.mcp_result_bytes(payload) == expected
    error_envelope = {"isError": True, "content": [{"type": "text", "text": text}]}
    expected_error = len(json.dumps(error_envelope, ensure_ascii=False).encode("utf-8"))
    assert m.mcp_result_bytes(payload, is_error=True) == expected_error
    assert m.mcp_result_bytes(payload, is_error=True) > m.mcp_result_bytes(payload)
    assert m.mcp_result_bytes({}) < m.mcp_result_bytes(payload)
