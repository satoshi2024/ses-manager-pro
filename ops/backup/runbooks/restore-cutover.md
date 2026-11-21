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
   どちらの場合も、`status` が `target=<旧版>` を出すことを確かめてから旧環境の app を起動する。DB と pointer の
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
