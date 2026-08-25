# Independent Review — Production Acceptance Evidence Package

**Reviewed package:** `.kiro/reviews/production-acceptance/54edfd2b/`  
**Frozen commit (verified = HEAD):** `54edfd2b08f5fd61095b3a94c33bd5b981935c28`  
**Review date:** 2026-08-24 JST (1st pass) · **Revision 3 package:** severity rubric + 55-row ledger  
**Mode:** Read-only — no source / test / config / migration changes  
**Independent decision (1st pass):** **NO-GO**

---

## Summary

Automated gates at this commit are **VERIFIED PASS** (Fast / MySQL / Performance / Browser / Backup PITR).  
However, the original acceptance package **missed a P0 account-takeover path** (OIDC identity binding), understated several security issues, and had **findings ledger defects** (corrected in revision 3 — pending 3rd independent confirmation).

---

## REOPENED

| ID | Sev | Title | Status vs original ACC |
|---|---|---|---|
| REV-P0-001 | P0 | HR/マネージャー can bind OIDC subject to admin user | **Missed** — contradicts “no P0 security path” |
| REV-P1-001 | P1 | Admin hard-boundary matcher wrongly widened | Missed |
| REV-P1-002 | P1 | Editable webhook URL → blind SSRF | Partially covered as ACC-SEC AI URL only; webhook missed |
| REV-P1-003 | P1 | Browser Gemini API key sent to server | Missed |
| REV-P1-004 | P1 | AI ingestion bypasses PII canary | Overstated “PII architecture reliable” |
| REV-P1-005 | P1 | Mail DRY_RUN logs full body at INFO | Known MOCK/DRY_RUN; severity understated |
| REV-P1-006 | P1 | Missing prod profile → boot in dev security mode | Missed |
| REV-P1-007 | P1 | findings.csv / final-report counts unauditable | Evidence package defect |
| REV-P1-008 | P1 | Browser tests rewrite tracked `.kiro/specs/**/evidence` | Missed |
| REV-P2-001 | P2 | ACC-ARCH-P1-002 duplicate-contract claim is false | **REOPENED as false positive** — unique key + FOR UPDATE exist |
| REV-P2-002 | P2 | Test-quality PASS / JaCoCo 73%/55% lack preserved artifacts | Overstated PASS |

### REV-P0-001 evidence (spot-check reconfirmed by acceptance lead)

| Claim | Evidence |
|---|---|
| Matcher allows HR/マネージャー on identity API | `SecurityConfig.java:143–169` — `/api/identity-providers/**` in `hasAnyRole("管理者", "HR", "マネージャー")` |
| Action seeded for non-admin | `V66_1__close_security_review_boundaries.sql:24` — `identity-provider.*` for role-sales/hr/manager |
| No menu → menu filter pass-through | `MenuPermissionFilter.java:121–123` |
| No method-level admin guard | `ExternalIdentityApiController.java:15–27` |
| Binds subject to arbitrary active user | `ExternalIdentityProvisioningServiceImpl.java:35–49` |
| OIDC login uses linked user role | `OidcLoginUserService.java` (reviewer cite L88) |

**Impact:** Account takeover of 管理者 via controlled IdP subject. Elevates acceptance security verdict from “no P0” to **P0 OPEN**.

### REV-P2-001 (false positive correction)

| Claim in ACC-ARCH-P1-002 | Actual |
|---|---|
| No unique on proposal_id | **False** — `V18__add_active_relation_unique_keys.sql:31–43` creates `active_proposal_id` + `uk_contract_active_proposal` |
| Duplicate active contracts possible | **False** for DB persistence; race may surface as unique-constraint exception |
| Proposal path unlocked | **False** — `ProposalServiceImpl` uses `FOR UPDATE` before conversion |

**Disposition:** ACC-ARCH-P1-002 → **WITHDRAWN** (see findings.csv). Residual: add MySQL concurrency test for normalized error handling — P2 only.

---

