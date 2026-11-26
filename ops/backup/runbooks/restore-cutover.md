# 復元カットオーバーとロールバック（HFP-03-009）

本ドキュメントは、復元検証（`validate-restore.sh`）が `READY_FOR_CUTOVER` を返した後、
旧環境から新環境（recovery target）へ write 可能な状態へ移行する手順を説明する。

## 1. 前提

- `validate-restore.sh` の結果（`VALIDATION_STATE_FILE`）が `READY_FOR_CUTOVER` であること
  - report の `checks.flyway_history_required` が `true` かつ `checks.flyway` が `PASS` であること。
    `cutover.sh` がこの 2 点を `jq` で検査し、満たさない report では cutover しない。
    `flyway_schema_history` の無い DB を Flyway 有効なアプリで起動すると、`baseline-on-migrate` で誤って
    baseline され migration が再適用され得る（G-15）。`checks.flyway_latest` が想定の最新 version であることは
    二者承認者が確認する。
  - `checks.content_hash` が `PASS`（production では `.env.backup` の `CONTENT_HASH_TABLES` を設定して backup と
    復元検証を行う。設定時は記録が無い・欠けている checkpoint を validate が FAIL にする）
  - `checks.schema_fingerprint` が `PASS`（G-04。設定によらず常に必須。`cutover.sh` も `jq` で検査し、満たさない report
    では cutover しない。`FAIL` のときは validate のログの「差異のある区分」と、記録時・現在の server の版・DB の既定値を
    確認する。README「schema fingerprint（G-04）」の fail-closed になる場合と「restore target role の権限」を参照）
- 復元先（recovery target）の MySQL サーバの時刻帯が production と同じ `+09:00` であること
  （`SELECT @@global.time_zone;`。2026-11-13 決定により DBA が設定する）
- 目標は RPO 15 分 / RTO 4 時間（2026-11-13 決定。DBA Lead / Operations の署名は PENDING）。
  超過した場合も cutover の判断は incident commander が行い、超過時間を記録する
- plan（`PLANS_DIR/<plan-id>.json` + `.sha256`）が存在し、`plan::verify` を通ること
- 二者承認（同一 plan SHA / 同一 target UUID に bind された 2 名の claim）が揃うこと
- 復元後の最初の app の起動に使う task definition は、緊急の総スイッチ `FEATURE_ENABLEMENT_ALLOWED=false`（`deploy-runbook.md` §1.0 の 42）を設定した revision を用意しておくこと（§4.7 の 1。`true` のまま起動すると、復旧点より後に取り消された署名や閉鎖された開放単位が復元した DB の値のまま開き得る）
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

`write-enable 前`で、`CUTOVER_STATE_FILE` があり state が initial / staged / read-only-smoke-passed / single-writer の
ときに限り、旧環境へ戻せる。state file が無ければ拒否する（`cutover.sh` が state を書く前に失敗した場合は DB が
切り替わっていないので、uploads の逆向きの switch だけを行う。§4.5.1 の 5）。`write-enabled` 後の rollback は禁止。

```bash
rollback-cutover.sh --plan <plan-id>
```

必須環境変数: `PLANS_DIR`, `CUTOVER_STATE_FILE`, `OLD_ENV_SMOKE_SCRIPT`

動作:

1. plan 検証
2. state が write-enabled / rolled-back でないことを確認
3. 旧環境の read-only smoke（`OLD_ENV_SMOKE_SCRIPT`）が PASS すること
4. `state=rolled-back` を書く（新環境は activation されない）

## 4.5 uploads の版の切替え（uploads-cutover.sh。RB-W 追加修正 C）

production の uploads は、uploads の根の下の版の directory（`v0`、`v1`、…）と、相対・1 段の pointer
`current -> v<n>` で構成する（app は `UPLOAD_BASE_PATH=/app/uploads/current`。README「uploads の配置と mount」）。
復元した uploads は今の版を上書きせず、新しい版として置いてから pointer を切り替える。restore 主体（uploads の access
point を app と同じ uid で読み書きで mount する）が実行する。

### 4.5.1 DB と uploads の切替えの順序（正本。README と deploy-runbook §1.6 の11 (f) はここを引く）

