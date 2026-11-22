# backup 監視 runbook（HFP-03-005）

## 監視方法

`check-backup.sh --json` を scheduler から 1 分間隔で実行し、exit code で alert を振り分ける。

| exit | 状態 | 対応 |
|---|---|---|
| 0 | OK | なし |
| 1 | WARN | 目視確認（full 20h / checkpoint 20m / repo check 7d / drill 90d 超過） |
| 2 | CRITICAL | 即時対応（full 26h / checkpoint 30m / binlog lag / gap / heartbeat 停止） |
| 3 | UNKNOWN | source 接続不能。監視自体の障害として対応 |

## 判定基準（watermark ベース）

- 「directory に古い file がある」ことでは FAIL にしない。
- full age: 最新 VALID full の consistency_time からの経過。
- checkpoint age: 最新 VALID checkpoint の consistency_time からの経過。
- binlog lag: 最新 archived closed file と source 現行 file の差（active 1 file は正常）。
- gap: binlog-index の suffix 欠番。
- archiver heartbeat: `archive-state.json.heartbeat` の mtime（30 秒間隔で touch）。
- rpo_available: checkpoint age <= 15 分 かつ gap 0 かつ source 到達可能。

## 対応手順

job の起動・状態確認は、障害対応中も `ops/backup/image/tool-image.sh compose` 経由で行う（docker compose を
直接実行しない）。`run` / `up` は compose の pin（content ID）とローカルのツールイメージを照合してから起動する。
照合に失敗した場合は image を読み込み直す（README「ツールイメージの build・配布」）。pin 照合を省いて起動しない。

### CRITICAL: checkpoint 30 分超 / RPO 不達

1. `check-backup.sh --json` で reasons を確認。
2. `ops/backup/image/tool-image.sh compose ps` で checkpoint / binlog service の状態確認。配備の間（deploy-runbook §2 の0〜§4.7）に
   定期起動を止めた計画停止なら、配備記録の停止の時刻と照らし、抑止の対象として扱う。
3. checkpoint 停止中なら手動実行: `ops/backup/image/tool-image.sh compose run --rm checkpoint`。
   job が書込み静止で失敗している場合（stderr の「書込み静止に失敗したため checkpoint を発行しません」「静止が途中で途切れたため
   VALID にしません」と `[quiesce-db]` の行）は、下の「書込み静止（RB-QUIESCE）」の該当する対応を行う。
4. 復旧後、次の checkpoint が 15 分以内に VALID になることを確認。

### CRITICAL: archiver lag / heartbeat 停止

1. `ls -la $BINLOG_RAW_DIR` で最新 file と source の差を確認。
2. archiver 再起動: `ops/backup/image/tool-image.sh compose up -d binlog`（state から再開、不完全 file は取り直し）。
3. 追従確認: `check-backup.sh --json` の binlog_event_lag_seconds が 0 に戻ること。
4. lag が残る場合は snapshot-binlog を実行して index を更新:
   `ops/backup/image/tool-image.sh compose run --rm snapshot-binlog`。

### CRITICAL: gap 検出

1. 欠番 file が source 側で purge された可能性が高い（source purge gap）。
2. `restic snapshots --tag kind=binlog` で欠番前後の存在を確認。
3. 欠番区間の復元可能性が無いため RPO 達成を停止扱いとし、
   新しい full を取得して以降の PITR を継続する（`backup-full.sh` 実行）。
4. source の binlog 保持期間（binlog_expire_logs_seconds）を archive 遅延と
   比較し、purge が archive を追い越さない運用にする。

### WARN: repo check / drill 期限

- `restic check` を実行し `last-repo-check` を touch する。
- 四半期 drill（`restore-drill.sh`）を実施し `last-drill` を touch する。

## 書込み静止（RB-QUIESCE）

