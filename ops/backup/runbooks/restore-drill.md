# Restore drill（HFP-03-012）

## 1. 目的

`mysqladmin ping` では検出できない「実際に復元できるか」を、本番相当の環境で
定期的に検証する。drill は実 script（plan-restore / restore / validate /
cutover）を実際に実行し、RPO / RTO segment 時間・markers・evidence SHA を
記録する。

## 2. スケジュール・オーナー・エスカレーション

| 項目 | 値 |
|---|---|
| 頻度 | 月次 1 回（毎月第 1 営業日） + リリース前 |
| オーナー | バックアップ運用担当 |
| エスカレーション | drill FAIL 時は当日中に incident commander へ。RTO/RPO 超過も同様 |
| 対象環境 | 本番と同一構成の隔離環境（source/target 別コンテナ・専用ネットワーク） |

## 3. 実行手順

```bash
# 1. plan を生成（drill の plan step は検証のみ）
plan-restore.sh --target <復旧点>   # plan_id を記録

# 2. 二者承認 claim を発行（plan SHA / target UUID に bind）

# 3. drill を実行（isolated target に対して）
restore-drill.sh --plan <plan-id> \
  --approval <claim1.json> --approval <claim2.json> \
  --report-dir <evidence-dir>
```

drill は以下の順に実 script を実行する:

1. plan 検証（`plan::verify` + `APPLYABLE`、RPO 記録）
2. repository integrity（`restic check --read-data`）+ base full の restore verify
3. restore（dump import + binlog replay + uploads staging）
4. validate + read-only smoke（`READY_FOR_CUTOVER` のみ成功）
5. read-only cutover リハーサル → rollback（write-enable は実施しない。
   write-enable が動いた場合は drill は失敗）

## 4. 成功条件（drill report）

- `state == SUCCESS`
- `rto_ok == true`（total_seconds <= RTO_SECONDS、既定 4h）
- `rpo_ok == true`（plan の rpo_seconds <= RPO_MAX_SECONDS、既定 15m）
- segments が 5 つ（plan / integrity / restore / validate / cutover）
- いずれか失敗・skip・evidence 欠如・目標超過で非 0 終了

## 5. 証跡

- `drill-report.json`（plan_id / plan_sha256 / RPO / RTO / segments）
- `integrity.log` / `restore.log` / `validate.log` / `cutover.log`
- evidence SHA は review ledger に記録する（secret は含めない）