## VERIFIED

| Item | Evidence |
|---|---|
| Fast 2647/0/0/0 | `verify-like-ci-rerun-full.log:8127` |
| MySQL 61/0/0/0 | `verify-like-ci-rerun-full.log:24077` |
| Performance 1/0/0/0 | `verify-like-ci-rerun-full.log:24166` |
| Browser 3/0/0/0 | `verify-like-ci-rerun-full.log:24291` |
| Backup PITR SUCCESS | Run 2 log + `pitr-evidence/` **file archive VERIFIED**; MySQL digest **BLOCKED**; prod topology **BLOCKED** |
| Mock not labeled as Sandbox/prod in final GO decision | Final decision remained NO-GO for externals |
| No SCA / PIT / SpotBugs / Sonar / Gitleaks in CI | `pom.xml` / `.github/workflows/ci.yml` |
| Source tree matches frozen commit for src/pom/migration | Independent review confirmed |

---

## BLOCKED (insufficient evidence for PASS)

### Browser automated evidence

- `-Pbrowser-tests` Run 2: **EXECUTED** (3 / 0 skip) — not a substitute for sandbox or UAT

### Human UAT / business sign-off

- Owner policy lock for ACC-ARCH-P1-001  
- Dispatch G2 Phase B (ACC-REQ-P1-001)  
- Production-topology backup restore (ACC-OPS-P0-002)

### External sandbox Demo (not executed)

- freee — ACC-REQ-P0-001  
- CloudSign — ACC-REQ-P0-002  
- Gemini / G10 / AI PII send — ACC-OPS-P0-003  
- SMTP production delivery — REV-P1-005  
- Webhook real endpoint — remaining ops under ACC-OPS-P0-003; SSRF code = REV-P1-002  

### Tooling / evidence gaps

- DAST / penetration / SCA / secret scan  
- Full requirement traceability % (reported 87%/75% not recalculable)  
- JaCoCo artifact preserved (REV-P2-002)  
- MySQL `8.0.36` image digest in PITR freeze (BLOCKED)  
- Actuator absence = ops readiness gap (ACC-OPS-P0-001 VERIFIED; severity as code P0 per rubric)

---

## Evidence package defects (1st pass — revision 3 status)

| Defect | Revision 3 status |
|---|---|
| `findings.csv` incomplete / field drift | **Fixed** — 56×16 fields (final freeze) |
| Severity downgraded to preserve counts | **Fixed** — rubric + restorations |
| JaCoCo without artifact | **Open** — REV-P2-002 |
| Security “no P0 path” | **Withdrawn** — REV-P0-001 |
| PITR files not in package | **Fixed** — `pitr-evidence/` + manifest |
| Zero-skip via stale Surefire XML | **Fixed** — handoff uses Run 2 aggregates only |
| Open checkbox count 67 vs 68 | **Fixed** — `requirements-traceability.md` |

**REV-P1-007** remains OPEN until 3rd independent reviewer confirms ledger.

---

## Fix batches (do not implement in review; for engineering)

1. **P0 identity & auth** — Restrict identity-providers + admin surfaces to 管理者; method-level guards; audit existing links; HR/manager denial tests.  
2. **P1 egress / SSRF / secrets** — Webhook allowlist; remove browser API key; ingestion PII; mail fail-fast + no body logs.  
3. **P1 prod baseline** — Prevent accidental `dev` profile; prod boot validation; SCA/secret scan/SBOM.  
4. **Evidence governance** — Browser tests write `target/` only; archive Surefire per gate; JaCoCo with hash; rebuild findings ledger.  
5. **External + UAT** — Provider sandbox/canary; prod-topology restore drill; UAT sign-off; re-freeze commit; re-run independent review.

---

## Independent decision

### **NO-GO**

Automated CI-like gates: **PASS (VERIFIED)**.  
Security / evidence integrity / external readiness: **FAIL / BLOCKED**.  
Unconditional GO criteria: **not met**.