DB の cutover（`cutover.sh` / `rollback-cutover.sh`）と uploads の pointer の切替え（`uploads-cutover.sh switch`）は、
**pointer が指す版と app が使う DB が常に一対になる**ように、次の順序だけで行う。`cutover.sh`・`rollback-cutover.sh` は
pointer に触れず、`uploads-cutover.sh` は app の停止も DB との対も確かめないため、順序は人が守る。

理由: 孤児清掃（`FileCleanupServiceImpl`。scheduler）は `current` が指す版だけを見て、DB が参照しない古い file を消す。
DB を復旧点の DB に替えたのに pointer が旧版のまま、または DB を戻したのに pointer が新しい版のままで scheduler が動くと、
その版の中の「今の DB が参照しない」file（旧版の復旧点より後の file 等）が消える。read-only smoke も、新しい DB と
新しい版の組合せで行わなければ確かめたことにならない。

1. **app と scheduler を止める**（deploy-runbook §2 の1〜4。全 instance の停止と DB への app の接続 0 件）。
   止まるまで 3 以降へ進まない（app は起動時に pointer の下の directory を作り、scheduler は孤児清掃を動かす）。
2. **stage**（今の版と pointer は変えないため、1 の前に済ませてもよい）:
   `uploads-cutover.sh stage --from <restore.sh の uploads_ready> --root <uploads の根> --version <新しい版>`
   （`validate-restore.sh` が `READY_FOR_CUTOVER` を返した plan の `uploads_ready` を使う）。
3. **switch と確認**:
   `uploads-cutover.sh switch --root <uploads の根> --to <新しい版> --expect <今の版>` の後、
   `uploads-cutover.sh status --root <uploads の根>` が rc 0 で `target=<新しい版>` を出し、`leftover=` と `error=` が
   無いことを確かめる。出力の `rollback=` の command を change ticket に控える。
4. **`cutover.sh`**（§3）: read-only smoke は、新しい DB と 3 で切り替えた新しい版の組合せで行われる。smoke に使う app は
   scheduler・Flyway・mail・外部 API・cleanup を止めて起動し（`validate-restore.sh` の前提）、upload root は
   `UPLOAD_BASE_PATH=/app/uploads/current` を使う。
5. **write-enable の前に戻すとき**: 場合によって DB 側の操作が違う。
   - read-only smoke の失敗で `cutover.sh` が `rolled-back`・exit 3 で止まった場合: DB の状態は `cutover.sh` が既に
     `rolled-back` にしているので、`rollback-cutover.sh` は実行しない（「すでに rolled-back 済み」で拒否される）。
     逆向きの switch（`--to <旧版> --expect <新しい版>`。3 で控えた `rollback=`）を行い、`OLD_ENV_SMOKE_SCRIPT` を
     手で実行して旧環境が健全であることを確かめる。
   - `cutover.sh` が state を書く前に失敗した場合（初回で `CUTOVER_STATE_FILE` が無い）: DB は切り替わっていない。
     `rollback-cutover.sh` は使わず（state file が無いと拒否される）、逆向きの switch だけを行う。
   - `staged`・`read-only-smoke-passed`・`single-writer` から人の判断で戻す場合: `rollback-cutover.sh`（§4）と
     逆向きの switch を**必ず対で**行う。
   いずれの場合も、`status` が `target=<旧版>` を出すことを確かめてから旧環境の app を起動する。DB と pointer の
   どちらか一方だけを戻した状態で app・scheduler を起動しない。
6. **`write-enabled` の後**は uploads も戻さない（DB と同じ。§2 の「`write-enabled` からの rollback は禁止」）。旧版は
   消さずに残す（削除は deploy-runbook §4.4 の D15 の手順だけ）。
