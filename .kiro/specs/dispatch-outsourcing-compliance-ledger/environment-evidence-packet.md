# R4-P1-01 environment Flyway evidence packet

## Status

`INCOMPLETE / ENVIRONMENT_EVIDENCE_REQUIRED`（2026-08-09、秘密情報なし）。

本packetは、S10のV82予約とS11のV83実在について、各environmentの
`flyway_schema_history`をread-onlyで照合するための証跡台帳である。全environmentのowner証跡が揃うまで、V82先行適用・V84以降への採番繰上げのいずれも決定しない。

## Inventory scope and closure

repoから確定できる環境区分は、application.ymlの`local-default`、CI workflow/Testcontainersのephemeral MySQL、ならびにR10が要求するstaging・production・other legacy/deploymentである。非localの実environment名、owner ID、接続先はrepoに存在しないため、ownerからのinventory declarationが提出されるまで、これらを仮区分のまま保持する。未確認のenvironmentを「不存在」や「V83未適用」とは扱わない。

各行は、environment ownerが次のいずれかを明示した時点で確定する。

- `owner ID or approved role`（実在の個人名をcodeへ固定しない）
- environmentの正式名称と種別（CI / staging / production / legacy等）
- read-only capture時刻とV82/V83の同一schema結果

local-defaultのexecutorは、環境ownerを代行するものではなく、workspaceから既定DBをread-only確認した主実装AIである。

## Environment inventory

| environment | owner/evidence status | V82 | V83 | latest successful migration | capture / note |
|---|---|---|---|---|---|
| local-default (`localhost:3306/ses_manager_db`) | **COLLECTED**（read-only JDBC、secrets excluded） | rowなし（version/success/installed_on/checksum=N/A） | rowなし（version/success/installed_on/checksum=N/A） | V74 / success=true / installed_on=`2026-08-02 00:35:29` / checksum=`559443363` | capture `2026-08-09T08:47:26Z`。executor/owner role=`主実装AI（local read-only verifier; environment owner approval not claimed）`。`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`未設定のためapplication.yml既定値を使用。V82/V83を変更していない |
| CI / Testcontainers MySQL | **MISSING_OWNER_EVIDENCE**（owner ID/role未提出） | 未提出 | 未提出 | 未提出 | CI実行環境のephemeral DBについて、実行jobのread-only出力が必要 |
| staging | **MISSING_OWNER_EVIDENCE**（正式environment名・owner ID/role未提出） | 未提出 | 未提出 | 未提出 | repo内に接続先・owner・credentialなし |
| production | **MISSING_OWNER_EVIDENCE**（正式environment名・owner ID/role未提出） | 未提出 | 未提出 | 未提出 | repo内に接続先・owner・credentialなし。productionへ接続・変更していない |
| other legacy / deployment environments | **MISSING_OWNER_EVIDENCE**（inventory・owner ID/role未提出） | 未提出 | 未提出 | 未提出 | environment inventoryとownerの提示が必要 |

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

- 全environmentの提出前はdeploy freezeを維持する。
- 全environmentでV83未適用が確認された場合だけ、V82を先にmerge/applyしてからV83へ進む正式decisionを作成する。
- 1つでもV83適用済みの場合は、実在latestより後の未使用番号へ予約表・README・parallel plan・全派工資料を同一decisionで繰り上げ、legacy fixtureを追加する。
- 証跡・正式decision・予約表/全派工資料/legacy fixture同期後に`SpecDispatchConsistencyTest`をPASSへ戻し、R10へ再Reviewを依頼する。
- 証跡取得前の採番変更、V82作成、T061、DDL、production変更は禁止する。
