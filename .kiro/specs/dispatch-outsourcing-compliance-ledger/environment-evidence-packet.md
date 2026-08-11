# R4-P1-01 environment Flyway evidence packet

> **履歴packet**: V82/V83/V84採番を決定した2026-08-09時点のread-only証跡である。
> 現行G2 follow-up V102とS12〜S17 V103〜V108の予約は
> `g2-gate-decision-delta-r19-p1-01.md` §12を正とし、本packetの旧後続予約を現行として使用しない。

## Status

`SUBMITTED / REPO_KNOWN_ENVIRONMENTS_COMPLETE / R10_SCOPE_VERIFICATION_REQUIRED`（2026-08-09、秘密情報なし）。

本packetは、S10のV82予約とS11のV83実在について、repoから解決できる各environmentの
`flyway_schema_history`をread-onlyで照合する証跡台帳である。repo内に永続staging/production接続先やGitHub Environmentは存在しないため、未構成の外部環境を存在しないと推測せず、inventory境界と再開条件を明示する。

## Inventory scope and closure

repoから確定できる環境区分は、application.ymlの`local-default`、CI workflow/Testcontainersのephemeral MySQLである。`gh api /environments`は`total_count=0`で、workflowにもstaging/production deployment targetはない。したがってstaging・production・other legacyは「repoに構成・接続先がない外部環境候補」として別記録し、未確認の外部環境を「不存在」や「V83未適用」とは扱わない。

各行は、environment ownerが次のいずれかを明示した時点で確定する。

- `owner ID or approved role`（実在の個人名をcodeへ固定しない）
- environmentの正式名称と種別（CI / staging / production / legacy等）
- read-only capture時刻とV82/V83の同一schema結果

local-defaultのexecutorは、環境ownerを代行するものではなく、workspaceから既定DBをread-only確認した主実装AIである。

## Environment inventory

| environment | owner/evidence status | V82 | V83 | latest successful migration | capture / note |
|---|---|---|---|---|---|
| local-default (`localhost:3306/ses_manager_db`) | **COLLECTED**（read-only JDBC、secrets excluded） | rowなし（version/success/installed_on/checksum=N/A） | rowなし（version/success/installed_on/checksum=N/A） | V74 / success=true / installed_on=`2026-08-02 00:35:29` / checksum=`559443363` | capture `2026-08-09T08:47:26Z`。executor/owner role=`主実装AI（local read-only verifier; environment owner approval not claimed）`。`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`未設定のためapplication.yml既定値を使用。V82/V83を変更していない |
| CI / Testcontainers MySQL | **COLLECTED**（test read-only query、secrets excluded） | row absent | `success=true`、`installed_on=2026-08-09 09:27:47.0Z`、`checksum=2106900723` | V83 / success=true | `FlywayEnvironmentEvidenceTest` CI run `31305828153`のログで確認。executor/owner role=`CI workflow executor / 主実装AI`。同run全体のFAILは同期前のV82≤V83 guardのみで、証跡test自体は1/0/0/0 |
| staging | **NOT_CONFIGURED_IN_REPO** | N/A（接続先なし） | N/A（接続先なし） | N/A | GitHub Environment=0、workflow deployment targetなし。外部ownerが別inventoryを提示した場合は追加証跡gateを再開 |
| production | **NOT_CONFIGURED_IN_REPO** | N/A（接続先なし） | N/A（接続先なし） | N/A | productionへ接続・変更していない。外部ownerが別inventoryを提示した場合は追加証跡gateを再開 |
| other legacy / deployment environments | **NOT_CONFIGURED_IN_REPO** | N/A（接続先なし） | N/A（接続先なし） | N/A | repo内にinventory・接続先・credentialなし。外部環境を推測しない |

## Collected query and result

read-only JDBC query（password、接続secret、host secretはpacketへ保存しない）:

```sql
SELECT version, success, installed_on, checksum
FROM flyway_schema_history
WHERE version IN ('82', '83')
ORDER BY installed_rank;

SELECT version, success, installed_on, checksum
FROM flyway_schema_history
WHERE success = 1
ORDER BY installed_rank DESC
LIMIT 1;
```

local-defaultの結果:

```text
target rows=0
latest_success version=74, success=true,
  installed_on=2026-08-02 00:35:29.0, checksum=559443363
```

## Owner submission contract

各environment ownerは、接続secretを共有せず、environment名とcapture時刻を付けた次のread-only結果を提出する。

```text
environment=<name>
captured_at=<ISO-8601>
target version=82, success=<true|false>, installed_on=<timestamp|null>, checksum=<integer|null>
target version=83, success=<true|false>, installed_on=<timestamp|null>, checksum=<integer|null>
latest_success version=<version>, success=true, installed_on=<timestamp>, checksum=<integer|null>
executor/owner=<approved owner id or role>
```

rowが存在しない場合も、`version=82/83, row absent`として明示する。checksumの欠落やsuccess=falseは未確認として扱い、推測で補完しない。

## Decision gate

- formal decisionは`migration-order-decision-r4-p1-01.md`に作成済みである。V83実在を根拠にS10=V84、S12〜S17=V85〜V90へ同期し、V82は欠番として保持する。
- repo-known environmentの証跡、正式decision、予約表/全派工資料/legacy fixture同期後に`SpecDispatchConsistencyTest`は9/0/0/0へ復帰している。
- R10独立確認まではdeploy freezeを維持する。T061はV84で開始し、R4-P1-01がVERIFIED_CLOSEDになるまでDDL/production変更を行わない。
- 外部ownerが未登録のstaging/production/legacy環境を提示した場合は、その環境のread-only証跡が揃うまで本packetのscope完了を取り消し、正式decisionの本番適用可否を再Reviewへ戻す。
