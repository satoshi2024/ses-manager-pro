# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

SES Manager Pro (`sql/`,`README.md`) is a management system for a Japanese SES (システムエンジニアリングサービス) company: engineer/skill management, customer & project management, a Kanban-style proposal pipeline, contract/assignment tracking, a KPI dashboard (utilization rate, bench count, projected revenue, gross profit), engineer↔sales-rep assignment with a per-sales-rep performance/commission rollup, and an admin-only user/permission module (account CRUD + role-based menu access). Backend: Spring Boot 3.3 + MyBatis-Plus + MySQL. Frontend: Thymeleaf server-rendered pages with jQuery/vanilla JS + Bootstrap 5 (no build step, no bundler — static JS/CSS served directly).

On top of that core, the 2026H2 roadmap (`.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`, FR-01〜FR-11) added: AI email ingestion for projects and partner-engineer availability, AI proposal drafting with real match scoring, a duplicate-proposal guard, anonymized/multi-format skill-sheet export, cash-flow forecasting, a contract-renewal calendar with escalation, forward utilization/bench forecasting, semi-automatic payment reconciliation, and labor-compliance risk checks plus engineer follow-up/retention tracking. See "Roadmap feature modules (FR-01〜FR-11)" below for where each lives.

The UI, comments, log messages, and commit conventions in this repo are in Japanese. Match that when editing templates, JS, and Java comments/log strings.

## Commands

No Maven wrapper is checked in; use the bundled Maven distribution under `apache-maven-3.9.6/` or a system `mvn`.

```
# run the app (requires MySQL running locally, see "Local database" below)
.\apache-maven-3.9.6\bin\mvn spring-boot:run

# run the fast feedback suite (H2/unit/MVC; excludes mysql/performance tags)
.\apache-maven-3.9.6\bin\mvn test

# run the real MySQL/Flyway suite sequentially (Docker required; CI uses 3 isolated shards)
.\apache-maven-3.9.6\bin\mvn test -Pmysql-tests

# run the isolated performance regression without JaCoCo instrumentation
.\apache-maven-3.9.6\bin\mvn test -Pperformance-tests

# run every JUnit test sequentially in one Maven invocation (Docker required)
.\apache-maven-3.9.6\bin\mvn test -Pfull-tests

# run the same three test gates and backup integration gate as CI
.\scripts\verify-like-ci.ps1

# run a single test class
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest

# run a single test method
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest#getSummary_returnsSixMonthTrailingWindow

# build a jar
.\apache-maven-3.9.6\bin\mvn package
```

App listens on `http://localhost:8080`. Login page is at `/login`; default seeded credentials are `admin` / `admin123` (see `db/migration/V2__init_master_data.sql`).

### Local database

`application.yml` points at `jdbc:mysql://localhost:3306/ses_manager_db` (env vars `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` override; defaults are for local dev only). Schema/data are managed by **Flyway** (`src/main/resources/db/migration/V1__...` through `V42__...`) and applied automatically on startup — no manual SQL execution needed. Before running the app locally:
1. Start MySQL and create an empty `ses_manager_db` database.
2. Run the app (`mvn spring-boot:run`) — Flyway applies all migrations on startup.

If you already have a database that was set up by manually running the old `sql/001`–`sql/008` scripts (pre-Flyway), Flyway's `baseline-on-migrate: true` (configured with `baseline-version: 9`) treats it as already at V9 and only applies anything newer — it will NOT re-run V1–V9 against a non-empty schema.

If your local database or production database fails to start due to a Flyway checksum mismatch (e.g. from modified scripts or legacy DB upgrades), follow this strict `flyway repair` runbook:
1. **Backup**: Take a full database dump before proceeding.
2. **Verify Checksums**: Check which scripts are failing validation and ensure they are on the allowed repair list.
3. **Execute Repair**: Run `mvn org.flywaydb:flyway-maven-plugin:repair -Dflyway.url=jdbc:mysql://localhost:3306/ses_manager_db -Dflyway.user=root -Dflyway.password=123456`.
4. **Schema Alignment Check**: Start the application and verify that `LegacyDatabaseFlywayCallback` has completed any necessary schema compensations (e.g., adding missing columns).
5. **Login Test**: Verify that the application starts successfully and you can log in.

**IMPORTANT**: Never modify a migration script after it has been applied. Doing so causes `FlywayValidateException` (checksum mismatch) for anyone who has already applied the original script. If you need to change the schema or data, always create a new `Vxx__...sql` migration script.