app 側の log と metric の監視（`.kiro/specs/backup-write-quiesce/design.md` §13）。log は日本語で固定の `errorCode=BACKUP_QUIESCE_*` を
含み、CloudWatch Logs の metric filter で数える。metric は `/actuator/prometheus`（scraper 口座。deploy-runbook §1.7 の SRE-9）。
alert の routing（通知先・当番）は HFP-03-PROD-008 で BLOCKED のため、本節は条件と対応だけを決める。自動再開・FENCED の間の応答の
欠け・停止確認の保持の読取り・停止口・解除の失敗・静止の拒否・full の中止では、その回の checkpoint・full は VALID にならない（安全側）。
checkpoint が続けて失敗すると、上の「CRITICAL: checkpoint 30 分超 / RPO 不達」にもなる。
app 側の確認の SQL（instance の行・静止要求）と手作業の SQL（保持の手での終了・停止口の行の片付け）の正本は deploy-runbook §4.7。

| 区分 | 条件 | 対応 |
|---|---|---|
| CRITICAL | `errorCode=BACKUP_QUIESCE_AUTO_RESUMED` が 1 件 | 「CRITICAL: 書込み静止の自動再開」 |
| CRITICAL | `errorCode=BACKUP_QUIESCE_FENCED` が 1 件 | 「CRITICAL: FENCED」 |
| CRITICAL | `errorCode=BACKUP_QUIESCE_STOPPED_HOLD_SEEN` が 1 件 | 「CRITICAL: 動いている app が停止確認の保持を読んだ」 |
| CRITICAL | `errorCode=BACKUP_QUIESCE_DISABLED` が 1 件、または `ses_backup_quiesce_disabled` が 1 | 「CRITICAL: 停止口」 |
| CRITICAL | `ses_backup_quiesce_gate_state` が 2（CLOSING）か 3（CLOSED）のまま最大保持時間（checkpoint 300 秒・full 3,600 秒）＋60 秒を超えた、または `ses_backup_quiesce_oldest_permit_seconds` が最大保持時間を超えた | 「CRITICAL: gate が閉じたまま・最も古い permit が長い」 |
| CRITICAL | backup の job が解除に失敗した（stderr の「重大: app の再開を確かめられません」、job は「静止解除失敗（incident 扱い）」で失敗） | 「CRITICAL: 解除の失敗」 |
| WARN | `errorCode=BACKUP_QUIESCE_REFUSED` が 30 分に 2 件以上 | 「WARN: 静止の拒否」 |
| WARN | `errorCode=BACKUP_QUIESCE_ACK_UNKNOWN` | 「WARN: 応答の結果不明」 |
| WARN | `errorCode=BACKUP_QUIESCE_FULL_ABORTED` | 「WARN: full の中止（取り直し）」 |
| WARN | 静止区間の秒数が `QUIESCE_WINDOW_WARN_SECONDS`（既定 60 秒）を超えた（backup の job の WARN「静止区間が … 秒でした」） | 「WARN: 静止区間が長い」 |
| WARN | `ses_backup_quiesce_rejected_requests_total{kind="readonly_sql"}` の増加 | 「WARN: 最後の防線での拒否」 |
| WARN | `ses_backup_quiesce_deferred_writes_pending` が解除の後 5 分を過ぎても 0 にならない、または `errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED` が 1 件 | 「WARN: 解除後の書込みが残る・捨てた」 |

### CRITICAL: 書込み静止の自動再開（`BACKUP_QUIESCE_AUTO_RESUMED`）

app が backup の解除を確かめないまま更新を再開した。log の理由: `LEASE_EXPIRED`（lease が延びなくなった）、`MAX_HOLD`（最大保持時間に
達した）、`REQUEST_GONE`（要求が解除以外の形で終わった: provider の失敗の片付け、ONLINE の full の中止、DBA の手の終了）。

1. 同じ要求の番号で `BACKUP_QUIESCE_FULL_ABORTED` が出ていれば ONLINE の full の中止（U-13）による再開で、「WARN: full の中止（取り直し）」
   に従う（この場合 AUTO_RESUMED の対応は要らない）。