7. **app と scheduler を起動する前に、DB と pointer が一対であることを確かめて記録する**:

   ```bash
   # DB 側: 状態と plan（write-enabled なら新しい DB、rolled-back なら旧環境）
   jq -r '"state=\(.state) plan_id=\(.plan_id)"' "$CUTOVER_STATE_FILE"
   jq -r '"checkpoint=\(.effective_checkpoint.restic_snapshot_id)"' "$PLANS_DIR/<plan-id>.json"
   # uploads 側: pointer の指す版（rc 0、leftover= と error= が無いこと）
   uploads-cutover.sh status --root <uploads の根>
   ```

   期待: `state=write-enabled` なら `target=<新しい版>`（その plan の `uploads_ready` から stage した版）、
   `state=rolled-back` なら `target=<旧版>`。plan-id・checkpoint・版の名前・`status` の出力を対として change ticket と
   配備記録に残す。一致しなければ起動しない。`state` がそれ以外（`staged`・`read-only-smoke-passed`・`single-writer`）
   のときも起動しない。この状態から `cutover.sh` をやり直すことはできない（開始できるのは initial・staged だけ）ので、
   5 に従って `rollback-cutover.sh` と逆向きの switch を対で行い、restore からやり直す。`CUTOVER_STATE_FILE` が無い
   （`cutover.sh` が state を書く前に失敗した）ときは DB は旧環境のままなので、`status` が `target=<旧版>` であることを
   確かめて記録する。

### 4.5.2 各 command の性質

- `stage` は、既にある名前（今の版を含む）・pointer の名前・1 段でない名前、symlink や特殊 file を含む複製元を拒否する。
  複製は一時 directory に行い、内容（相対 path と SHA-256）が一致してから 1 回の rename で版にするため、失敗しても
  作りかけの版は残らない。
- `switch` は pointer が `--expect` と違う・解決できない（無い・実 directory に置き換わっている・絶対 path 等）、または
  切替え先が実 directory でなければ何も変えずに失敗する。pointer は前後とも必ずどちらかの版を指す（1 回の rename）。
- `status` は読むだけで、根の下に何も書かない。
- `switch` は切替え先の版の来歴（どの plan・checkpoint から stage したか）を確かめない。4.5.1 の 7 の記録で人が確かめる。

## 4.6 復元した DB の書込み静止の信号表と、backup の再開の前の権限（RB-QUIESCE）

復元した DB には、checkpoint の時点の書込み静止の信号表（V202 の `sys_backup_quiesce_request`・`sys_backup_quiesce_instance`・
`sys_backup_quiesce_ack`）の行が残り、backup account の権限は残らない（設計は `.kiro/specs/backup-write-quiesce/design.md` §3.8）。

1. **信号表の残りの行は手で消さない**。
   - 残る行: その checkpoint を取った静止要求（`REQUESTED` のまま。replay は checkpoint の閉じた binlog の coordinate までのため、解除の
     UPDATE は入らない）、その時に動いていた instance の行（`STOPPED` でない）と、その応答（`QUIESCED`）の行。
   - cutover の後に起動する app は、新しい UUID で instance を登録し、残った要求を lease の期限切れ（checkpoint の時刻から 60 秒で切れて
     いる）として無視する（INFO の log に要求の番号を出す。起動は待たない）。残った instance の行は heartbeat が古い（`QUIESCE_DEAD_SECONDS`
     の 300 秒を超える）ため、backup の provider は無視する。
   - cutover の後に最初に動く provider（最初の full・checkpoint）が、残った `REQUESTED` の要求を `EXPIRED` にしてから新しい要求を
     登録する（有効な要求は同時に 1 件だけ）。解除済みの行は 30 日（U-12）を過ぎると app が消す。
   - 停止口の行（`STOPPED` でない `gate_state = 'DISABLED'`）は、VALID な checkpoint の時点の DB には無い（あれば verify が失敗するため）。
     preflight の `checks.quiesce` が停止口の行を報告したら、復元に使った checkpoint を確かめる（片付けの SQL は deploy-runbook §4.7。その
     instance の task が止まっていることを確かめてから行う）。
2. **backup account と、要求の表への表単位の権限を付け直す**（backup を再開する前。DBA）。account と権限（`mysql.user`・`mysql.tables_priv`
   等）は対象 DB の dump に入らないため、復元先を新しい source にした後、その server に backup account（README「権限分離」の「source 側の
   account の権限」）があることを確かめ（無ければ作り）、要求の表への表単位の権限を付ける（deploy-runbook §3.4 と同じ文。同 §1.8 の
   RR-10 の例外。restore target role は backup に使わない）。
   ```sql
   GRANT INSERT, UPDATE ON <db>.sys_backup_quiesce_request TO '<backup account>'@'<host>';
   SHOW GRANTS FOR '<backup account>'@'<host>';   -- 要求の表への表単位の INSERT, UPDATE の行がある
   ```
