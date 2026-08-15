# 障害モード別 runbook（HFP-03）

各障害の検出・復旧手順。まず `check-backup.sh --json` と `validate-restore.sh`
の状態を確認し、下表に従って対応する。

## 1. backup の gap（binlog 欠番 / archiver 停止）

- 検出: `check-backup.sh` の `binlog_event_lag_seconds` / `gap_count`
- 対応:
  1. archiver のログ（`archive-binlog.sh --stop-never`）を確認
  2. 不完全 raw file は起動時 size 照合で取り直しが走る（自動）
  3. gap が残る場合は、欠番以降の checkpoint は使わない
     （plan-restore が gap を検出して RPO_MISSED / 失敗にする）
- 復旧後: `check-backup.sh` で OK を確認し、drill で復元性を再確認

## 2. 運用ミス（誤削除・誤操作）

- 検出: retention の dry-run report と実際の削除の突合（report SHA bind）
- 対応:
  1. 削除は `retention.sh --apply` の二者承認を経た report のみ
  2. 誤削除に気づいたら、削除した snapshot を repository の backup から
     復元（無ければ escrow の repository key で別 repo から）
  3. incident を記録し、再発防止（承認プロセスの見直し）を実施

## 3. restore 失敗（dump / binlog replay / uploads）

- 検出: `restore.sh` が非 0、staging は read-only に隔離
- 対応:
  1. restore.log を確認（dump import / replay / uploads のどの段階か）
  2. 隔離された staging は read-only のまま保全
  3. 問題を修正して同一 plan で再実行（plan は immutable、再生成可）
  4. 再実行でも失敗する場合は古い checkpoint を選んで再計画

## 4. validation 失敗（Flyway / counts / markers / hash）

- 検出: `validate-restore.sh` が `FAILED_VALIDATION`
- 対応:
  1. どの check が FAIL したかを確認（flyway / check_table / counts /
     markers / uploads_hash / references / app_smoke）
  2. marker-after が検出された場合は復旧点の取り直し
     （より新しい checkpoint で再計画）
  3. uploads_hash FAIL は staging の保全 → 元の snapshot から再展開
  4. `mysqladmin ping` のみの代替確認は不可（drill が拒否する）

## 5. cutover rollback

- 条件: write-enable 前（CUTOVER_STATE が rolled-back でない・write-enabled でない）
- 手順: `rollback-cutover.sh --plan <plan-id>`
  - 旧環境の read-only smoke が PASS した場合のみ `rolled-back`
  - write-enabled 後の rollback は禁止（新規 transaction を失う）
- 失敗時: rollback 拒否のまま、新環境側の修正を進める

## 6. key loss / key rotation 失敗

- 検出: restic 操作が password エラー / `rotate-key.sh` が非 0
- 対応:
  1. escrow から旧キーを取り出し、復元確認（`restic restore --verify`）
  2. `rotate-key.sh --new-key-file <escrow の新キー>` を再実行
  3. 失敗時は切替えは行われない（旧キーで運用継続）
- 定期: 四半期に 1 回、key rotation を実施し復元確認

## 7. write-enable 失敗（cutover 中）

- 状態: `single-writer` のまま（write-enabled には遷移しない）
- 対応:
  1. WRITE_ENABLE_COMMAND（version 管理された executable）のログを確認
  2. 修正後、cutover.sh を再実行（single-writer から write-enabled へ）
  3. 新規 transaction はまだ発生しないため rollback も可能
     （rollback-cutover.sh、write-enable 前のみ）
