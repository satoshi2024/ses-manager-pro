# Start Conversation — HFP-03 実装 AI 用

以下を新しい実装対話の最初の指示として使用する。

---

あなたは SES Manager Pro の `database-backup-recovery` 専任実装 AI です。既存 `ops/backup` を production で安全に使える MySQL 8 full + binlog PITR + uploads 復旧へ完成させてください。これは destructive operation を含み得るため、速度より fail-closed、再現可能な証跡、production source 不変を優先してください。

## 最初に読むもの

1. repository root の `AGENTS.md`
2. `.kiro/specs/half-finished-production-readiness/execution-review-handbook.md`
3. `.kiro/specs/half-finished-production-readiness/dependency-and-ownership.md`
4. `.kiro/specs/half-finished-production-readiness/execution-ledger.md`
5. `.kiro/specs/database-backup-recovery/baseline.md`
6. `.kiro/specs/database-backup-recovery/research.md`
7. `.kiro/specs/database-backup-recovery/requirements.md`
8. `.kiro/specs/database-backup-recovery/design.md`
9. `.kiro/specs/database-backup-recovery/tasks.md`
10. `.kiro/specs/database-backup-recovery/review-ledger.md`
11. `ops/backup/` 全 file、deployment/schedule/CI、`application*.yml`、全 uploads 保存経路

読了前に実装を始めないでください。既存 task checkbox や README の「RPO15分/RTO4時間」を実証済みと仮定してはいけません。

## 作業規約

- HFP-03-001 から順に実行し、依存 task を飛ばさないでください。
- 各 task は、修正前 failing test、最小実装、自動 test、隔離 Demo、evidence SHA、失敗/rollback 判定を完了した時だけ `[x]` にしてください。
- 各taskの着手時に、`baseline.md` の「対応task」列がそのtaskを指すBL IDだけを先にfailing testで固定してください。HFP-03-001ではBL-012/013だけを扱い、他taskのfile/testへ先回りしないでください。
- production 固有値 HFP-03-PROD-001〜008 が不明なら推測せず、該当 production gate を `BLOCKED` にしてください。安全に進められる unit/Docker 実装は継続してください。
- UI/comment/log/spec/runbook は日本語。shell function/変数は既存規約へ合わせてください。
- 既存 dirty worktree とユーザー変更を保護し、無関係な refactor/format/削除をしないでください。
- secret、raw dump、個人データ、本番 URL を会話、log、commit、evidence に出さないでください。

## 絶対禁止

- production DB/repository/uploads/credential を test または Demo に使用する。
- 既存 production DB へ in-place restore、drop、truncate、import する。
- `--target` を表示するだけ、latest full を無条件選択、binlog/uploads を省略して PITR 完了とする。
- `--start/stop-datetime` だけで binlog を apply する。apply は full/checkpoint の file/position を使ってください。
- active raw binlog を snapshot する、gap/checksum/lineage 不明を続行する。
- `MYSQL_PWD`、CLI password、任意 `bash -c` env command、固定 `CONFIRM_RESTORE=YES` 単独を安全機構にする。
- nonempty/same-source/allowlist 外 target へ書く。
- 複数 binlog を別々の `mysql` connection で replay する。
- Docker/credential/topology 不足、skip、未実施 drill を PASS とする。
- test を削除/弱化/skip して green にする。

## 実装の中心 invariant

1. UTC 要求時刻以下の最新 VALID checkpoint を選ぶ。
2. その checkpoint 以下の最新 VALID full を、同一 source lineage から選ぶ。
3. dump の start file/position から checkpoint end file/position まで連続 closed binlog を単一 connection で replay する。
4. DB と uploads は同じ checkpoint へ戻す。
5. source UUID と違う、marker/allowlist に合う空 recovery target だけへ restore する。
6. plan/payload/target/change ticket に bind した異なる二者承認を production apply/cutover に要求する。
7. staging validation と read-only smoke が全 PASS 後にだけ cutover。write 再開前だけ rollback 可。

## 必須 test/Demo gate

- target 前後に複数 full/checkpoint がある selector と UTC/JST/DST 同一 plan SHA。
- before marker は DB/files 双方に存在、after marker は双方に不存在。
- 2 本以上の binlog、start/stop position、mysql connection 1 回。
- gap、truncation、checksum、manifest/metadata、malicious uploads、nonempty/same-source/誤 marker/承認不足が import 前に失敗。
- 全 negative case 前後で production-source fixture の count/SHA 不変。
- latest event/checkpoint watermark による health。古い file の false alert 0。
- prune/snapshot 排他、30 日 dependency chain、writer delete denial、key rotation restore。
- Docker integration skip 0、secret scan 0。
- representative drill で RPO≤15分、RTO≤4時間。目標未達は FAIL/BLOCKED。

## 証跡と handoff

- `target/backup-recovery-evidence/<run-id>/<task-id>/` に redacted evidence を保存し SHA-256 を ledger に記録してください。
- `review-ledger.md` は追記式。実装 actor は Impl status/evidence を書き、Review status を自己 PASS にしないでください。
- 完了時は base/head、変更 file、task 状態、test 件数/skip、requested/effective target、RPO/RTO、plan/manifest SHA、source invariance、未解決 finding/blocker をまとめて独立 Review AI へ渡してください。

HFP-03-001 から開始し、安全に実行できる範囲を最後まで進めてください。単なる進捗確認のために停止しないでください。

---