3. **確かめてから backup を再開する**: `.env.backup` の接続先（`MYSQL_HOST` 等）を新しい source にし、`FLYWAY_VERSION` を復元した DB の
   `flyway_schema_history` の成功行の最大の version にする。`ops/backup/image/tool-image.sh compose run --rm preflight` が 0 で終わり、
   `checks.quiesce` が `status: "ok"`・`signal_tables: 3`・`request_table_grant: true` であることを確かめる（権限が無ければ exit 19）。
   新しい source の最初の backup は full にする（source が変わると lineage が変わり、旧 source の full は新しい source の PITR の基点に
   ならない）。app の起動の前なら `--app-stopped` を付け、起動の後なら付けない。その後に定期起動を再開し、最初の ONLINE の checkpoint が
   VALID になることを確かめる（deploy-runbook §4.7 の4 と同じ確認）。

## 4.7 復元後の機能の開放（総スイッチによる全閉と外部ログとの照合。FE-E04）

復元した DB の開放状態（`t_feature_enablement_state`）・署名と組（`t_feature_sign_off`・`t_feature_sign_off_bundle`）・設定（`t_feature_enablement_setting`）・履歴（`t_feature_enablement_event`）は復旧点の時点に戻る。復旧点より後・障害の前に取り消された署名や、手動・自動で閉鎖された開放単位は、そのまま起動すると「開」に戻るため、次の 1〜3 の順で全閉・外部ログとの照合・再開放を行う（設計は `.kiro/specs/feature-enablement-console/design.md` §13.1）。

1. **最初の app の起動は `FEATURE_ENABLEMENT_ALLOWED=false` で行い、全ての開放単位を閉じる**。
   - cutover と DB・uploads pointer の対の確認（§4.5.1 の 7）を終えた後の最初の app の起動は、task definition に `FEATURE_ENABLEMENT_ALLOWED=false` を設定して行う（`validate-task-definition.ps1 -Mode Actual -Phase Steady` も `false` を受け付ける）。
   - 起動時の評価（`FeatureEnablementStartupEvaluator`）が開いていた（`OPEN` / `OPEN_PENDING_RESTART`）開放単位を全て `AUTO_CLOSED`（理由 `MASTER_SWITCH_CLOSED`）にし（元から閉じていた単位は閉のまま保つ）、未決の申請（`PENDING`）を全て `CANCELLED` にする。起動 log に ERROR `errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED` が出、gauge `ses_feature_enablement_master_allowed` が `0` になる。
   - NF05 も起動の前の読取り（`IntegrationHubEnablementEnvironmentPostProcessor`）が総スイッチの `false` で両方向を閉じ（`MASTER_SWITCH_CLOSED`）、outbound の起動 barrier（`IntegrationHubOutboundStartupBarrier`）が `m_system_config` の `integration.hub.outbound.runtime.v1` を `v1|OFF|0|<指紋>` に書き直すため、次に開き直す起動では未送信の配信が隔離（`STARTUP_REVALIDATION_REQUIRED`）される。
   - 総スイッチが `false` の間、画面「機能の開放」（`/feature-enablement`）での開放・設定変更の申請と承認は 409 で拒否されるが、一覧・履歴の閲覧と、署名・組の登録・確認・取消し（`REVOKED`）は使える。
