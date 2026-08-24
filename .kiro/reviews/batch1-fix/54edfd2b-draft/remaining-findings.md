# Remaining findings after Batch 1 DRAFT freeze

## Batch 1 scope

| ID | Status | Notes |
|---|---|---|
| REV-P0-001 | FIXED (pending independent close) | Admin hard boundary + OIDC APPROVED-only |
| REV-P1-001 | FIXED (pending independent close) | Boundary wrongly widened — restored |
| REV-P1-011 / REV-P1-012 | FIXED (pending independent close) | Required audit + DKE catch narrowed + tx evidence |
| REV-P2-011 | FIXED (pending independent close) | CAS / FOR UPDATE / idempotent re-approve |
| REV-P2-012 | FIXED (runbook text) | CoC files + manifest + dual sign; **cutover artifacts not executed** |
| REV-P2-013 | FIXED | Transaction class renamed `*Test` |
| REV-P2-014 | FIXED | Scoped cleanup |
| REV-P2-015 | FIXED | MySQL concurrent insert + FOR UPDATE re-read |
| REV-P1-010 | **OPEN / BLOCKED** | Real cutover evidence (7 files + manifest + signatures) not produced in this DRAFT |

## Outside Batch 1 (production NO-GO drivers)

| ID | Status | Notes |
|---|---|---|
| ACC-OPS-P2-003 | **OPEN** | Backup unit false-green (`common::trap_add`); **not** Batch 1; do not treat backup gate SUCCESS as closing this |
| ACC-OPS-P0-002 | **BLOCKED** | Production-topology backup restore |
| Batch 2 items | OPEN | SSRF / PII / mail / profile etc. |

## Production decision

**NO-GO** (unchanged). This DRAFT only freezes Batch 1 review input.
