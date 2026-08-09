# 300人規模 E2E 第2ラウンド（深掘り）

- 実行日時: 2026-08-09T14:05:56.068Z
- 対象URL: http://localhost:8081
- 検出問題数: 26（重複除去後）
- 実行チェック数: 54（うち失敗 1）

## チェック一覧

| チェック | 結果 | 内容 |
|---|---|---|
| pagination:engineer | OK | total=255, pages=26, lastPageRows=5, activePage=26 |
| pagination:customer | OK | total=38, pages=4, lastPageRows=8, activePage=4 |
| pagination:project | OK | total=103, pages=11, lastPageRows=3, activePage=11 |
| pagination:contract | OK | total=252, pages=13, lastPageRows=12, activePage=13 / 13 |
| pagination:todo | OK | total=100, pages=5, lastPageRows=20, activePage=5 |
| pagination:candidate | OK | total=45, pages=5, lastPageRows=5, activePage=5 |
| search:engineer | OK | keyword=田中 on #searchName, found=true |
| global-search | OK | keyword=田中, resultText=要員 (4) 田中 陸正社員 稼動中 田中 達也正社員 稼動中 田中 結衣正社員 Bench 田中 太郎正社員 稼動中  |
| engineer-detail:account-link | NG | id=1 linkText= |
| modal:engineer-modal | OK | engineerModal opened, visible fields=11 |
| modal:customer-modal | OK | customerModal opened, visible fields=5 |
| modal:contract-modal | OK | contractModal opened, visible fields=16 |
| modal:task-modal | OK | taskModal opened, visible fields=5 |
| api:管理者:/api/engineers?current=1&size=3 | OK | status=200 json |
| api:管理者:/api/customers?current=1&size=3 | OK | status=200 json |
| api:管理者:/api/users?current=1&size=3 | OK | status=200 json |
| api:管理者:/api/role-menus | OK | status=400 json |
| api:管理者:/api/approval/inbox | OK | status=404 json |
| api:管理者:/api/notifications | OK | status=200 json |
| api:管理者:/api/my/timesheet | OK | status=403 json |
| api:営業:/api/engineers?current=1&size=3 | OK | status=200 json |
| api:営業:/api/customers?current=1&size=3 | OK | status=200 json |
| api:営業:/api/users?current=1&size=3 | OK | status=403 json |
| api:営業:/api/role-menus | OK | status=403 json |
| api:営業:/api/approval/inbox | OK | status=404 json |
| api:営業:/api/notifications | OK | status=200 json |
| api:営業:/api/my/timesheet | OK | status=403 json |
| api:HR:/api/engineers?current=1&size=3 | OK | status=200 json |
| api:HR:/api/customers?current=1&size=3 | OK | status=200 json |
| api:HR:/api/users?current=1&size=3 | OK | status=403 json |
| api:HR:/api/role-menus | OK | status=403 json |
| api:HR:/api/approval/inbox | OK | status=404 json |
| api:HR:/api/notifications | OK | status=200 json |
| api:HR:/api/my/timesheet | OK | status=403 json |
| api:マネージャー:/api/engineers?current=1&size=3 | OK | status=200 json |
| api:マネージャー:/api/customers?current=1&size=3 | OK | status=200 json |
| api:マネージャー:/api/users?current=1&size=3 | OK | status=403 json |
| api:マネージャー:/api/role-menus | OK | status=403 json |
| api:マネージャー:/api/approval/inbox | OK | status=404 json |
| api:マネージャー:/api/notifications | OK | status=200 json |
| api:マネージャー:/api/my/timesheet | OK | status=403 json |
| api:要員:/api/engineers?current=1&size=3 | OK | status=403 json |
| api:要員:/api/customers?current=1&size=3 | OK | status=403 json |
| api:要員:/api/users?current=1&size=3 | OK | status=403 json |
| api:要員:/api/role-menus | OK | status=403 json |
| api:要員:/api/approval/inbox | OK | status=403 json |
| api:要員:/api/notifications | OK | status=200 json |
| api:要員:/api/my/timesheet | OK | status=400 json |
| mobile:sidebar-toggle | OK | before=false, afterOpen=true, afterClose=false, overflow=0 |
| mobile:modal-scroll | OK | visible=true, contentH=831, viewportH=844 |
| legacy-member:s300.member253 | OK | url=http://localhost:8081/my/timesheet, nameFound=true |
| legacy-member:s300.member254 | OK | url=http://localhost:8081/my/timesheet, nameFound=true |
| legacy-member:s300.member255 | OK | url=http://localhost:8081/my/timesheet, nameFound=true |
| concurrent-login | OK | 34/34 succeeded |