2. **復旧点より後の外部ログ（CloudWatch Logs）と照合し、取消しと閉鎖の理由を反映する**。
   - app は機能の開放の全ての遷移で構造化ログ `FEATURE_ENABLEMENT_EVENT`（単一行の `key=value` 形式: `tenant=<...> subjectType=<...> subjectKey=<...> subjectId=<...> eventType=<...> from=<...> to=<...> reason=<...> generation=<...> actorType=<...> actorUserId=<...> correlationId=<...>`）を標準出力へ出しており、DB とは独立に CloudWatch Logs（例: `/ecs/ses-manager-prod`。log group の保持期間は PITR で戻り得る binlog の保持期間以上にする。`deploy-runbook.md` §1.0・§1.7）に残る。
   - 復旧点（plan の `effective_checkpoint.consistency_time_utc` または PITR の target 時刻）より後・旧環境の停止までの期間の `FEATURE_ENABLEMENT_EVENT` を CloudWatch Logs Insights 等で抽出し、復元した DB の `t_feature_enablement_event`（復旧点までの行しか無い）と突き合わせる。
     ```text
     fields @timestamp, @message
     | filter @message like /FEATURE_ENABLEMENT_EVENT/
     | sort @timestamp asc
     ```
   - 突き合わせて次を行う。
     - **(a) 復旧点より後に取り消された署名・組（`subjectType=SIGN_OFF` または `subjectType=BUNDLE` で `eventType=REVOKED`）**: 復元した DB では `CONFIRMED` に戻っているため、`FEATURE_ENABLEMENT_ALLOWED=false` のまま画面「機能の開放」の「署名・受理」タブから、該当する署名・組を改めて取り消す（`REVOKED`。NF07 の署名・組は privacy-admin が行う）。復旧点より後に登録・確認されていた新しい署名・組が要る場合は、元の決定書・証跡から登録・確認し直す。
     - **(b) 復旧点より後に閉鎖された開放単位（`subjectType=FEATURE` で `eventType=CLOSED`（`to=MANUALLY_CLOSED`）または `eventType=AUTO_CLOSED`（`to=AUTO_CLOSED`））とその `reason=`（DB の `t_feature_enablement_event` 側では `reason_code` 列）**: 1 により開いていた開放単位は既に `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`（元から閉じていた単位は閉のまま）になっているが、復旧点より後に閉鎖されていた開放単位と理由（`MANUAL_SECURITY_INCIDENT` 等の手動閉鎖理由、条件の不成立、署名の失効等）を一覧にし、その原因が解消していることを確かめるまで 3 で開き直さない。
     - **(c) 復旧点より後に承認・反映された設定変更（`subjectType=SETTING` で `eventType=CHANGED`、例: `subjectKey=nf02-customer-portal.visible-since`）**: 復旧点の値に戻っているため、3 の後に必要なら「設定」タブで改めて申請・承認する。
   - 照合した時刻範囲、抽出した事象の一覧、再取消しした署名・組の ID、確認者を change ticket と配備記録に残す。
3. **`FEATURE_ENABLEMENT_ALLOWED=true` に戻して再起動し、必要な開放単位を二人で開き直す**。
   - 2 の照合と再取消しが済んだら、task definition の `FEATURE_ENABLEMENT_ALLOWED` を `true` に戻して再起動する（`true` に戻しても自動では 1 件も開かない）。
   - 原因の解消と条件の成立を確かめた開放単位だけを、画面「機能の開放」で申請者と別の承認者の二人で 1 件ずつ開き直す（NF05 を開く場合は承認の後に再起動し、起動の後に LB/WAF の `/external-api/**` の rule を合わせる。`deploy-runbook.md` §1.7 SRE-2）。

## 5. 判定早見表

| 状況 | 対応 | コマンド |
|---|---|---|
| validation が FAILED_VALIDATION | cutover 不可。restore のやり直し | `restore.sh` → `validate-restore.sh` |
| read-only smoke 失敗（cutover 中） | 旧環境へ rollback。uploads も逆向きの switch（§4.5.1 の 5） | cutover.sh が自動で `state=rolled-back`（exit 3）＋ `uploads-cutover.sh switch --to <旧版> --expect <新しい版>` |
| write-enable 前に旧環境へ戻す | rollback。uploads の逆向きの switch と必ず対で（§4.5.1 の 5） | `rollback-cutover.sh --plan <plan-id>` ＋ `uploads-cutover.sh switch --to <旧版> --expect <新しい版>` |
| write-enabled 後 | 戻れない（新規 transaction を失う）。uploads も戻さない（§4.5.1 の 6） | —（切戻しは次の正規復元手順で） |
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