2. 同じ時刻の backup の job の stderr（`[quiesce-db]` の行と `hold.log` の要約）を見る。job・host が落ちた（`LEASE_EXPIRED`）なら host と
   job を直す。`LEASE_EXPIRED` には、job は動いたまま保持 process が DDL 凍結の lock を失って終わり、lease の延長が止まった場合もある
   （stderr・`hold.log` の要約に「DDL 凍結の lock を失いました」。その静止区間は verify で失敗する）。その場合は DB への接続（DDL の
   session が切れた理由）と、同じ lock を取る Flyway job・DBA の DDL が動いていなかったかを確かめる。`MAX_HOLD` なら「WARN: 静止区間が
   長い」。acquire・verify の失敗の片付け（`REQUEST_GONE`）なら、その失敗の理由を直す。
3. `check-backup.sh --json` で、次の checkpoint が 15 分以内に VALID になることを確かめる。

### CRITICAL: FENCED（`BACKUP_QUIESCE_FENCED`）

app が heartbeat を `BACKUP_QUIESCE_FENCE_AFTER`（既定 20 秒）の間書けず、自分で更新を止めた（更新の要求は 503、参照は続く）。業務の更新が
止まるため即時に対応する。

1. app の log の前後の DB の接続の失敗と、`ses_backup_quiesce_heartbeat_failures_total`・`ses_backup_quiesce_heartbeat_age_seconds` を見る
   （制御用・heartbeat 用の pool `ses-backup-quiesce-control`・`ses-backup-quiesce-heartbeat`。アプリ account）。
2. DB への到達・`max_connections`・アプリ account の lock を確かめる（deploy-runbook §4.7）。
3. heartbeat を書けるようになれば WARN `BACKUP_QUIESCE_UNFENCED` が出て自動で戻る。戻らなければ DB 側を直してから task を起動し直す。
4. 戻った後の checkpoint が VALID になることを確かめる（FENCED の間の checkpoint は失敗し得る）。

### CRITICAL: 動いている app が停止確認の保持を読んだ（`BACKUP_QUIESCE_STOPPED_HOLD_SEEN`）

app が動いている間に `--app-stopped` の backup（APP_STOPPED の保持）が流れた。app は更新を止めた（参照は続く。ack は書かない）。その
backup は VALID にならない。

1. backup host でその job（`ops/backup/image/tool-image.sh compose ps` の `backup`・`checkpoint`）を止める。保持が解かれると（job の終わり、
   または lease の 60 秒）app は自動で更新を再開する（WARN「app の停止を確かめる backup の保持（番号 …）が終わったため、更新を再開しました」）。
2. 誰がなぜ `--app-stopped` を流したかを確かめる。`--app-stopped` は app を止めた後にだけ手で付ける（deploy-runbook §2 の4・5）。定期の
   job には付けない。

### CRITICAL: 停止口（`BACKUP_QUIESCE_DISABLED`・`ses_backup_quiesce_disabled=1`）

app が停止口（`BACKUP_QUIESCE_ENABLED=false`）で動いており、backup が全て失敗する（RPO の alert が続く）。

1. 承認済みの変更 ticket（log の `ticket=`）と期間が配備記録にあることを確かめる。無ければ Security と運用 owner へ上げる。
2. 修正の release で `BACKUP_QUIESCE_ENABLED=true` に戻し、ticket を消して配備し直す（deploy-runbook §1.0 の「停止口の使い方」）。
3. 戻した後も backup が「停止口…の instance の行があります」で失敗し続ける場合は、異常に止まった停止口の行を片付ける（deploy-runbook
   §4.7。DBA が ECS の task の停止と processlist の 0 件を確かめてから）。

### CRITICAL: gate が閉じたまま・最も古い permit が長い