## 検出問題一覧

| ID | ロール | ビューポート | ページ | 種別 | 内容 |
|---|---|---|---|---|---|
| R2-001 | 管理者 | desktop | contract-gantt | PAGE_ERROR | TypeError: Cannot read properties of undefined (reading '11')
    at Object.format (http://localhost:8081/lib/frappe-gantt/frappe-gantt.min.js:1:1613)
    at f.get_date_info (http: |
| R2-002 | 管理者 | desktop | proposal-kanban | PAGE_ERROR | ReferenceError: renderKanbanCard is not defined
    at http://localhost:8081/js/modules/proposal-kanban.js:174:21
    at Array.forEach (<anonymous>)
    at Object.success (http://l |
| R2-003 | 管理者 | desktop | engineer-detail | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-004 | 管理者 | desktop | engineer-detail-legacy | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-005 | 管理者 | desktop | detail-1001 | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-006 | 管理者 | desktop | detail-1252 | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-007 | 管理者 | desktop | detail-1 | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-008 | 管理者 | desktop | detail-2 | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-009 | 管理者 | desktop | detail-3 | PAGE_ERROR | ReferenceError: loadAccountLink is not defined
    at http://localhost:8081/js/modules/engineer-account-link.js:6:51
    at http://localhost:8081/js/modules/engineer-account-link.j |
| R2-010 | 管理者 | desktop | engineer-detail | ACCOUNT_LINK | legacy engineer id=1 account link not shown () |
| R2-011 | 管理者 | desktop | route-skill-tag | HTTP_404 | HTTP 404 |
| R2-012 | 管理者 | desktop | route-skill-tag | CONSOLE_ERROR | Failed to load resource: the server responded with a status of 404 () |
| R2-013 | 管理者 | desktop | route-skill-tag | RESPONSE_4XX | 404 http://localhost:8081/skill-tag |
| R2-014 | 管理者 | desktop | route-search | HTTP_404 | HTTP 404 |
| R2-015 | 管理者 | desktop | route-search | CONSOLE_ERROR | Failed to load resource: the server responded with a status of 404 () |
| R2-016 | 管理者 | desktop | route-search | RESPONSE_4XX | 404 http://localhost:8081/search |
| R2-017 | 管理者 | desktop | route-tasks | HTTP_404 | HTTP 404 |
| R2-018 | 管理者 | desktop | route-tasks | CONSOLE_ERROR | Failed to load resource: the server responded with a status of 404 () |
| R2-019 | 管理者 | desktop | route-tasks | RESPONSE_4XX | 404 http://localhost:8081/tasks |
| R2-020 | 管理者 | desktop | route-saved-views | HTTP_404 | HTTP 404 |
| R2-021 | 管理者 | desktop | route-saved-views | CONSOLE_ERROR | Failed to load resource: the server responded with a status of 404 () |
| R2-022 | 管理者 | desktop | route-saved-views | RESPONSE_4XX | 404 http://localhost:8081/saved-views |
| R2-023 | 管理者 | desktop | route-batch-operations | HTTP_404 | HTTP 404 |
| R2-024 | 管理者 | desktop | route-batch-operations | CONSOLE_ERROR | Failed to load resource: the server responded with a status of 404 () |
| R2-025 | 管理者 | desktop | route-batch-operations | RESPONSE_4XX | 404 http://localhost:8081/batch-operations |
| R2-026 | 管理者 | mobile | proposal | PAGE_ERROR | ReferenceError: renderKanbanCard is not defined
    at http://localhost:8081/js/modules/proposal-kanban.js:174:21
    at Array.forEach (<anonymous>)
    at Object.success (http://l |
