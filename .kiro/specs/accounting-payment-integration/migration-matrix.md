# S15 Migration Matrix — V106 checksum不変・company境界repair

## 目的

V106/V106.1の適用済みchecksumを変更せず、freee connectionの正規identityを
`tenant × legal_entity × provider × product × external_company_id`へ収束させる。
通常FlywayのS15 migrationはV106/V106.1/V106.2だけとし、V105.4は作成しない。
V106到達前のlegacy preflightは`sql/runbook/v106_legacy_freee_preflight.sql`をFlyway外で実行し、
S16のV107は使用しない。

| 形状 | 開始状態 | 実行経路 | 期待結果 | 直接検証 |
|---|---|---|---|---|
| Fresh | consolidated V1 | V106 → 旧V106.1 → V106.2 | 最終UNIQUEが`external_company_key`を含む。NULL companyのsoft-delete再作成可 | fresh latest、index/column assert、historyにV105.4なし |
| Historical legacy | V105.3相当。`m_integration_connection`未作成、`t_freee_connection`に同一productでcompany_id違い2件 | **Flyway前にpreflight runbook** → V106 → 旧V106.1 → V106.2 | V106で衝突せず、退避行をV106.2が復元し、2 companyがactive。Flyway historyはV106以降のみ | historical fixtureでrunbook実行、全履歴とcompany row再読 |
| Old V106.1 applied | V106/V106.1適用済み。旧UNIQUEとbackupが存在 | V106.2のみ | backupのcompany別行を最大1件ずつ復元し、company-aware UNIQUEへforward repair | 旧checksum、行数、index、再実行 |
| Partial | V106.1/V106.2の列・index・stagingが一部適用 | rollback runbookまたはrepair → V106.2 | 不足列・indexの存在に応じて再開し、重複作成なし | partial各境界、failed history、repair |
| Backfill | NULL company、同一company重複、soft-deleted row、legacy preflight row | V106.2 | survivor優先順位を維持し、明示NULLを別companyへ誤統合しない | NULL/同一/異なるcompany、soft-delete再作成 |

## 不変・禁止事項

- `V106__accounting_payment_integration.sql` のblob: `5f5f0bd615de091da5d1bd3ccb828180374c3032`を変更しない。
- `V106_1__accounting_integration_snapshot_and_slot.sql` の旧適用済みblob: `193b51d4904dbf16c9c1dbbfff3decb80f480e04`を復元し、以後変更しない。
- V106.2はV106.1の旧動作を上書きせず、後段のcompany境界修復だけを担う。
- `V105.4__accounting_legacy_freee_preflight.sql`は通常Flyway locationに存在してはならない。
- legacy preflightはFlyway historyへversionを記録しない運用runbookであり、V106適用前に一度だけ実行する。
- `application.yml`のFlyway out-of-orderは明示的にfalseとし、適用済みV106.1環境ではV106.2だけをpendingにする。
- preflight runbookは`m_integration_connection`が既に存在するfresh/baseline経路で業務データを変更しない。
- `flyway_schema_history`にV105.4が存在しないことをupgrade契約とする。
- `external_company_id IS NULL`は明示NULLのまま同一identityとして扱い、soft-delete後の再作成だけ許可する。

## rollback

- 旧V106.1へ戻す場合は既存`sql/runbook/v106_1-rollback.sql`を使う。V106.1の責務外である`external_company_key`のrollbackは実行しない。
- V106.2適用後は`sql/runbook/v106_2-rollback.sql`でV106.2のcompany key/indexだけを戻す。V106/V106.1のFlyway history/checksumは変更しない。