app の gate が閉じた（CLOSING・CLOSED）まま最大保持時間＋60 秒を超えた、または書込みの処理（permit）が最大保持時間より長く続いている。
gate は手元の期限で開くはずのため、静止応答の状態機械が止まったか、長い処理が排水を止めている。

1. app の log の `BACKUP_QUIESCE_REQUESTED` 以後の行（`ACKED`・`REFUSED`・`ACK_UNKNOWN`）と、`ses_backup_quiesce_oldest_permit_seconds` の
   `kind` を見る。
2. `REFUSED` の log には排水を止めた処理（permit の持ち主と保持の時間）が出る。長い処理を特定する（「WARN: 静止の拒否」）。
3. gate が開かない場合は、app の log を残してから task を起動し直す（新しい instance は、有効な要求が無ければ OPEN で始まる）。

### CRITICAL: 解除の失敗

backup の job が静止を解いた後、app の再開（応答の `RESUMED`）を `QUIESCE_RESUME_CONFIRM_SECONDS`（既定 15 秒）の内に確かめられなかった
（provider の exit 3）。job は「静止解除失敗（incident 扱い）」で失敗し、その回は VALID にならない。app は lease の期限か最大保持時間で自動で
再開する。

1. app の log で、同じ要求の番号の `BACKUP_QUIESCE_RESUMED` か `BACKUP_QUIESCE_AUTO_RESUMED` が出たことを確かめる（出ていれば更新は
   再開している）。
2. どちらも出ず更新が 503 のままなら、「CRITICAL: gate が閉じたまま・最も古い permit が長い」に従う。
3. 区間の中で heartbeat が止まった instance（落ちた等）は待たない（job の WARN「区間の中で heartbeat が止まった instance は再開を確かめずに
   進めます」）。その instance の task の状態を ECS で確かめる。

### WARN: 静止の拒否（`BACKUP_QUIESCE_REFUSED` が 30 分に 2 件以上）

app が排水の上限（checkpoint 30 秒・full 600 秒。U-3）までに処理中の書込みを終えられず静止を拒否した、または要求の処理の途中で
エラーになった。その回の backup は失敗し、checkpoint が 2 回続けば checkpoint age の CRITICAL になる。

1. log の「待っていた処理」（permit の持ち主）と「書込み可能な業務の接続」を見て、長い処理（長い job・一括の更新・大きな取込）を特定する。
2. その処理の時刻（15 分ごとの checkpoint との重なり）や分割を見直す。排水の上限は変えない（U-3）。
3. 拒否の理由が `DISABLED`（停止口）なら「CRITICAL: 停止口」、`UNSUPPORTED_PROTOCOL` なら app と backup tool の protocol の版の組
   （配備した app の commit と tool image）を確かめる。

### WARN: 応答の結果不明（`BACKUP_QUIESCE_ACK_UNKNOWN`）

app が応答（ack）の書込みの結果を確かめられなかった（DB の接続が途中で切れた等）。app は閉じたまま確かめ続け、拒否を確定できれば開き、
できなければ手元の期限（hold 期限）まで閉じたままにする（安全側）。

1. DB の接続（制御用の pool）を確かめる。
2. その回の backup の成否は job の結果で判断する（QUIESCED が DB に確定していれば VALID になり得る）。

### WARN: full の中止（取り直し）（`BACKUP_QUIESCE_FULL_ABORTED`）

ONLINE の full の最中に新しい app の instance が登録され（task の置き換え・再起動）、provider が full を中止して静止を解いた（U-13）。
`backup-full.sh` は mysqldump を止めて非 0 で終わり、その日の full は欠ける。同じ要求の番号で app に `BACKUP_QUIESCE_AUTO_RESUMED`（理由
`REQUEST_GONE`）も出る。

1. 新しい instance が登録された理由（ECS の task の置き換え・再起動・配備）を確かめる。
2. app の起動が落ち着いた（readiness が UP で、heartbeat の新しい instance が 1 つ。deploy-runbook §4.7 の1）後に、full を手で取り直す
   （app は動いているので `--app-stopped` は付けない）: `ops/backup/image/tool-image.sh compose run --rm backup`。
