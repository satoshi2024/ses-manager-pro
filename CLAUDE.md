# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

SES Manager Pro (`sql/`,`README.md`) is a management system for a Japanese SES (システムエンジニアリングサービス) company: engineer/skill management, customer & project management, a Kanban-style proposal pipeline, contract/assignment tracking, and a KPI dashboard (utilization rate, bench count, projected revenue, gross profit). Backend: Spring Boot 3.3 + MyBatis-Plus + MySQL. Frontend: Thymeleaf server-rendered pages with jQuery/vanilla JS + Bootstrap 5 (no build step, no bundler — static JS/CSS served directly).

The UI, comments, log messages, and commit conventions in this repo are in Japanese. Match that when editing templates, JS, and Java comments/log strings.

## Commands

No Maven wrapper is checked in; use the bundled Maven distribution under `apache-maven-3.9.6/` or a system `mvn`.

```
# run the app (requires MySQL running locally, see "Local database" below)
.\apache-maven-3.9.6\bin\mvn spring-boot:run

# run all tests (uses H2 in-memory DB via src/test/resources/application-test.yml, no MySQL needed)
.\apache-maven-3.9.6\bin\mvn test

# run a single test class
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest

# run a single test method
.\apache-maven-3.9.6\bin\mvn test -Dtest=DashboardServiceImplTest#getSummary_returnsSixMonthTrailingWindow

# build a jar
.\apache-maven-3.9.6\bin\mvn package
```

App listens on `http://localhost:8080`. Login page is at `/login`; default seeded credentials are `admin` / `admin123` (see `sql/002_init_master_data.sql`).

### Local database

`application.yml` points at `jdbc:mysql://localhost:3306/ses_manager_db` (user `root`, no dedicated test password management — credentials are plain in `application.yml`). Before running the app locally:
1. Start MySQL and create `ses_manager_db`.
2. Run `sql/001_create_tables.sql` then `sql/002_init_master_data.sql`.

Tests do **not** need MySQL — Spring Boot tests pick up `src/test/resources/application-test.yml`, which points at an H2 in-memory DB in MySQL compatibility mode with `spring.sql.init.mode: always`.

## Architecture

### Layering

Standard Spring MVC/MyBatis-Plus layering under `com.ses`:

- `controller/page/*PageController` — return Thymeleaf view names only (e.g. `engineer/list`), no business logic. One per feature area.
- `controller/api/*ApiController` — `@RestController`s under `/api/**`, called via AJAX from page JS. Every response is wrapped in `ApiResult<T>` (`common/result/ApiResult.java`): `{code, message, data}`, success code `200`. Frontend JS checks `res.code === 200`.
- `service/*Service` (interface) + `service/impl/*ServiceImpl` — most simply extend MyBatis-Plus's `IService<Entity>` with no custom methods (e.g. `EngineerService`), so CRUD for those entities is entirely generic (`page()`, `save()`, `updateById()`, `removeById()` called directly from the API controller). Custom query/aggregation logic (e.g. `DashboardService`, `NotificationService`) lives in the impl class.
- `mapper/*Mapper` — thin `@Mapper` interfaces extending MyBatis-Plus's `BaseMapper<Entity>`. **There are no MyBatis XML mapper files** (`mapper-locations: classpath:mapper/**/*.xml` in `application.yml` currently matches nothing) — all queries go through MyBatis-Plus's `LambdaQueryWrapper` built inline in controllers/services, not through custom SQL.
- `entity/*` — MyBatis-Plus `@TableName`-annotated entities, one per DB table (see `sql/001_create_tables.sql` for schema). Soft-delete is enabled globally via `mybatis-plus.global-config.db-config.logic-delete-field: deletedFlag` — `removeById()` etc. do NOT hard-delete.
- `dto/<area>/*` — response-shaping DTOs for endpoints that aggregate across entities (dashboard profit analysis, notifications, AI matching), kept separate from entities.
- `common/exception` — `BusinessException` (carries an explicit code/message for expected failures) + `GlobalExceptionHandler` (`@RestControllerAdvice`), which converts `BusinessException`, validation errors, and any uncaught `Exception` into an `ApiResult` JSON response. Because of this, `/api/**` endpoints should always return JSON even on failure — if you see an endpoint returning an HTML error page instead, that's a bug in that specific path, not the intended behavior.

### Security

`config/SecurityConfig.java`: form login at `/login`, session-based auth, CSRF disabled specifically for `/api/**` (`csrf().ignoringRequestMatchers("/api/**")`) since those are called via same-origin AJAX. `PasswordEncoder` is currently `NoOpPasswordEncoder` (plaintext) — seeded/test passwords are plaintext to match. `CustomUserDetailsService` loads users via `SysUserMapper`.

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
