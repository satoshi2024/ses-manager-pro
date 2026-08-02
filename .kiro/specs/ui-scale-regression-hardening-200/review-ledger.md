# Review Ledger — 200名規模 UI・同時実行回帰

## 記入規約

- 実装AIは各task完了時に「実装証跡」列まで記入する。
- Review AIは実装説明を信用せず、実diff、test、browser、DB/logを確認して「Review判定」を記入する。
- 判定は`PASS` / `FAIL` / `BLOCKED`。`未確認`や空欄のまま全体PASSにしない。
- Testcontainers未実行は`BLOCKED(Dockerなし)`であり`PASS`ではない。

## Defect traceability

| ID | 期待結果要約 | 実装task | 変更file | 自動test | Demo/証跡 | 実装証跡 | Review判定 |
|---|---|---|---|---|---|---|---|
| R3-001 | 25同時login成功、deadlock 0 | S1 |  |  |  |  |  |
| R3-002 | setup failureをsummary/exitへ反映 | S2 |  |  |  |  |  |
| R3-003 | 複数credential、単一credential矛盾拒否 | S2 |  |  |  |  |  |
| R3-004 | 認証済みmetricsまたは明示Unavailable | S3 |  |  |  |  |  |
| R3-005 | BP review 200、`#request`なし | A1 |  |  |  |  |  |
| R3-006 | 契約147件全到達、scope total正確 | A2 |  |  |  |  |  |
| R3-007 | Bench 32件filter可能 | B1 |  |  |  |  |  |
| R3-008 | scope外detailにdummy/actionなし | B2 |  |  |  |  |  |
| R3-009 | invalid customerが400/404、500なし | A3 |  |  |  |  |  |
| R3-010 | マイ勤怠は要員だけ表示 | B3 |  |  |  |  |  |
| R3-011 | 要員に横断検索UIなし、API拒否維持 | B3 |  |  |  |  |  |
| R3-012 | 勤怠147件をpaged、月確定全体 | C1 |  |  |  |  |  |
| R3-013 | Kanban段階load、83件全到達 | C2 |  |  |  |  |  |
| R3-014 | lead 41件をpaged | C3 |  |  |  |  |  |
| R3-015 | task 81件paged、担当者/filter | C4 |  |  |  |  |  |
| R3-016 | dashboard Top10+total+全件導線 | C5 |  |  |  |  |  |
| R3-017 | scopeに一致するKPI表記 | B4 |  |  |  |  |  |
| R3-018 | 見積page文言のplaceholder残り0 | D1 |  |  |  |  |  |
| R3-019 | candidate edit CRUD動線 | D2 |  |  |  |  |  |
| R3-020 | payroll main landmark 1 | D3 |  |  |  |  |  |
| R3-021 | PS5.1/PS7でhelper実行 | S3 |  |  |  |  |  |

## Test runs

| 日時 | command/scenario | 環境 | tests/requests | failure | error | skip | P95 | 証跡path | 判定 |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| baseline 2026-08-02 | `mvn -B test` | H2 / Dockerなし | 1,277 | 0 | 0 | 8 | - | `target/surefire-reports` | 参考値 |
| baseline 2026-08-02 | 25 unique simultaneous login | MySQL | 25 login | - | 10 | - | - | app log | FAIL |
| baseline 2026-08-02 | staggered login + 25 steady | MySQL | 2,027 request | 0 | 0 | - | 41.65ms | temp capacity output | 参考値 |
|  |  |  |  |  |  |  |  |  |  |

## Review findings

| Finding | 優先度 | file/line | 事象 | 要求ID | 状態 | 修正commit | 再確認 |
|---|---|---|---|---|---|---|---|
|  |  |  |  |  |  |  |  |

## Final gate

- [ ] 全21件にReview判定あり
- [ ] P0/P1/P2 FAIL 0
- [ ] requirements→task→file→test→Demo traceability欠落0
- [ ] `verify-like-ci` failure/error/skip 0
- [ ] MySQL 25同時login成功
- [ ] MySQL 25 steady request error 0 / P95 < 500ms
- [ ] 5role browser回帰完了
- [ ] security/scope/CSRF regressionなし
- [ ] 未解決riskをユーザーへ明示

