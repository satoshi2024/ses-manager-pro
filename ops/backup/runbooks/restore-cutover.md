# 復元カットオーバーとロールバック（HFP-03-009）

本ドキュメントは、復元検証（`validate-restore.sh`）が `READY_FOR_CUTOVER` を返した後、
旧環境から新環境（recovery target）へ write 可能な状態へ移行する手順を説明する。

## 1. 前提

- `validate-restore.sh` の結果（`VALIDATION_STATE_FILE`）が `READY_FOR_CUTOVER` であること
- plan（`PLANS_DIR/<plan-id>.json` + `.sha256`）が存在し、`plan::verify` を通ること
- 二者承認（同一 plan SHA / 同一 target UUID に bind された 2 名の claim）が揃うこと
- read-only smoke スクリプト（`APP_SMOKE_SCRIPT`）と write-enable provider
  （`WRITE_ENABLE_COMMAND`、version 管理された executable）が準備済みであること
- 旧環境の read-only smoke（`OLD_ENV_SMOKE_SCRIPT`）が PASS できること（rollback 用）

## 2. 状態機械（CUTOVER_STATE_FILE）

`CUTOVER_STATE_FILE`（単一の JSON）が cutover の単一の真実である。

```
initial -> staged（restore+validation 完了）
staged -> read-only-smoke-passed -> single-writer -> write-enabled
staged / read-only-smoke-passed / single-writer -> rolled-back（write-enable 前のみ）
```

- `write-enabled` からの rollback は禁止（新規 transaction を失うため）
- `rolled-back` からの再開は、restore + validation のやり直しが必要

## 3. 正常系（cutover.sh）

```bash
cutover.sh \
  --plan <plan-id> \
  --approval <claim1.json> --approval <claim2.json>
```

必須環境変数: `PLANS_DIR`, `CUTOVER_STATE_FILE`, `VALIDATION_STATE_FILE`,
`TARGET_HOST`, `TARGET_USER`, `TARGET_PASSWORD_FILE`, `TARGET_DATABASE`,
`APP_SMOKE_SCRIPT`, `WRITE_ENABLE_COMMAND`, `APPROVAL_PUBKEY_DIR`

動作:

1. plan 検証（`plan::verify` + `plan::status` == APPLYABLE）
2. validation report の state が `READY_FOR_CUTOVER` であることを確認
3. target UUID を取得し、claim の bind（plan SHA / target UUID / 有効期限）を検証
4. read-only smoke を実行 → 失敗時は `state=rolled-back` で exit 3（旧環境へ戻す）
5. `state=single-writer` へ遷移
6. `WRITE_ENABLE_COMMAND` を実行（失敗時は single-writer のまま中断）
7. `state=write-enabled` を書いて完了 JSON を出力

## 4. ロールバック（rollback-cutover.sh）

`write-enable 前`（initial / staged / read-only-smoke-passed / single-writer）に限り、
旧環境へ戻せる。`write-enabled` 後の rollback は禁止。

```bash
rollback-cutover.sh --plan <plan-id>
```

必須環境変数: `PLANS_DIR`, `CUTOVER_STATE_FILE`, `OLD_ENV_SMOKE_SCRIPT`

動作:

1. plan 検証
2. state が write-enabled / rolled-back でないことを確認
3. 旧環境の read-only smoke（`OLD_ENV_SMOKE_SCRIPT`）が PASS すること
4. `state=rolled-back` を書く（新環境は activation されない）

## 5. 判定早見表

| 状況 | 対応 | コマンド |
|---|---|---|
| validation が FAILED_VALIDATION | cutover 不可。restore のやり直し | `restore.sh` → `validate-restore.sh` |
| read-only smoke 失敗（cutover 中） | 旧環境へ rollback | cutover.sh が自動で `state=rolled-back`（exit 3） |
| write-enable 前に旧環境へ戻す | rollback | `rollback-cutover.sh --plan <plan-id>` |
| write-enabled 後 | 戻れない（新規 transaction を失う） | —（切戻しは次の正規復元手順で） |
| rolled-back 後の再開 | restore からやり直し | `restore.sh` 以降を再実行 |

## 6. production 固有の注意（HFP-03-PROD-004 と併用）

- `WRITE_ENABLE_COMMAND` は version 管理された executable を指定し、実運用では
  deployment の write 再開手順を実装すること（隔離環境向けは
  `providers/write-enable-local.sh`）。
- rollback 時の `OLD_ENV_SMOKE_SCRIPT` は、旧環境（直前まで write を受けていた環境）の
  read-only 健全性確認に用いる。PASS しない限り rollback してはならない。
- `CUTOVER_STATE_FILE` は root-only（0600）で保存され、1 名の operator による
  改変（例えば「state を write-enabled へ書き換える」）を防ぐ前提ではない。
  承認と状態遷移の監査は監査ログ/承認ログ側で行うこと。
