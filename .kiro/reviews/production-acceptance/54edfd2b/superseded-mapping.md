# SUPERSEDED Finding Mapping

**Ledger:** `findings.csv` · **Rules:** `severity-rubric.md`  
**Severity column is authoritative.** ID prefixes (P0/P1/P2/P3 in the ID) are historical labels and may differ after re-rate.

| Finding ID | Status | Replacement ID | Coverage assessment |
|---|---|---|---|
| ACC-REQ-P0-003 | SUPERSEDED | ACC-REQ-P1-002 | Stale `tasks.md` is traceability P1 not a GO-blocker; ID was P0-named |
| ACC-ARCH-P1-002 | WITHDRAWN | REV-P2-001 | False positive — `uk_contract_active_proposal` + `FOR UPDATE` |
| ACC-ARCH-P2-002 | WITHDRAWN | — | No checked-exception `throws` on WorkRecord/BpPayment; default `@Transactional` rolls back RuntimeException |
| ACC-TEST-P1-003 | SUPERSEDED | ACC-SEC-P1-001 | **Full** — same CVE/Dependabot/Dependency-Check gap and Required Test |
| ACC-OPS-P1-002 | SUPERSEDED | ACC-REQ-P0-002 | **Full** — CloudSign sandbox GO gate |
| ACC-OPS-P1-003 | SUPERSEDED | ACC-REQ-P0-001 | **Full** — freee payroll sandbox GO gate |
| ACC-OPS-P1-004 | SUPERSEDED | REV-P1-005 | **Full** — SMTP DRY_RUN / body-log |
| ACC-OPS-P1-005 | SUPERSEDED | ACC-OPS-P0-003 | **Full after expansion** — Gemini mock + G10 unsigned + PII/AI canary/external-send |
| ACC-DB-P3-002 | WITHDRAWN | V108_1 | Seed exists `V108_1:15-23` |

## ACC-OPS-P0-003 scope (narrowed)

**In scope:** Gemini/`ai.provider=mock`, G10/`GATE-S17-G10-PROD` unsigned, AI PII canary + external-send off, remaining webhook mock ops.  
**Out of scope (do not double-count):** freee → ACC-REQ-P0-001; CloudSign → ACC-REQ-P0-002; SMTP DRY_RUN → REV-P1-005; webhook SSRF code path → REV-P1-002; AI canary code skip → REV-P1-004.

## Restored / kept OPEN (replacement insufficient)

| Finding ID | Note |
|---|---|
| ACC-TEST-P1-002 | `mapper/**` ≠ `config/**` |
| ACC-OPS-P1-001 | Metrics ≠ health probe |
| ACC-TEST-P1-004 / P1-005 | Flake / CDP tagging ≠ JaCoCo |
| ACC-OPS-P1-006 | Session scale ≠ health |
| ACC-TEST-P2-001..005 | Distinct from REV-P2-002 artifact |

## Residual overlap (not merged)

| Pair | Why both remain |
|---|---|
| ACC-TEST-P2-001 vs ACC-DB-P2-004 | Flyway vs H2 init subset ≠ dual `engineer-schema-h2.sql` maintenance path |
| ACC-SEC-P1-003 vs ACC-OPS-P0-003 | `ai.api-url` SSRF class ≠ G10/mock send gate |
| REV-P1-004 vs ACC-OPS-P0-003 | Ingestion canary skip in code ≠ prod send unauthorized |
