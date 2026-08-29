# 300人規模 E2E 自動検出結果

- 実行日時: 2026-08-29T02:48:25.986Z
- 対象URL: http://localhost:8081
- 検出問題数: 6

| ID | ロール | ビューポート | ページ | 種別 | 内容 |
|---|---|---|---|---|---|
| E2E-001 | 管理者 | mobile | contract | H_OVERFLOW | horizontal overflow 14px |
| E2E-002 | 営業 | mobile | contract | H_OVERFLOW | horizontal overflow 14px |
| E2E-003 | HR | mobile | contract | H_OVERFLOW | horizontal overflow 14px |
| E2E-004 | マネージャー | mobile | contract | H_OVERFLOW | horizontal overflow 14px |
| E2E-005 | functional | desktop | customer_create | FUNCTIONAL_ERROR | TimeoutError: page.click: Timeout 30000ms exceeded.
Call log:
[2m  - waiting for locator('[data-bs-target="#customerModal"]')[22m
 |
| E2E-006 | multi | desktop | login | CONCURRENT_LOGIN | 5/10 login failed: s300.sales01,s300.sales02,s300.sales03,s300.hr01,s300.mgr01 |
