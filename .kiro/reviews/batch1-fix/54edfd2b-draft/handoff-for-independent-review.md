# Batch 1 Fix — Independent Reviewer Handoff (DRAFT candidate)

## Scope

**In scope (Batch 1):** REV-P0-001 OIDC identity-binding admin hard boundary; related P1/P2 hardening for quarantine approval, required audit, CAS concurrency, cutover runbook CoC.

**Out of scope (remain production NO-GO):** Batch 2 (SSRF/PII/mail/profile), Java 21 CI substitution policy outside this worktree run, production backup topology (ACC-OPS-P0-002), backup unit false-green (ACC-OPS-P2-003).

## Freeze base

- Parent / production-acceptance freeze: `54edfd2b08f5fd61095b3a94c33bd5b981935c28`
- Worktree: `C:\work\ses-fix-batch1`
- Branch: `fix/batch1-admin-hard-boundary`

## What to verify (code)

1. Admin-only SecurityConfig / `@PreAuthorize` / AuthorizationService hard-deny for identity-provider & system-config.
2. OIDC login only on `APPROVED` bindings; V109 quarantine + inventory + CHECK.
3. `ExternalIdentityProvisioningServiceImpl`:
   - `DuplicateKeyException` catch wraps **insert only**; `recordApprovalAudit` outside catch (REV-P1-012).
   - `FOR UPDATE` + CAS `approveIfNotApproved`; already-APPROVED idempotent (no reviewer overwrite, no extra audit).
   - After insert race: `selectByTenantProviderAndSubjectForUpdate` current read (MySQL RR).
4. Runbook `ops/security/runbooks/oidc-binding-quarantine-cutover.md`: 7 evidence files + `evidence-manifest.sha256` + dual sign-off.

## Gate evidence

See `.kiro/reviews/batch1-fix/54edfd2b-draft/` in this worktree:
- `env.txt` — Temurin 21 / tool versions
- `logs/verify-like-ci-java21.log`
- `surefire-archive/` — copied XML after gate
- `package-manifest.sha256` — recomputable digests
- `remaining-findings.md`

## Explicit non-claims

- This DRAFT commit is **not** production acceptance and **not** “Batch 1 production complete”.
- Overall production decision remains **NO-GO** until out-of-scope P0/P1 and cutover evidence (REV-P1-010) are closed.
- Backup integration may exit 0 while **ACC-OPS-P2-003** (unit false-green / `common::trap_add`) stays **OPEN** outside Batch 1 — do **not** reclassify as PASS.
