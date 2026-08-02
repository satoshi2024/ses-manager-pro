# Test Baseline — 2026-08-02 200名規模検証

## 1. 実行環境

| 項目 | 値 |
|---|---|
| アプリ | Spring Boot / `http://localhost:8080` |
| DB | MySQL `ses_manager_ui_test` |
| データmarker | `R3_SCALE_200` |
| 実行日 | 2026-08-02 JST |
| Java process | PID 11352（検証時） |
| Browser | Codex in-app Browser |
| Node | v24.18.0 |
| Docker | 利用不可 |

テストDBのデータはユーザー許可により保持している。ただし実装AIはID値を固定してテストしてはならず、自分で作成したfixtureのIDを使用すること。

## 2. データ件数

| テーブル/機能 | 件数 |
|---|---:|
| 要員 | 200 |
| 稼動中 | 131 |
| Bench | 32 |
| 提案中 | 22 |
| 退場予定 | 15 |
| ユーザー | 44（容量試験用25アカウントを含む） |
| 顧客 | 25 |
| 案件 | 64 |
| 契約 | 147 |
| 提案 | 83 |
| 月次勤怠 | 146 |
| 日次勤怠 | 2,901 |
| 請求 | 21 |
| 候補者 | 31 |
| リード | 41 |
| 商機 | 31 |
| ToDo | 81 |
| 見積 | 41 |
| BP会社 | 21 |

## 3. ロール別基準結果

### 管理者

- Dashboard: 稼動率73.5%、Bench 32名、予想売上130,401,428円、粗利率16.8%。
- 要員一覧: 200件、20ページ（10件/ページ）。
- 顧客: 25件、3ページ。
- 案件: 64件、7ページ。
- 契約: DB147件に対しUI100件、ページ移動不可。
- 8月勤怠grid: 契約147行を一括描画。
- 提案Kanban: 83件を一括描画。
- 見積: 41件、5ページ。ページ情報文言が破損。
- 候補者: 31件、4ページ。表示・削除のみで編集入口なし。
- ToDo: 81件を一括描画。担当者列なし。
- 月次締め: 未契約1、未確認0、未請求0、BP未払0、延滞請求0、compliance risk 48。締めボタンdisableは正しい。

### 営業

- `scope.sales-own-data-only=false`のため200名を参照可能。これは設定どおり。
- リード41件を一括描画し、ページングなし。
- 管理者専用メニューは非表示。

### HR

- 候補者、給与、要員、契約、勤怠等の許可メニューを表示。
- `/user/list`の直接アクセスは統一403となり正常。
- freee未接続時の給与画面は表示されるが、HTML上`main` landmarkが二重。

### マネージャー

- `r3_manager01`は組織scopeにより要員49件、契約37件を参照し、DB件数と一致。
- scope外要員detailへ直接遷移するとAPIは拒否するが、ページshellに固定値「田中 太郎」が残る。
- scope済みKPIなのに「全社平均粗利率」と表示される。

### 要員

- `member_test`はlogin後`/my/timesheet`へ遷移し、本人勤怠を表示。
- `/engineer/list`直接アクセスは統一403となり正常。
- Headerの横断検索を操作すると`/api/search`が403となり、「検索エラー」「このactionへのアクセス権限がありません」を表示。

## 4. 自動テスト基準

`mvn -B test`結果:

- suites: 213
- tests: 1,277
- failures: 0
- errors: 0
- skipped: 8
- report合計時間: 101.23秒

skipされた8件はDocker/Testcontainers依存:

1. `FlywayLegacyV60MigrationSmokeTest`
2. `FlywayLegacyV71MigrationSmokeTest`
3. `FlywayMigrationSmokeTest`
4. `FlywayRepairRunbookTest`
5. `FlywayV62ClosedHistoryMigrationSmokeTest`
6. `FlywayV63UpgradeMigrationSmokeTest`
7. `FlywayV73PartialRepairSmokeTest`
8. `ConcurrentUpdateTest`

実装完了判定はDocker有効環境の`verify-like-ci`でskip 0を必要とする。ローカルでDockerを用意できない場合、未検証項目をReview ledgerへ明記し、CI結果を最終gateにする。

## 5. 容量試験結果

### 5.1 同一adminアカウントを使う既存script

| stage | requests | errors | 主なerror |
|---:|---:|---:|---|
| 10 | 878 | 456 | HTTP 401 |
| 25 | 2,270 | 1,837 | HTTP 401 |

原因はアプリの単一ユーザー最大session数が5である一方、scriptが全workerで同じ`admin`を使うため。これはアプリ性能値として扱わない。

### 5.2 25個の異なるアカウントを同時login

| stage | login成功 | login 500 | login失敗率 | 成功sessionの業務request |
|---:|---:|---:|---:|---:|
| 10 | 3 | 7 | 70% | 261 |
| 25 | 15 | 10 | 40% | 1,199 |

server logの17件すべてが`UserSessionMapper.insert`時の`MySQLTransactionRollbackException: Deadlock found when trying to get lock`。

既存summaryは`Kind == 'request'`のみ集計するため、この17件を除外して`Errors=0`と誤表示した。

### 5.3 25アカウントを150ms間隔でlogin後、25同時利用

| 指標 | 結果 |
|---|---:|
| login失敗 | 0 |
| request | 2,027 |
| HTTP error | 0 |
| throughput | 144.57 req/s |
| P50 | 11.51 ms |
| P95 | 41.65 ms |
| P99 | 65.28 ms |
| server ERROR | 0 |

steady-stateは良好であり、主障害はlogin時の永続session登録に限定される。

## 6. 実装後に必ず再実行するシナリオ

1. 25個の異なるアカウントをbarrierで同時にloginする。
2. 全25loginが2xx/redirect成功し、DB deadlock/500が0であることを確認する。
3. 25sessionで10秒以上、要員一覧、要員detail、dashboard summary、通知件数を循環取得する。
4. setup failureとrequest failureの両方がsummaryに含まれ、失敗時はscriptが非0終了することを意図的失敗fixtureでも確認する。
5. 200要員/147契約fixtureで全ページの先頭・中間・最終データへ到達する。
6. 5ロールでsidebar/headerと直接URL/APIのpermission matrixを再確認する。

## 7. 実行上の注意

- Windowsでアプリ起動中に`mvn clean`すると、実行中jarがlockされ削除に失敗する。`verify-like-ci`前にアプリを停止し、テスト後に再起動する。
- Windows PowerShell 5.1では、現状の`verify-like-ci.ps1`がUTF-8/BOM非互換でparse errorになる。修正後は`powershell.exe`と`pwsh`の両方で起動確認する。
- Actuator probeが401のままなら、Hikari/Tomcat内部値を計測できていない。401を「正常監視」として扱わない。