**Version numbers must be unique — two scripts sharing a version stop the app from booting at all.** Flyway resolves migrations before it touches the DB and throws `FlywayException: Found more than one migration with version NN`, so *every* environment (dev and prod alike) fails at startup, not just the one running the new script. This is the single most likely merge accident in this repo: parallel feature branches each grab "the next free number" against `main`, and the collision only appears once both are merged. Before adding a migration, check the *merged* state of `db/migration`, and when resolving a collision **renumber the later script upward to the next free number** (e.g. V50→V52, V49→V55) — do **not** fill an earlier gap, because a DB that already applied a higher version will then reject the lower one with `FlywayValidateException: Detected resolved migration not applied to database`. `src/test/java/com/ses/migration/MigrationScriptIntegrityTest` guards this without needing a DB or Docker; note it reads the migrations from the **classpath**, so run `mvn clean test` after renaming a script or a stale copy under `target/classes` will still be seen.

**`V1__create_tables.sql` is a *consolidated baseline schema***, not the original first migration — later structural additions (e.g. `t_engineer.prefecture`/`railway_company`, `sys_user.failed_count`/`locked_until`) have been folded back into V1's `CREATE TABLE`s. Because of this, the incremental migrations that originally added those columns (`V3`, `V8`) are kept as **no-ops** (`SELECT 1;` only — an empty script is rejected by `spring.sql.init`). **When you add a column, add it to V1's `CREATE TABLE` and make sure no later migration re-`ADD COLUMN`s it** — a duplicate `ADD COLUMN` breaks *both* the empty-DB Flyway startup *and* test context init (see below), and MySQL 8 has no `ADD COLUMN IF NOT EXISTS`. New columns/tables introduced *after* the baseline (e.g. V12/V14) are added by their own migration as usual.

`prod` profile (`application-prod.yml`) additionally applies `db/migration-prod/R__update_admin_password_bcrypt.sql`, which rewrites the seeded plaintext `admin123` password to its BCrypt hash (required because `prod` uses `BCryptPasswordEncoder` while `dev`/`test` use `NoOpPasswordEncoder`). Change the admin password immediately after first login in any real deployment.

### Tests and the DB

Tests do **not** need MySQL — Spring Boot tests pick up `src/test/resources/application-test.yml`, which uses an H2 in-memory DB in MySQL compatibility mode and disables Flyway (`spring.flyway.enabled: false`). The H2 schema for tests comes from **two** mechanisms, so keep both in sync when you change the schema:
1. `spring.sql.init.schema-locations` in `application-test.yml` **replays a curated subset of `db/migration` scripts** (plus H2-specific variants under `sql/` for MySQL-only migrations) to build the base schema for `@SpringBootTest`s that boot the real datasource. `V3` is deliberately **excluded** from this list because it duplicated V1's columns — the same class of conflict described above. A non-idempotent migration will fail *here* at context-init even if it's fine under baselined prod.
2. Test classes that need a fuller/isolated schema load `@Sql("/sql/engineer-schema-h2.sql")`, a hand-maintained consolidated H2 schema. **If you add a column/table, update `engineer-schema-h2.sql` too** or MyBatis-Plus's generated `SELECT` (which lists every entity column) will fail with "Unknown column".

Because the fast suite runs on H2 with Flyway disabled, **migration SQL is never executed against real MySQL by plain `mvn test`**. Real-MySQL tests carry the JUnit `mysql` tag and run only through `-Pmysql-tests`, `-Pfull-tests`, or `verify-like-ci`. This is an explicit selection, not an environment-dependent skip: Docker availability no longer changes what plain `mvn test` means. The MySQL profile uses disposable tmpfs-backed MySQL 8 containers and validates dialect, migration ordering, locking, and concurrency behavior.

Because that smoke test disappears without Docker, the checks that must hold *regardless of environment* live in Docker-free tests instead — treat these as the last line of defence and extend them rather than relying on the Testcontainers run:
- `MigrationScriptIntegrityTest` — duplicate migration versions and empty scripts (see the version-numbering warning above).
- `MessageBundleConsistencyTest` — key parity across `messages{,_en,_ko,_zh_CN}.properties`, no duplicate keys, and (`testTemplateMessageKeysExist`) that every `#{...}` a Thymeleaf template references actually exists. The last one matters because `spring.messages.use-code-as-default-message: true` means a missing key renders the raw key name in the UI instead of throwing.

### Local vs CI — explicit, identical test gates

CI and `scripts/verify-like-ci.*` execute the same explicit gates: the default fast suite, `mysql-tests`, and `performance-tests`. CI runs them as parallel jobs; the local script runs them sequentially and then executes the backup integration gate. Docker and Node are checked before the full local verification starts, so missing capabilities fail fast instead of silently reducing coverage.

1. **Fast feedback remains H2-based.** H2 is retained for business/service/controller regression speed, but it is not evidence of MySQL dialect compatibility.
2. **MySQL behavior is a separate required gate.** Every Testcontainers class must carry `@Tag("mysql")`; CI invokes `-Pmysql-tests` with Docker required and zero skips.
   The profile itself stays sequential because in-JVM Testcontainers parallelism can corrupt per-class Surefire report attribution. CI parallelizes safely with three isolated jobs defined by `scripts/test-suites/mysql-shard-{1,2,3}.txt`; `MySqlTestShardInventoryTest` fails when a tagged test is missing or duplicated in those lists.
