# 300人規模 E2E 第2ラウンド（深掘り）

- 実行日時: 2026-08-09T14:21:24.535Z
- 対象URL: http://localhost:8081
- 検出問題数: 0（重複除去後）
- 実行チェック数: 54（うち失敗 0）

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
| engineer-detail:account-link | OK | id=1 linkText=#397 |
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
