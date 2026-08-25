# PITR Evidence Freeze — Production Acceptance Run 2

**Frozen commit:** `54edfd2b08f5fd61095b3a94c33bd5b981935c28`  
**Run:** verify-like-ci.ps1 Run 2 (2026-08-24 12:48–13:34 JST)  
**Source log:** `verify-like-ci-rerun-full.log` (lines ~24568–24708)

---

## Artifact Integrity Verdict

| Check | Status |
|---|---|
| Integration suite execution | **VERIFIED PASS** (log: `integration suite SUCCESS（skip 0・全ステップ実実行）`) |
| **Source evidence files on disk** | **YES** — `ops/backup/tests/.integration-work/evidence/` (12 files from Run 2) |
| **Archived into acceptance package** | **YES** — `.kiro/reviews/production-acceptance/54edfd2b/pitr-evidence/` |
| SHA256 manifest (all archived files) | **YES** — `pitr-evidence/manifest-sha256.txt` |
| `mysql:8.0.36` image digest | **BLOCKED** — not emitted in Run 2 log (cached compose pull) |

### **Overall**

| Layer | Verdict |
|---|---|
| File archive integrity | **VERIFIED** (12 files + `manifest-sha256.txt`) |
| MySQL `8.0.36` image digest | **BLOCKED** |
| Production topology restore | **BLOCKED** (ACC-OPS-P0-002) |

---

## Archived Files (12)

| File | SHA256 (package copy) |
|---|---|
| archiver.log | `1212507cbcd066017a52e5b8527862f1a05550d7578a00cd6d62b9032d8054a4` |
| backup-full.log | `02d57b10600dc847f70a4e13bcb82c50616962b4a5dd1d3f0189904818c88f13` |
| checkpoint.log | `31add27268498804eb85ce16e66416388249afe02c942d50b4b61e309a0acec4` |
| drill-report.json | `7cbb05393f584478dac386fea99c4189bf858556ff152f53af098fdaec735eda` |
| evidence-sha.txt | `43f75ede6da36024088a1613f0685126eb478a18a8f93e79cefd47b685af456` |
| integration-summary.json | `73b45d90ca829f7e92cc474b9fb9d0611f0d64de7845a4e35d831ebfe4479e3f` |
| preflight.json | `4c76ed7c75b7cc2d7f4b44cdd93adaa81432ebecd7c81a830093b299f61b39a9` |
| restore.log | `29df4e2ed99bcb8c75e380f8f0f40d49c975cc31d230d33e96d0cd895a60eef2` |
| source-state.txt | `fc7315cd012cb25d7ab97e85519586bb1696963e944c2035205bc11b3c89f913` |
| target-markers.txt | `882206ab345b62bcacb119186743cf57fd79687fe50812a687d95fb0f3a49062` |
| uploads-markers.txt | `f483a04448d97b84bb9d194f21dd97632896ed411a68302fdfd18ed44747dbbe` |
| validate.json | `565eb77bb61bfe3f108060395511f675e338d8dfd852dd60fd3e7b8acbc7e123` |

**Note:** Run-time `evidence-sha.txt` covers 6 JSON/log files only; package `manifest-sha256.txt` covers **all 12** archived files.

**Source path (ephemeral, may be overwritten on next integration run):** `ops/backup/tests/.integration-work/evidence/`

---

## Integration Outcome (log snapshot)

**plan_id:** `7e499d7b5da78f38` · **target_ts:** `2026-08-24T04:34:27Z` · **validation:** `READY_FOR_CUTOVER`

See `pitr-evidence/integration-summary.json` and `pitr-evidence/drill-report.json`.

---

## Tool / Base Image Digests (from Run 2 log)

| Image | Digest / reference |
|---|---|
| docker/dockerfile:1 | `sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32` |
| debian:bookworm-slim | `sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241` |
| ses-backup-tool:test (manifest list) | `sha256:f2a9c2aa9ca738a8b651b1b9b97b30ba109e246d57e342044173d304fe0fe458` |
| ses-backup-tool:integration (manifest list) | `sha256:4ffc66601a6de06bcf6d299e3a38ea5ff405e3c2526e970f3749f340fb7867d2` |
| mysql:8.0.36 (compose pin) | **digest BLOCKED** — tag-only in `docker-compose.integration.yml` |

---

## Related Gate Finding

Backup **unit** gate false-green: **ACC-OPS-P2-003** — 464 count is shell `assert_*` checks; `common::trap_add` missing when `mysql-options.sh` sourced without `common.sh` (Run 2 log ~24414).
