# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project overview

SES Manager Pro (`sql/`,`README.md`) is a management system for a Japanese SES (システムエンジニアリングサービス) company: engineer/skill management, customer & project management, a Kanban-style proposal pipeline, contract/assignment tracking, a KPI dashboard (utilization rate, bench count, projected revenue, gross profit), engineer↔sales-rep assignment with a per-sales-rep performance/commission rollup, and an admin-only user/permission module (account CRUD + role-based menu access). Backend: Spring Boot 3.3 + MyBatis-Plus + MySQL. Frontend: Thymeleaf server-rendered pages with jQuery/vanilla JS + Bootstrap 5 (no build step, no bundler — static JS/CSS served directly).

The UI, comments, log messages, commit conventions, `.kiro` documents, and SQL migration comments in this repo are in Japanese. Match that when editing templates, JS, Java comments/log strings, specs, and migration files. Do not add Chinese prose to repository files unless the file is explicitly a Chinese localization resource such as `messages_zh.properties`.

## Commands

No Maven wrapper is checked in; use the bundled Maven distribution under `apache-maven-3.9.6/` or a system `mvn`.

```
# run the app (requires MySQL running locally, see "Local database" below)
.\apache-maven-3.9.6\bin\mvn spring-boot:run

# run all tests (uses H2 in-memory DB via src/test/resources/application-test.yml, no MySQL needed)
# the Testcontainers migration smoke test auto-skips unless Docker is available (see "Tests and the DB")
.\apache-maven-3.9.6\bin\mvn test

# run a single test class
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest

# run a single test method
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest#getSummary_returnsSixMonthTrailingWindow

# build a jar
.\apache-maven-3.9.6\bin\mvn package
```

App listens on `http://localhost:8080`. Login page is at `/login`; default seeded credentials are `admin` / `admin123` (see `db/migration/V2__init_master_data.sql`).

### Local database

`application.yml` points at `jdbc:mysql://localhost:3306/ses_manager_db` (env vars `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` override; defaults are for local dev only). Schema/data are managed by **Flyway** (`src/main/resources/db/migration/V1__...` through `V14__...`) and applied automatically on startup — no manual SQL execution needed. Before running the app locally:
1. Start MySQL and create an empty `ses_manager_db` database.
2. Run the app (`mvn spring-boot:run`) — Flyway applies all migrations on startup.

If you already have a database that was set up by manually running the old `sql/001`–`sql/008` scripts (pre-Flyway), Flyway's `baseline-on-migrate: true` (configured with `baseline-version: 9`) treats it as already at V9 and only applies anything newer — it will NOT re-run V1–V9 against a non-empty schema.

**`V1__create_tables.sql` is a *consolidated baseline schema***, not the original first migration — later structural additions (e.g. `t_engineer.prefecture`/`railway_company`, `sys_user.failed_count`/`locked_until`) have been folded back into V1's `CREATE TABLE`s. Because of this, the incremental migrations that originally added those columns (`V3`, `V8`) are kept as **no-ops** (`SELECT 1;` only — an empty script is rejected by `spring.sql.init`). **When you add a column, add it to V1's `CREATE TABLE` and make sure no later migration re-`ADD COLUMN`s it** — a duplicate `ADD COLUMN` breaks *both* the empty-DB Flyway startup *and* test context init (see below), and MySQL 8 has no `ADD COLUMN IF NOT EXISTS`. New columns/tables introduced *after* the baseline (e.g. V12/V14) are added by their own migration as usual.

`prod` profile (`application-prod.yml`) additionally applies `db/migration-prod/V10__update_admin_password_bcrypt.sql`, which rewrites the seeded plaintext `admin123` password to its BCrypt hash (required because `prod` uses `BCryptPasswordEncoder` while `dev`/`test` use `NoOpPasswordEncoder`). Change the admin password immediately after first login in any real deployment.

### Tests and the DB

Tests do **not** need MySQL — Spring Boot tests pick up `src/test/resources/application-test.yml`, which uses an H2 in-memory DB in MySQL compatibility mode and disables Flyway (`spring.flyway.enabled: false`). The H2 schema for tests comes from **two** mechanisms, so keep both in sync when you change the schema:
1. `spring.sql.init.schema-locations` in `application-test.yml` **replays a curated subset of `db/migration` scripts** (plus H2-specific variants under `sql/` for MySQL-only migrations) to build the base schema for `@SpringBootTest`s that boot the real datasource. `V3` is deliberately **excluded** from this list because it duplicated V1's columns — the same class of conflict described above. A non-idempotent migration will fail *here* at context-init even if it's fine under baselined prod.
2. Test classes that need a fuller/isolated schema load `@Sql("/sql/engineer-schema-h2.sql")`, a hand-maintained consolidated H2 schema. **If you add a column/table, update `engineer-schema-h2.sql` too** or MyBatis-Plus's generated `SELECT` (which lists every entity column) will fail with "Unknown column".