3. **Test execution order is still pinned to `<runOrder>alphabetical</runOrder>`.** Fast tests currently share `jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`; use transaction rollback or explicit cleanup and never read rows a test did not insert. Remove this ordering constraint only after all H2 tests are isolated.
4. **Schedulers are disabled in the test profile.** Scheduler-specific tests invoke their target path explicitly; background cron jobs must not mutate the shared test DB.
5. **Timezone / locale / encoding are pinned** to `Asia/Tokyo`, `ja_JP`, and UTF-8. CI runs Temurin **21**; `<java.version>17</java.version>` is the bytecode target.

**The CI contract is zero skipped tests across all three gates.** Before pushing, run `scripts/verify-like-ci.sh` / `.ps1`. `clean` remains required because migration integrity tests read copied classpath resources.

## Architecture

### Layering

Standard Spring MVC/MyBatis-Plus layering under `com.ses`:

- `controller/page/*PageController` — return Thymeleaf view names only (e.g. `engineer/list`), no business logic. One per feature area.
- `controller/api/*ApiController` — `@RestController`s under `/api/**`, called via AJAX from page JS. Every response is wrapped in `ApiResult<T>` (`common/result/ApiResult.java`): `{code, message, data}`, success code `200`. Frontend JS checks `res.code === 200`.
- `service/*Service` (interface) + `service/impl/*ServiceImpl` — most simply extend MyBatis-Plus's `IService<Entity>` with no custom methods (e.g. `EngineerService`), so CRUD for those entities is entirely generic (`page()`, `save()`, `updateById()`, `removeById()` called directly from the API controller). Custom query/aggregation logic (e.g. `DashboardService`, `NotificationService`) lives in the impl class.
- `mapper/*Mapper` — thin `@Mapper` interfaces extending MyBatis-Plus's `BaseMapper<Entity>`. **There are no MyBatis XML mapper files** (`mapper-locations: classpath:mapper/**/*.xml` in `application.yml` currently matches nothing) — the vast majority of queries go through MyBatis-Plus's `LambdaQueryWrapper` built inline in controllers/services. The only custom SQL is a handful of annotation-based `@Select` methods for cases the wrapper can't express cleanly (e.g. `SysUserMapper.selectByUsername`, `RoleMenuMapper.selectMenuKeysByRole` which joins `t_role_menu`×`m_menu`) — still no XML.
- `entity/*` — MyBatis-Plus `@TableName`-annotated entities, one per DB table (see `db/migration/V1__create_tables.sql` for schema). Soft-delete is enabled globally via `mybatis-plus.global-config.db-config.logic-delete-field: deletedFlag` — `removeById()` etc. do NOT hard-delete.
- `dto/<area>/*` — response-shaping DTOs for endpoints that aggregate across entities (dashboard profit analysis, notifications, AI matching), kept separate from entities.
- `common/exception` — `BusinessException` (carries an explicit code/message for expected failures) + `GlobalExceptionHandler` (`@RestControllerAdvice(basePackages = "com.ses.controller.api")`), which converts `BusinessException`, validation errors, and any uncaught `Exception` **thrown by REST API controllers** into an `ApiResult` JSON response. It is intentionally scoped to the `api` package so that page (Thymeleaf) controller exceptions are NOT turned into raw JSON in the browser — those fall through to the error dispatch. Because of this, `/api/**` endpoints should always return JSON even on failure.
- Unified error handling — `controller/CustomErrorController` (`implements ErrorController`, maps `/error`) is the single entry point for error dispatches (404, 403, uncaught page exceptions, `sendError`). It returns an `ApiResult` JSON for API/AJAX requests (`/api/**`, or `X-Requested-With: XMLHttpRequest`, or `Accept: application/json`) and renders the unified dark-theme error page `templates/error.html` (self-contained — does NOT extend `layout/base`, so it renders even when sidebar/DB/auth are broken) for browser navigation, choosing title/message/icon by status code. `server.error` in `application.yml` disables the Whitelabel page and suppresses message/stacktrace/exception leakage.

### Security

`config/SecurityConfig.java`: form login at `/login`, session-based auth. CSRF is enabled for all paths including `/api/**`. `CustomUserDetailsService` loads users via `SysUserMapper` and maps `sys_user.role` to a single `ROLE_<role>` authority (roles are `管理者/営業/HR/マネージャー/要員`).

