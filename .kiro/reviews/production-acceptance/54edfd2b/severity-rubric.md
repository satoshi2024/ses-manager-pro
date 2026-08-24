# Finding Severity Rubric — Production Acceptance

**Applies to:** `findings.csv` at commit `54edfd2b`  
**Rule:** Severity reflects **intrinsic risk at production cutover**, not ledger roll-up targets. Status (OPEN / CLOSED / WITHDRAWN / SUPERSEDED) is separate from severity. **The Severity column is authoritative**; finding ID prefixes are frozen historical labels and may lag a re-rate (e.g. ACC-ARCH-P3-001 is P2).

---

## P0 — Production blocker

Assign when **any** of:

- Exploitable path to account takeover, privilege escalation, or cross-tenant data access without admin intent
- Incorrect money/settlement/invoice/commission persistence under normal concurrent use
- Required production gate (HFP external sandbox, prod restore) **mandated for GO** and entirely un evidenced
- Application cannot be health-checked / safely orchestrated **and** this blocks deploy sign-off (ops readiness gate)

**Not P0:** mock-only integrations (track as REQ/OPS P0 only when GO policy mandates sandbox), JDK mismatch, JaCoCo gaps, scale hotspots without correctness bug.

---

## P1 — High (must fix or explicitly accept before prod)

Assign when:

- Material security weakness without proven exploit chain (SSRF class, secret handling, profile misconfiguration)
- Business-integrity leak across roles/scopes (e.g. scoped manager sees company-wide closing data)
- Test/ops gap that hides security wiring or allows silent prod misconfig (JaCoCo exclude of `config/**`, DRY_RUN mail)
- Supply-chain gate absent (Dependency-Check / Dependabot)
- Evidence/traceability defects that invalidate sign-off audit (REV-P1-007, REV-P1-008)

**Distinct findings stay distinct:** e.g. `config/**` vs `mapper/**` JaCoCo excludes; health/readiness vs metrics/APM; health vs session stickiness/scale.

---

## P2 — Medium

Assign when:

- Correctness risk is **secondary**, bounded, or mitigated (controller-layer guard, unique DB constraint exists)
- Test-architecture / schema-parity / static-analysis gaps (H2 vs Flyway, weak MVC asserts, no PIT)
- Operational false-green or misleading gate signal (backup shell assertions passing despite setup error)
- Scale/query patterns that degrade under load but do not corrupt data at typical tenant size
- Architecture inconsistencies (menu vs service deny, missing `rollbackFor` on non-critical paths)

**CLOSED** only with Run 2+ verified evidence (e.g. MySQL gate executed). **WITHDRAWN** when disproven (false positive). **SUPERSEDED** only when replacement finding's Required Test and remediation **fully cover** the same risk surface.

---

## P3 — Low

Assign when:

- Performance / maintainability / developer ergonomics only
- Environment parity notes (local JDK vs CI) with bytecode target unchanged
- Non-blocking migration hygiene (smoke class fragmentation)
- Sanitized messaging / logging polish

**Do not downgrade P2→P3** without a written technical basis in the finding Evidence column (scale-only, admin-only, mitigated elsewhere, etc.).

---

## SUPERSEDED semantics

| Replacement must include | Example valid | Example invalid |
|---|---|---|
| Same risk surface + Required Test | ACC-OPS-P1-004 → REV-P1-005 (SMTP DRY_RUN) | ACC-TEST-P1-004 → ACC-TEST-P1-001 (flake ≠ JaCoCo) |
| Same integration gate | ACC-OPS-P1-003 → ACC-REQ-P0-001 (freee) | ACC-OPS-P1-001 → ACC-OPS-P0-001 (metrics ≠ health probe) |
| Same false-positive closure | ACC-ARCH-P1-002 → REV-P2-001 | ACC-TEST-P2-001 → REV-P2-002 (schema parity ≠ JaCoCo artifact) |

When replacement does **not** fully cover the original risk → restore **OPEN** at original severity.