3. **中止を知ったら 2 時間以内に取り直す**。full の CRITICAL の閾値（最新の VALID な full から 26 時間。`HEALTH_FULL_CRITICAL_SECONDS=93600`）
   は前日の 02:00 の full から数えるため、当日のおよそ 04:00 までに取り直しが VALID にならなければ `check-backup.sh` が full の CRITICAL
   を出す。間に合わなければ CRITICAL を計画外の既知の事象として記録し、取り直しの VALID で解消を確かめる（閾値は変えない）。
4. 取り直すまで PITR の基点は前日の full のままで、RPO は checkpoint で保たれる。取り直しの最中にも新しい instance が登録されれば同じく
   中止されるため、task の置き換えが続く間は待つ。

### WARN: 静止区間が長い（`QUIESCE_WINDOW_WARN_SECONDS` 超え）

静止区間（更新が 503 になる時間）が既定 60 秒を超えた。上限は checkpoint 300 秒・full 60 分（U-1）で、超えると app が自動で再開し
（`MAX_HOLD`）、その回は VALID にならない。

1. job の結果の JSON の `quiesce.window_seconds` と、index の `quiesce.window_breakdown_seconds`（区間ごとの秒数）を見る（README
   「書込み静止の記録（quiesce.json）」の投影を使い、`quiesce.json` をそのまま ticket に貼らない）。
2. uploads の snapshot が伸びている場合は、uploads の件数と容量の増え方を記録し、staging の実測（deploy-runbook §1.7 の BQ-2）と比べる。
   上限に届く前に uploads の方式の見直し（後の batch。backup-write-quiesce design.md §10.4）を始める。
3. full の dump が伸びている場合は DB の大きさ（HFP-03-PROD-007）を確かめる。

### WARN: 最後の防線での拒否（`rejected_requests{kind="readonly_sql"}` の増加）

静止中に、HTTP の分類（filter）では止まらなかった書込みが、業務の接続の読取り専用（最後の防線）で拒否されて 503 になった（書込みは DB
に入っていない）。**拒否の多くは Connector/J が server へ送る前に client 側で行い**（SQLSTATE `S1009`、vendor code 0、message
「Connection is read-only. Queries leading to data modification are not allowed.」）、**client 側の判定に掛からず server に届いた文は
ERROR 1792（SQLSTATE `25006`）になる**。どちらも `ses_backup_quiesce_rejected_requests_total{kind="readonly_sql"}` に数え、WARN
`errorCode=BACKUP_QUIESCE_READONLY_REJECTED`（60 秒に 1 回へ間引く）に経路の型と拒否の形（`connector-j-S1009` / `server-1792`）を出す。
監視・log の検索を 1792 だけにしない（backup-write-quiesce design.md §20.7 の fix E）。

1. WARN の経路（要求の型）と時刻から、静止中に書こうとした要求（想定外の書込みを持つ GET 等）を特定する。
2. 分類（WRITE にする）か query（書かないようにする）を直す（backup-write-quiesce design.md §17 L-2）。

### WARN: 解除後の書込みが残る・捨てた

静止中の logout の失効と監査（U-8）等の解除後の書込み（`ses_backup_quiesce_deferred_writes_pending`）が、解除の後 5 分を過ぎても 0 に
ならない、または上限（10,000 件）・3 回の失敗・時刻の範囲の外で捨てられた（`BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED`。内容は WARN の log に
残る）。

1. DB の接続と、app の log の書込みの失敗を確かめる。
2. DROPPED の log は `t_audit_log` に入らなかった監査の補いとして保管する（log の保管の規則に従い、ticket に内容を写さない）。process が
   解除の前に落ちた場合も同じく失われ、WARN の log だけが残る（backup-write-quiesce design.md §17 L-14）。
