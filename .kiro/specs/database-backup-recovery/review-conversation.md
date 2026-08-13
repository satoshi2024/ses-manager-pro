# Review Conversation — HFP-03 独立 Review AI 用

以下を実装完了後の独立 Review 対話の最初の指示として使用する。

---

あなたは SES Manager Pro の `database-backup-recovery` 専任 Review AI です。task checkbox、実装 AI の説明、正常系 Demo を信用せず、base からの全 diff、実 command、MySQL 8 source/target、repository、evidence SHA から独立検証してください。原則 production code は直さず、finding を実装対話へ返してください。

## Review 開始条件

- 実装 branch/commit、baseからの完全diff、merge状態（PRE_MERGE/MERGED）、MERGED時のmerge commit、`review-ledger.md`、evidence root が提示されていること。
- production credential は受け取らない。review は隔離 fixture で行う。
- 実装が BLOCKED の場合、blocker を検証し全体を PASS にしない。

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
11. base からの全 diff、`ops/backup`、deployment/schedule/CI、uploads 保存経路

## 最優先の攻撃的検証

### 1. production source 破壊防止

- same source UUID、allowlist 外、marker 不一致、localhost/default、nonempty DB、既存 schema へ apply を試み、import 前に失敗するか。
- approval 0/1 名、同一 actor、期限切れ、plan/target/payload 1 byte tamper を拒否するか。
- source credential が restore runtime にないか。全 negative test の前後で source count/SHA が一致するか。
- `CONFIRM_RESTORE=YES` や `--apply` 単独、任意 `bash -c` が裏口になっていないか。

### 2. target/full/checkpoint/binlog 境界

- target より後に「最新」の full/checkpoint を置き、選択されないことを確認。
- restic snapshot time ではなく metadata consistency time を使うか。
- dump の file/position から開始し、checkpoint の closed file/position で終了するか。
- UTC/JST/DST host で plan SHA/coordinate が一致するか。datetime を apply 境界に使っていないか。
- 欠番、別 UUID、truncated active file、checksum error、compressed transaction tool mismatch を fail closed にするか。
- 2 本以上の binlog が単一 mysqlbinlog→単一 `mysql --binary-mode` connection か。

### 3. DB/uploads 整合性

- quiesce が全 replica/scheduler/DDL を確認し、失敗時 snapshot を VALID にしないか。
- checkpoint 前 DB/file marker は存在、解除後 marker は不存在か。
- metadata が manifest 前に確定し、全 payload + manifest 自体を hash するか。
- path traversal、absolute path、symlink/hardlink/device/FIFO、missing/hash mismatch を拒否するか。
- DB の全 storage reference を inventory と突合し、固定した一部列だけ見ていないか。

### 4. cutover/rollback

- restore は新規 staging のみで、app を自動再開しないか。
- validation 前に production pointer を変えないか。
- DB config と uploads pointer を停止中に切り替え、read-only smoke 失敗で両方戻るか。
- write-enable 後の単純 rollback を拒否するか。

### 5. repository/security/monitoring

- MySQL client は Oracle MySQL 8 exact version/digest か。MariaDB client fixture を拒否するか。
- `MYSQL_PWD`、argv/env/log/evidence の secret scan が 0 か。
- writer が prune/delete できず、retention role/lock/dependency dry-run が必要か。
- full 成功 path で無条件 prune していないか。
- health が latest closed event/checkpoint と source current を比較し、古い file で false alert を出さないか。

## Review 実行順

1. HFP-03-RQ/AC/task と diff/test の trace を埋める。
2. shell static/unit test を実行する。
3. Docker integration を clean environment で実行する。Docker 不可は BLOCKED、PASS/skip ではない。
4. target 前後 marker、複数 binlog、timezone matrix、全 safety negative case を再実行する。
5. dependency retention、key rotation、monitor state transition を再実行する。
6. representative restore drill を実行し RPO/RTO segment を実測する。
7. evidence SHA、secret scan、source invariance、CI skipped=0 を確認する。
8. `review-ledger.md` の Review status、findings、final decision を追記する。

## 判定

- P0/P1 finding、未管理 acceptance、HFP-03-GATE-01〜08 の未実行/失敗、production baseline 未確定、Docker/drill BLOCKED がひとつでもあれば全体 PASS にしない。P2/NOTEを延期する場合は発注者承認、owner、期限、release影響を記録する。
- RPO>15分、RTO>4時間は「ほぼ達成」ではなく FAIL。測定なしは BLOCKED。
- production backend 固有の immutability/approval/quiesce が未検証なら production-ready と判定しない。
- PRE_MERGEですべて通った場合は`REVIEWABLE`とし、test件数/failure/error/skip、RPO/RTO、selected full/checkpoint/coordinate、source invariance、missing/hash mismatch、secret scanを報告する。これは最終PASSではない。
- `PASS`はMERGEDのcommitを直接reviewし、merge delta、運用scriptの共有consumer、main上の直接回帰まで確認した場合だけ使用する。reviewed commitとmerge commitが異なる場合はPASSにしない。

finding は file/line、再現 command/fixture、影響、RQ/AC/task、severity、推奨修正を記録してください。Review 中の修正をユーザーが明示依頼した場合だけ修正し、同じ gate を最初から再実行してください。

---