**Password encoder is profile-switched** (two `@Bean`s in `SecurityConfig`): `@Profile("!prod")` → `NoOpPasswordEncoder` (plaintext, matches the plaintext `admin/admin123` seed and the H2 test data), `@Profile("prod")` → `BCryptPasswordEncoder`. `UserApiController` always calls `passwordEncoder.encode(...)` before saving, so switching profiles requires no code change. Tests run under the `test` profile (i.e. `!prod`), so they keep the plaintext encoder.

### User & permission module (admin-only)

The user/permission module lets an admin manage accounts and control which menus each role can reach. Follow its existing pieces rather than reinventing:

- **Account CRUD** — `controller/api/UserApiController` (`/api/users`) + `controller/page/UserPageController` (`/user/list`) + `templates/user/list.html` + `static/js/modules/user.js`, following the standard `Engineer`/`Customer` CRUD pattern. Passwords are stripped from list/detail responses; on update an empty password keeps the existing one (relies on MyBatis-Plus `update-strategy: not_null`). Guards: a user cannot delete/disable themselves, nor change their own role (prevents self-lockout); duplicate `username` is checked before insert/update.
- **Role → menu permissions** — two tables (`m_menu` with `menu_key` / `path_prefix` / `api_prefix`, and `t_role_menu` mapping `role`×`menu_id`), seeded in `db/migration/V2__init_master_data.sql` (admin = all menus; other roles = everything except `user`). `controller/api/RoleMenuApiController` (`/api/role-menus`) reads/replaces a role's allowed menus (the replace is `@Transactional`). The permissions UI is a second tab in `templates/user/list.html`.
- **Enforcement is two-layered**: (1) `config/GlobalControllerAdvice` injects `allowedMenus` (the current role's allowed `menu_key`s) into every page model, and `layout/sidebar.html` shows each `<li>` only via `th:if="${allowedMenus.contains('...')}"`; (2) `config/MenuPermissionFilter` (an `OncePerRequestFilter` wired into the Spring Security chain via `addFilterAfter(...)` in `SecurityConfig`) blocks direct URL/API access — it matches the request URI against `m_menu.path_prefix`/`api_prefix` (longest match wins) and returns 403 (JSON for `/api/**`, `sendError` → unified error page for pages) when the role lacks the menu. **The `管理者` role always bypasses this filter** (superuser), so admins can never lock themselves out via the permission settings.
- **Hard admin-only boundary**: independent of the dynamic filter, `SecurityConfig` statically restricts `/user/**`, `/api/users/**`, `/api/role-menus/**` to `hasRole("管理者")`.
- Both `GlobalControllerAdvice` and `MenuPermissionFilter` obtain their service/mapper beans via `ObjectProvider` and fall back gracefully (empty menus / allow-through) when unavailable, so test slices like `@WebMvcTest` and the H2-backed `MobileResponsiveLayoutTest` (whose schema has no `m_menu`/`t_role_menu`) don't break.
- **Action permissions are a third layer, and they must never be expressed as an allow-list.** `MenuPermissionFilter` also asks `AuthorizationService.isAllowed` for an action key derived by `service/security/ActionPermissionResolver`, which **generates the key mechanically from the URI root** (`/api/quotations` → `quotation.view`, `DELETE /api/engineers/1` → `engineer.delete`). So the set of action keys grows every time a controller is added, and *any* attempt to enumerate the permitted keys per role silently 403s the un-enumerated ones. That already happened once: V64 seeded per-role allow-lists and broke 営業/HR on the dashboard, analytics, quotations, work records and every `.delete`. The model is therefore **baseline + deny**: a group holds `action_key='*'` for "everything the old menu model allowed", and `t_permission_group_action.deny_flag = 1` rows subtract the sensitive ones (`user.*`, `permission.manage`, `audit.security.view`, `file.scan.retry`, `payroll.view` except HR, `contract.cost.view` for HR). Deny beats baseline. `AuthorizationServiceImpl.legacyRoleAllows` (used only for users with no group) implements the identical rule, so **adding an API root needs no change in either place**. `ActionPermissionMatrixTest` locks this down — if you find yourself editing its `BUSINESS_ACTIONS` list to make a new endpoint pass, the seed or the fallback has regressed to an allow-list.
- Every user must have a permission group. `UserApiController` assigns the role's default group on create and re-assigns on role change; V64/V66 backfill existing rows. A user without a group falls back to `legacyRoleAllows`, which no admin can edit — so a missing assignment is a permission bug, not a default.

Spec for this module: `.kiro/specs/user-account-management/` (requirements / design / tasks).

### Engineer ↔ sales-rep & commission module

Links engineers to sales users (`sys_user.role = '営業'`), attributes contracts to a sales rep, and computes a per-sales-rep performance/commission rollup. Spec: `.kiro/specs/engineer-sales-commission/`.

- **担当営業 association** — `t_engineer_sales` (`entity/EngineerSales`, `mapper/EngineerSalesMapper`, `service/EngineerSalesService`) maps engineer×sales-user with a `primary_flag` and **history via `released_at` (NULL = current), not soft-delete** (soft-deleted rows are hidden by the global `@TableLogic`, so history would be unqueryable). Business rules live in `EngineerSalesServiceImpl` (`@Transactional`): assignee must be an active `営業`; no duplicate active assignment; first assignment is forced primary; setting a new primary demotes the old one in the same tx; releasing a primary while other reps remain is blocked. API is under `/api/engineers/{id}/sales-reps` (reuses the `engineer` menu's `api_prefix`, so no new permission wiring). UI: a card on `templates/engineer/detail.html` + a column/filter on the engineer list; the bench list (`analytics`) also shows the primary rep.
- **Contract attribution** — `t_contract` gains `sales_user_id` (+ optional `commission_base_type`/`commission_rate` overrides). On proposal→contract conversion (`ContractServiceImpl.createDraftFromProposal`) it defaults to the engineer's current primary rep; the contract form lets you change it. `ContractMapper.selectPageWithNames` joins `sys_user` for the rep name (**note: `sys_user`'s name column is `real_name`, not `full_name`**) and filters by `salesUserId`.
- **Performance & commission** — `SalesPerformanceService`/`Impl` computes per-rep monthly figures on the fly (no ledger table): assigned-engineer count, closed-deal count (契約, excluding renewals), win rate (提案 basis via `proposed_by`), active-contract sales/gross-profit (mirrors `DashboardServiceImpl`'s work-record-preferred / contract-price-fallback), and commission = `max(0, floor(base × rate ÷ 100))` per contract. The **default commission rule is stored in `m_system_config`** (`commission.base-type` = 粗利/売上, `commission.rate` = %) and edited from the existing admin `/system-config` screen — no new table. Page: `/sales-performance` (`sales-performance` menu, seeded in V14 for 管理者/営業/マネージャー).
- **Clearable override fields** — the global `mybatis-plus … update-strategy: not_null` means a `null` field is skipped on update, so a nullable column normally can't be cleared back to "unset". `Contract.salesUserId`/`commissionBaseType`/`commissionRate`/`renewalDecision` override this with `@TableField(updateStrategy = FieldStrategy.ALWAYS)` so "revert to default" (send `null`) actually persists. Use this pattern when a nullable field must be user-clearable.

  ⚠️ **`ALWAYS` is per-field, and it makes partial updates destructive.** Every `ALWAYS` field on the entity is emitted into the `SET` clause on **every** `updateById(entity)` — including the ones left `null` on a sparse patch object. So `new Contract(){id, renewalDecision}` passed to `updateById` also writes `sales_user_id = NULL`, silently detaching the contract from its sales rep and its commission overrides. The rule is: `updateById(entity)` only for paths that send the **full** entity; for single-column updates use an explicit `UpdateWrapper` naming the column (see `ContractServiceImpl.updateRenewalDecision`).

### Roadmap feature modules (FR-01〜FR-11)

The 2026H2 roadmap features. Each has a spec under `.kiro/specs/<name>/`; the roadmap itself is `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`. Shared conventions worth knowing before extending any of them:

| FR | Spec / menu | Core classes | Notes |
|---|---|---|---|
| 01 案件メール取込 | `project-email-ingestion` / `project-ingestion` | `ProjectIngestionService(Impl)`, `ProjectParseService`, V44 | Ingestion-job pattern (below) |
| 02 AI提案生成 | `ai-proposal-generation` | `ProposalDraftService(Impl)`, `GeminiMatchingServiceImpl` | `POST /api/ai/proposal-draft`; prompt gets `initialName`, never the real name |
| 03 重複提案ガード | `duplicate-proposal-guard` | `ProposalService.findActiveDuplicates`, `ProposalMapper.selectActiveDuplicates` | Warns, never blocks |
| 04 スキルシート匿名化 | `skillsheet-anonymized-export` | `SkillSheetGenerator`, `SkillSheetOptions`, `SkillSheetConstants`, V55 | Templates in `m_system_config: skillsheet.templates`; PDF and Excel must stay in sync |
| 05 資金繰り予測 | `cashflow-forecast` | `CashFlowForecastService(Impl)`, V46/V48/V49 | `/api/cashflow` is `@PreAuthorize` 管理者/マネージャー (no `m_menu` prefix covers it) |
| 06 契約更新カレンダー | `contract-renewal-calendar` | `RenewalCalendarService(Impl)`, `RenewalEscalationService(Impl)`, V50 | `t_contract.renewal_decision`; escalation stages in `renewal.escalation-days` |
| 07 将来稼働率予測 | `utilization-forecast` | `UtilizationForecastService(Impl)`, `UtilizationCalcService`, V51 | See utilization caliber note below |
| 08 要員空き取込 | `bp-availability-ingestion` | `BpAvailabilityIngestionService(Impl)`, `BpAvailabilityParseService`, V45 | Ingestion-job pattern |
| 09 入金消込 | `payment-reconciliation` | `PaymentReconciliationService(Impl)`, `t_bank_deposit`, V52 | Auto-applies only on unique amount+name match; `InvoiceService.addPayment` is the overpayment guard |
| 10 労務コンプライアンス | `labor-compliance-check` | `LaborComplianceService(Impl)`, V53 | Findings are derived, never persisted |
| 11 要員フォロー・定着 | `engineer-followup-retention` | `EngineerFollowupService`, `RetentionRiskService(Impl)`, V54 | See batch-scoring note below |

- **Ingestion-job pattern (FR-01 / FR-08 / `skillsheet-ingestion`)** — all three share one shape: upload/paste → `DocumentTextExtractor` → `*ParseService` (AI, with a deterministic mock) → job row with status `取込待ち→抽出中→要確認→確定済/却下/失敗` → human review → entity creation. Copy an existing one rather than inventing a new flow. Non-obvious invariants: `@Async` parse is invoked through `ObjectProvider` self-injection (never a direct self-call, which would bypass the proxy); status transitions are CAS updates so a double-confirm returns 409. `extracted_text` holds PII and is purged by `ResumeRetentionCleanupServiceImpl` after `app.resume.retention-days`.
- **Storing a file obliges you to register it in two places** — both default to the *unsafe* answer when you forget, and neither failure is visible until it bites:
  1. `FileReferenceProvider` — `FileCleanupScheduler` deletes any file in the upload root that no provider claims. Miss it and live originals are deleted (only after `cleanup-safety-hours`, so it looks fine in testing). Note the scan is **non-recursive**, which is the only reason contract PDFs under `uploads/contracts/{id}/` survive without a provider — don't change `Files.list` to `Files.walk` without adding one.
  2. `FileScopeValidationService` — decides who may download a stored name. Its final fallthrough is now **deny** (`error.file.unknownReference`), so an unregistered table becomes undownloadable rather than world-readable. FR-01/FR-08 originals are gated on the `project-ingestion` / `bp-availability-ingestion` menu.
- **Uploads are quarantine-then-publish, and the metadata row is what makes a file readable.** `FileStorageServiceImpl` writes to `uploads/quarantine/`, scans (`FileScanner`; ClamAV in prod, a signature fake elsewhere), and only moves to `uploads/published/` on `CLEAN`; `load()` refuses anything without a `t_file_security_metadata` row in `PUBLISHED`+`CLEAN`. Two consequences: `FileCleanupServiceImpl` must scan all three directories (root, `quarantine`, `published`), and **files that predate this scheme are unreachable until migrated** — `LegacyUploadMigrationService` (run at startup by `LegacyUploadMigrationRunner`, `app.upload.legacy-migration-enabled`) scans root-level files claimed by a provider and publishes the clean ones. If you ever change the storage layout again, ship the equivalent migration in the same change: a Flyway script cannot move files, so the DDL alone will look complete while every existing download 403s.
- **Utilization caliber is shared, deliberately** — `UtilizationCalcService` is the single contract-based definition of "working vs bench" used by *both* the dashboard KPI and the FR-07 forecast, so the current-month figure is identical in both. Don't compute utilization inline anywhere else; extend this service. Note it is a different caliber from the `Engineer.status` breakdown chart, which is intentional.
- **Retention risk must be scored in batch** — `RetentionRiskService.score(id)` costs several queries, and the engineer list needs a score for every row. Always call `scoreBatch(ids)` from list/collection paths; `score(id)` is for single-record screens only.
- **Compliance findings are permission-gated at the source** — `/api/compliance` is 管理者/マネージャー only via `m_menu`, but the same findings are embedded in the monthly-closing summary, which HR can also reach. `MonthlyClosingServiceImpl.canViewCompliance()` re-checks the `compliance` menu before filling that section. If you surface compliance data on another screen, gate it the same way rather than assuming the screen's own menu is enough.

### Capacity and concurrency

The app is a single-process Spring Boot server with **no cache layer, no session store, and no read replica**. Before promising a concurrency number, know where the ceiling actually is — in order:

1. **DB connection pool** (`spring.datasource.hikari.maximum-pool-size`, default 20, env `DB_POOL_SIZE`). This is the real limit, not Tomcat's thread count. A request holds its connection for the whole transaction, and read-heavy screens issue many queries each — `DashboardService.getSummary` alone runs ~17, several of which load whole tables (all engineers, all contracts). Concurrency far above the pool size just queues until `connection-timeout` and then 500s. **Raising the pool is not the fix** — past roughly `DB cores × 2` it gets slower, not faster. The fix is fewer queries per request and caching.
2. **Tomcat threads** (`server.tomcat.threads.max`, default 200, env `TOMCAT_MAX_THREADS`), then `accept-count` (100). Beyond that, connections are refused at the socket.
3. **Sessions are in-memory.** There is no Spring Session/Redis dependency, so scaling out needs sticky sessions and a restart logs everyone out.
4. **Unbounded exports.** `/api/engineers/export` and `/api/contracts/export` build the whole result set in memory with no paging; concurrent exports on a large tenant are an OOM risk.
5. **Page sizes must be clamped.** Use `PageUtils.safePage` in every list endpoint. `PaginationInnerInterceptor.setMaxLimit(1000)` only protects queries that go through MyBatis-Plus paging — hand-written `LIMIT/OFFSET` (e.g. `NotificationMapper.selectPageForUser`) bypasses it entirely and must normalize the size itself.

Scaling meaningfully past a few dozen concurrent active users needs work this codebase has not done yet: caching the dashboard aggregates, externalizing sessions, and read replicas or narrower queries. Treat those as design changes, not config tuning.

### Internationalization

Four bundles are shipped and the language switcher offers exactly these: `messages.properties` (ja, the base/default), `messages_en`, `messages_zh_CN`, `messages_ko`. There is **no `messages_zh`** — `zh_CN` already carries every key, so a `zh` bundle would never be consulted; don't add one. `spring.messages.fallback-to-system-locale: false` keeps unknown locales on the Japanese base.

**There must be no `messages_ja.properties` either, and this one is a trap that already bit.** The base bundle *is* the Japanese bundle, but Spring's `MessageSource` still resolves `messages_ja` ahead of it for a Japanese locale — while `I18nMessagesLoader` (which builds `window.SES_MESSAGES` for the frontend) explicitly skips locale files when `lang.equals("ja")`. So a key present in both files renders **one string server-side via Thymeleaf `#{...}` and a different string client-side via `SES.i18n.t()`**. That is exactly what happened: the project list's filter dropdown said 「終了」 while the status badge on the same screen said 「クローズ」 (and 提案 vs 提案中). Adding a language means adding a bundle for *that* language only; `testNoUnexpectedBundleFiles` fails on any `messages*.properties` outside the sanctioned four, because the other checks hardcode their file list and would otherwise never see the stray file.

`MessageBundleConsistencyTest` enforces that the four bundles have identical key sets, no duplicate keys, and matching `{0}`-style placeholders. **Satisfying it by copying the Japanese value into the other bundles is not acceptable** — that passes the test while shipping Japanese text to English/Korean/Chinese users. Translate each new key. When adding a key referenced from a template, the same test's `testTemplateMessageKeysExist` will fail until the key exists in the base bundle.

### Frontend structure

No SPA framework, no bundler. Each feature page is `templates/<area>/list.html` (+ occasional `form.html`/`detail.html`) using the Thymeleaf Layout Dialect against `templates/layout/base.html` (which pulls in `layout/header.html` and `layout/sidebar.html` as fragments and loads Bootstrap 5, SweetAlert2, Chart.js, and `common.js`). Page-specific behavior lives in one JS file per area under `static/js/modules/<area>.js`, loaded via the `page-js` Thymeleaf fragment slot.

**Every frontend asset is served from this app — no CDN, no web fonts, no external host of any kind.** The libraries live in `static/lib/` (Bootstrap, Bootstrap Icons + its woff/woff2, jQuery, SweetAlert2, Chart.js + date-fns adapter, SortableJS, DOMPurify, frappe-gantt, marked) and are referenced as `/lib/...`, which `SecurityConfig` already `permitAll()`s. This is not a preference: Japanese corporate networks routinely block these domains, and jQuery or Bootstrap failing to load doesn't degrade the page, it makes the whole system unusable (blank lists, modals that never open). Web fonts were removed rather than vendored — Noto Sans JP/SC/KR at four weights would be ~15 MB with no build step to subset it — so `--ses-font-sans` in `common.css` is an OS-native CJK stack instead; use that variable rather than naming fonts inline. `StaticAssetLocalityTest` fails on any external `src`/`href` in a template **and** on any `@import`/`url()` to an external host in `static/css` — that second check exists because `common.css` had an `@import` of Google Fonts that a template-only scan could not see, and a CSS `@import` blocks first paint until the connection times out.

**Filter panels are collapsible on phones, generically.** All seven list screens share the same markup (`.card > .card-body > form#searchForm`), so `SES.filterPanel` in `common.js` finds it and injects the toggle bar — do not hand-roll one per template, and keep that structure when adding a list screen. Visibility is decided entirely by the `max-width: 768px` block in `common.css`; the JS only writes `data-filter-collapsed` on the card. That split is deliberate: above the breakpoint the attribute is ignored, so the panel is always open on desktop and rotating a phone needs no resize listener. The badge showing how many filters are active is load-bearing — collapsed filters are otherwise invisible state, and a user who forgot a filter will report the list as missing records.

**Input suggestions never use `<datalist>`.** Type-ahead inputs declare `data-suggest="<key>"` and are wired by `SES.autocomplete` in `common.js` (`bind()` for the shared `/api/autocomplete/*` sources, `attach(input, {items})` for a page-local source like the station list in `engineer.js`). Native `datalist` was removed because it produces suggestions that don't start with what was typed, for two independent reasons: Chrome/Edge match the option's **label text** as well as its `value` (the 最寄り駅 list carried 「都道府県 路線」 as the label, so typing 「東」 surfaced every station on 東海道本線 / in 東京都), and its filtering is substring-based in source order rather than prefix-first. `SES.autocomplete.match` fixes the caliber: match **the value only**, rank 完全一致 → 前方一致 → 部分一致, and normalize both sides (NFKC, case, カタカナ→ひらがな, spaces/中黒) so 「ＪＲ」/「JR」 and 「トウキョウ」/「とうきょう」 agree. Labels are display-only. `AutocompleteSuggestMatchTest` (Node.js, auto-skips without `node`) runs the matcher against the real 8千件超 station data and fails if a suggestion no longer starts with the typed text.

**Layout Fragments**: The base layout (`layout/base.html`) provides exactly three slots for child pages to use via `layout:fragment`:
- `content`: For the main page HTML body.
- `page-css`: For page-specific `<style>` or `<link rel="stylesheet">`.
- `page-js`: For page-specific `<script>` tags. Do not use generic names like `scripts`.

Conventions used across every module JS file (`engineer.js`, `customer.js`, `project.js`, `contract.js`, `proposal-kanban.js`, `email-template.js`, etc.) — follow these when adding a new CRUD screen rather than inventing a new pattern:
- List/search/pagination via `$.ajax` GET to `/api/<area>`, re-rendered into a table.
- Create/edit share one Bootstrap modal per area (`#<area>Modal`); `save<Area>()` POSTs or PUTs, then on `res.code === 200` calls `bootstrap.Modal.getInstance(...).hide()` and shows a success toast via the global `Toast` object (`window.Toast = SES.toast`, defined in `common.js`).
- Delete flows confirm via SweetAlert2 (`Swal.fire({...}).then(...)`) before issuing the DELETE request.
- `common.js` (`SES` global) also owns: sidebar toggle behavior, header clock, Bootstrap tooltip init, the notification bell dropdown (`SES.notification.load()`, polls `/api/notifications`), and a global jQuery `ajaxSetup complete` handler that watches for session-expiry (an HTML response where JSON was expected) and redirects to `/login`.

### AI features

`ai.enabled`/`ai.provider` in `application.yml` currently disable real AI calls (`provider: mock`) — `AiTextService` and the AI endpoints (`AiApiController`, `AiRestController`, `ProposalDraftService` via `POST /api/ai/proposal-draft`) use mock implementations pending real API wiring via `config/AiConfig.java` (`ai.api-key`, `ai.api-url`, `ai.model`).

The system uses `AiTextService` as the main abstraction, with `GeminiTextServiceImpl` for actual AI calls and `MockAiTextServiceImpl` as a fallback. For mock AI routing, `MockAiTextServiceImpl` relies on markers like `[TASK:PROPOSAL_DRAFT]` injected into the prompt to return the appropriate deterministic JSON response.

**AI機能開発時の注意事項（A8-01/A8-02関連）**:
1. **PII（個人を特定できる情報）の保護**: LLMへ送信するプロンプトへの生情報の直接注入（氏名、連絡先など）は原則禁止。必ずマスキング（イニシャル等）するか、AIサービスレイヤーでサニタイズすること（※ただし、取込パーサ等、生情報の抽出自体を目的とするAI機能はこの限りではない）。データの保存期間（`app.resume.retention-days`）超過時の論理削除・パージも確実に行うこと。
2. **`AiTextService` のBean競合回避**: AIプロバイダー（`mock`、`gemini`等）の実装クラスは、テスト環境（`@SpringBootTest`）やプロファイル切替時にBean定義の重複エラーを引き起こしやすい。実装クラスには必ず `@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")` のような条件式を用いてフォールバック（例: mock）を設定し、プロバイダ切替時の不変条件（どの provider でも全AI系Beanが一意に解決）を担保すること。


## Spec-driven task workflow (`.kiro/specs/`)

This repo uses a lightweight spec convention for planned work: each feature/fix lives under `.kiro/specs/<name>/` with three files — `requirements.md` (numbered requirements + acceptance criteria), `design.md` (technical approach per requirement, naming concrete files/methods), `tasks.md` (an ordered, checkbox task list, each with Objective / implementation guidance / test requirements / a manual "Demo" verification step). When asked to work from one of these specs, follow `tasks.md` in order, and check off (`- [x]`) each task as it's completed and verified via its Demo step.
