# R4-P1-01 migration order decision

## Decision metadata

- Decision ID: `S10-R4-P1-01-V83-REALIZED-RENUMBER`
- Decision date: 2026-08-09 (JST)
- Status: `IMPLEMENTER_DECISION_SUBMITTED / R10 INDEPENDENT VERIFICATION REQUIRED`
- Implementer Head: `08eb09802d07c6e272473495ac22f5057cd4bbba`（Base `df7f6b1f5e27b64876133d26debd95422d29379a`）
- Scope: repository-known local development DB and CI/Testcontainers ephemeral MySQL
- Production release authorization: **なし**。本decisionは採番と資料の整合を決めるもので、T061のDDL適用や本番交付を許可しない。

## Evidence and environment inventory

| environment | read-only evidence | result | provenance |
|---|---|---|---|
| local-default (`localhost:3306/ses_manager_db`) | `flyway_schema_history`をJDBCで照会 | V82/V83 rowなし、latest successful V74、`success=true`、`installed_on=2026-08-02 00:35:29`、`checksum=559443363` | `environment-evidence-packet.md` capture `2026-08-09T08:47:26Z` |
| CI/Testcontainers | MySQL 8.0へFlyway target 83を適用後、履歴をread-only照会 | V82 row absent、V83 `success=true`、`installed_on=2026-08-09 09:27:47.0Z`、`checksum=2106900723`、latest versioned successful=V83 | `FlywayEnvironmentEvidenceTest` CI run `31305828153`のログ（test自体1/0/0/0） |
| GitHub persistent environments | GitHub API `/repos/satoshi2024/ses-manager-pro/environments` | `total_count=0`。repo workflowにもstaging/production deployment targetはない | 2026-08-09 read-only `gh api` |
| staging / production / other legacy | repo inventory | 接続先・credential・owner指定がrepoに存在しない。接続・変更は実施していない | owner secretを取得せず、未構成環境を推測しない |

CI/Testcontainersの履歴照会はrepeatable migration（`version IS NULL`）をlatestから除外し、versioned migrationだけを対象にしている。これによりFlyway履歴の見かけ上のlatestを誤って証拠化しない。

## Formal decision

1. V83はrepoの`src/main/resources/db/migration/V83__attendance_leave_overtime_compliance.sql`として実在し、Testcontainers fresh DBで適用成功した。V82はrepoにも証跡環境にも存在しないため、V82を後から作成・補填しない。
2. S10 `dispatch-outsourcing-compliance-ledger`の予約をV82から**V84**へ繰り上げる。S11は実在V83として扱う。
3. 後続予約は同じdecisionでS12=V85、S13=V86、S14=V87、S15=V88、S16=V89、S17=V90へ繰り上げる。V59、V72、V82は欠番として保持する。
4. 過去migrationの編集、out-of-order適用、V82のlegacy backfillは行わない。新しいS10 migrationを作る場合の番号はV84である。
5. staging/production等の外部管理環境が後からinventoryへ追加された場合は、このdecisionを自動的に本番証拠とは扱わず、環境別read-only `flyway_schema_history`を追加取得してR10/M gateを再開する。

## Synchronized artifacts

- `README.md`、`parallel-execution-plan.md`、`spec-execution-ledger.md`
- S10〜S17の`design.md`/`tasks.md`
- `spec-start-conversations.md`、`spec-review-conversations.md`
- `copyable-conversations/S10...S17`のstart/review資料
- `src/test/resources/migration/s10-r4-p1-01-v83-realized.properties`
- `SpecDispatchConsistencyTest`のV83/V82 guardとdecision fixture test

## Gate and rollback

R10が本decision、environment packet、同期資料、fixture、`SpecDispatchConsistencyTest` PASSを独立確認するまで、R4-P1-01は自己判定で閉じない。確認完了まではdeploy freeze、T061、V84 DDL、production変更を停止する。rollbackはこの文書・資料・fixture・testのcommitをrevertするだけで、production DB rollbackは不要（本deltaでmigration/DDLを作成していない）。