Because tests run on H2 with Flyway disabled, **migration SQL is never executed against real MySQL by the normal suite**. `src/test/java/com/ses/migration/FlywayMigrationSmokeTest` (Testcontainers) fills that gap: it spins up a real MySQL 8 container, runs the full `db/migration` set from an empty DB, and asserts the resulting schema. **It requires Docker** — it auto-skips when Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`), so `mvn test` stays green locally, but **CI needs Docker for this test to actually run** (it is the only automated check that catches MySQL-dialect errors, missing columns, and migration-vs-migration conflicts).

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

`config/SecurityConfig.java`: form login at `/login`, session-based auth, CSRF disabled specifically for `/api/**` (`csrf().ignoringRequestMatchers("/api/**")`) since those are called via same-origin AJAX. `CustomUserDetailsService` loads users via `SysUserMapper` and maps `sys_user.role` to a single `ROLE_<role>` authority (roles are the fixed ENUM `管理者/営業/HR/マネージャー`).

**Password encoder is profile-switched** (two `@Bean`s in `SecurityConfig`): `@Profile("!prod")` → `NoOpPasswordEncoder` (plaintext, matches the plaintext `admin/admin123` seed and the H2 test data), `@Profile("prod")` → `BCryptPasswordEncoder`. `UserApiController` always calls `passwordEncoder.encode(...)` before saving, so switching profiles requires no code change. Tests run under the `test` profile (i.e. `!prod`), so they keep the plaintext encoder.

### User & permission module (admin-only)

The user/permission module lets an admin manage accounts and control which menus each role can reach. Follow its existing pieces rather than reinventing:

- **Account CRUD** — `controller/api/UserApiController` (`/api/users`) + `controller/page/UserPageController` (`/user/list`) + `templates/user/list.html` + `static/js/modules/user.js`, following the standard `Engineer`/`Customer` CRUD pattern. Passwords are stripped from list/detail responses; on update an empty password keeps the existing one (relies on MyBatis-Plus `update-strategy: not_null`). Guards: a user cannot delete/disable themselves, nor change their own role (prevents self-lockout); duplicate `username` is checked before insert/update.
- **Role → menu permissions** — two tables (`m_menu` with `menu_key` / `path_prefix` / `api_prefix`, and `t_role_menu` mapping `role`×`menu_id`), seeded in `db/migration/V2__init_master_data.sql` (admin = all menus; other roles = everything except `user`). `controller/api/RoleMenuApiController` (`/api/role-menus`) reads/replaces a role's allowed menus (the replace is `@Transactional`). The permissions UI is a second tab in `templates/user/list.html`.
- **Enforcement is two-layered**: (1) `config/GlobalControllerAdvice` injects `allowedMenus` (the current role's allowed `menu_key`s) into every page model, and `layout/sidebar.html` shows each `<li>` only via `th:if="${allowedMenus.contains('...')}"`; (2) `config/MenuPermissionFilter` (an `OncePerRequestFilter` wired into the Spring Security chain via `addFilterAfter(...)` in `SecurityConfig`) blocks direct URL/API access — it matches the request URI against `m_menu.path_prefix`/`api_prefix` (longest match wins) and returns 403 (JSON for `/api/**`, `sendError` → unified error page for pages) when the role lacks the menu. **The `管理者` role always bypasses this filter** (superuser), so admins can never lock themselves out via the permission settings.
- **Hard admin-only boundary**: independent of the dynamic filter, `SecurityConfig` statically restricts `/user/**`, `/api/users/**`, `/api/role-menus/**` to `hasRole("管理者")`.
- Both `GlobalControllerAdvice` and `MenuPermissionFilter` obtain their service/mapper beans via `ObjectProvider` and fall back gracefully (empty menus / allow-through) when unavailable, so test slices like `@WebMvcTest` and the H2-backed `MobileResponsiveLayoutTest` (whose schema has no `m_menu`/`t_role_menu`) don't break.

Spec for this module: `.kiro/specs/user-account-management/` (requirements / design / tasks).

### Engineer ↔ sales-rep & commission module

Links engineers to sales users (`sys_user.role = '営業'`), attributes contracts to a sales rep, and computes a per-sales-rep performance/commission rollup. Spec: `.kiro/specs/engineer-sales-commission/`.

- **担当営業 association** — `t_engineer_sales` (`entity/EngineerSales`, `mapper/EngineerSalesMapper`, `service/EngineerSalesService`) maps engineer×sales-user with a `primary_flag` and **history via `released_at` (NULL = current), not soft-delete** (soft-deleted rows are hidden by the global `@TableLogic`, so history would be unqueryable). Business rules live in `EngineerSalesServiceImpl` (`@Transactional`): assignee must be an active `営業`; no duplicate active assignment; first assignment is forced primary; setting a new primary demotes the old one in the same tx; releasing a primary while other reps remain is blocked. API is under `/api/engineers/{id}/sales-reps` (reuses the `engineer` menu's `api_prefix`, so no new permission wiring). UI: a card on `templates/engineer/detail.html` + a column/filter on the engineer list; the bench list (`analytics`) also shows the primary rep.
- **Contract attribution** — `t_contract` gains `sales_user_id` (+ optional `commission_base_type`/`commission_rate` overrides). On proposal→contract conversion (`ContractServiceImpl.createDraftFromProposal`) it defaults to the engineer's current primary rep; the contract form lets you change it. `ContractMapper.selectPageWithNames` joins `sys_user` for the rep name (**note: `sys_user`'s name column is `real_name`, not `full_name`**) and filters by `salesUserId`.
- **Performance & commission** — `SalesPerformanceService`/`Impl` computes per-rep monthly figures on the fly (no ledger table): assigned-engineer count, closed-deal count (契約, excluding renewals), win rate (提案 basis via `proposed_by`), active-contract sales/gross-profit (mirrors `DashboardServiceImpl`'s work-record-preferred / contract-price-fallback), and commission = `max(0, floor(base × rate ÷ 100))` per contract. The **default commission rule is stored in `m_system_config`** (`commission.base-type` = 粗利/売上, `commission.rate` = %) and edited from the existing admin `/system-config` screen — no new table. Page: `/sales-performance` (`sales-performance` menu, seeded in V14 for 管理者/営業/マネージャー).
- **Clearable override fields** — the global `mybatis-plus … update-strategy: not_null` means a `null` field is skipped on update, so a nullable column normally can't be cleared back to "unset". `Contract.salesUserId`/`commissionBaseType`/`commissionRate` override this with `@TableField(updateStrategy = FieldStrategy.ALWAYS)` so "revert to default" (send `null`) actually persists. Use this pattern when a nullable field must be user-clearable; it's safe only when every update path sends the full entity.

### Frontend structure

No SPA framework, no bundler. Each feature page is `templates/<area>/list.html` (+ occasional `form.html`/`detail.html`) using the Thymeleaf Layout Dialect against `templates/layout/base.html` (which pulls in `layout/header.html` and `layout/sidebar.html` as fragments and loads Bootstrap 5, SweetAlert2, Chart.js, and `common.js` from CDN/static). Page-specific behavior lives in one JS file per area under `static/js/modules/<area>.js`, loaded via the `page-js` Thymeleaf fragment slot.

Conventions used across every module JS file (`engineer.js`, `customer.js`, `project.js`, `contract.js`, `proposal-kanban.js`, `email-template.js`, etc.) — follow these when adding a new CRUD screen rather than inventing a new pattern:
- List/search/pagination via `$.ajax` GET to `/api/<area>`, re-rendered into a table.
- Create/edit share one Bootstrap modal per area (`#<area>Modal`); `save<Area>()` POSTs or PUTs, then on `res.code === 200` calls `bootstrap.Modal.getInstance(...).hide()` and shows a success toast via the global `Toast` object (`window.Toast = SES.toast`, defined in `common.js`).
- Delete flows confirm via SweetAlert2 (`Swal.fire({...}).then(...)`) before issuing the DELETE request.
- `common.js` (`SES` global) also owns: sidebar toggle behavior, header clock, Bootstrap tooltip init, the notification bell dropdown (`SES.notification.load()`, polls `/api/notifications`), and a global jQuery `ajaxSetup complete` handler that watches for session-expiry (an HTML response where JSON was expected) and redirects to `/login`.

### AI features

`ai.enabled`/`ai.provider` in `application.yml` currently disable real AI calls (`provider: mock`) — `GeminiService` and the AI matching/skill-sheet endpoints (`AiApiController`, `AiRestController`) are placeholders pending real API wiring via `config/AiConfig.java` (`ai.api-key`, `ai.api-url`, `ai.model`).

## Spec-driven task workflow (`.kiro/specs/`)

This repo uses a lightweight spec convention for planned work: each feature/fix lives under `.kiro/specs/<name>/` with three files — `requirements.md` (numbered requirements + acceptance criteria), `design.md` (technical approach per requirement, naming concrete files/methods), `tasks.md` (an ordered, checkbox task list, each with Objective / implementation guidance / test requirements / a manual "Demo" verification step). When asked to work from one of these specs, follow `tasks.md` in order, and check off (`- [x]`) each task as it's completed and verified via its Demo step.
