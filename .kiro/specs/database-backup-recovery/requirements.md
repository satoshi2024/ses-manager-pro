# 正式データバックアップ・PITR 要件

## 1. 目的と完了の定義

MySQL 8 の業務データと `app.upload.base-path` 配下のファイルを、暗号化された異地保管へ継続退避し、障害時に指定時刻以前の**検証済み整合点**へ安全に復元できるようにする。

本仕様でいう「実装完了」は、スクリプトが存在することではない。次をすべて満たした状態だけを指す。

- 目標 RPO 15 分、目標 RTO 4 時間を、代表データ量の隔離演習で実測している。
- DB と uploads を同じ整合点へ戻し、復元前後 marker、Flyway、主要件数、DB→ファイル参照、SHA-256、アプリ smoke を検証している。
- 欠落 binlog、破損 snapshot、誤った時刻、非空 DB、同一 source/target、承認不足では fail closed になる。
- 既存本番 DB を原地上書きせず、旧環境を cutover 確定まで保持できる。
- `review-ledger.md` に要求 ID、task ID、test、Demo、証跡 SHA-256 が追跡可能に記録されている。

## 2. 用語

| 用語 | 定義 |
|---|---|
| 要求時刻 | 運用者が RFC 3339 UTC（末尾 `Z`）で指定する復旧希望時刻。 |
| 整合 checkpoint | 書込みを静止した境界で採取した source UUID、binlog file/position、GTID、uploads snapshot、主要件数をひと組にした復旧点。 |
| 実効復旧点 | 要求時刻以下で最も新しい検証済み checkpoint。DB と uploads はこの点へそろえる。 |
| base full | 実効復旧点以下で最も新しく、同一 source lineage に属する検証済み全備。 |
| restore plan | base full、開始/終了 coordinate、必要 binlog、uploads snapshot、復元先 fingerprint、承認、検証手順を固定した不変 JSON。 |
| production source | 復旧元となる既存本番環境。復元処理からは読取りもしないオフラインの証跡対象。 |
| recovery target | production source とは別の、復元専用 marker を持つ空の MySQL 8 インスタンス/DB と uploads staging。 |

## 3. スコープ

対象:

- MySQL 8 の対象 DB 全体、routine、event、trigger、Flyway 履歴。
- `app.upload.base-path` 配下の quarantine、published、documents、contracts、および将来追加される全子孫。
- restic repository、継続 binlog、full/checkpoint、監視、保存期間、復元、隔離演習、runbook。
- 復元時に必要なアプリ build/Flyway version と storage topology の証跡。

対象外:

- 管理画面からの一発復元。
- 稼働中の既存本番 DB への in-place import。
- MySQL 5.7/MariaDB client による MySQL 8 PITR。
- 外部 SaaS 自体が保持するデータのバックアップ。
- 本仕様の証跡を根拠にしない、未検証の「ベストエフォート復元」。

## 4. 要件と受入基準

### HFP-03-RQ-001：本番 topology と前提条件を確定する

- DB version/minor、source UUID、GTID mode、binlog format/checksum/transaction compression、TLS、binlog 保持期間、全 table engine、uploads 実体、app replica 数、停止/書込み静止方法を `baseline.md` に記録する。
- production tool image は稼働 DB と互換の Oracle MySQL 8 client を exact version と image digest で固定する。Alpine の汎用 `mysql-client` を互換確認なしで使用しない。
- binlog は `ON`、checksum 検証可能、対象 table はすべて InnoDB とする。DDL/deploy と full/checkpoint の同時実行を禁止する。
- 未確定の production topology を推測して実装完了扱いにしない。
- production dataを複製せず、DB総bytes/rowsと上位table分布、uploads file数/bytes/size分布、15分/日次binlog量、CPU/RAM/storage/networkを匿名化した代表profileとしてID/SHA付きで固定する。synthetic drill fixtureは各容量をbaseline未満にせず上限+10%以内、上位分布を±10%とし、restore環境をproductionより高性能にしない。

受入基準:

- **HFP-03-AC-001-01** `preflight.sh` が MySQL 8 client/server、source UUID、binlog、table engine、TLS、空き容量を機械判定し JSON を出力する。
- **HFP-03-AC-001-02** MariaDB client、MySQL major/minor 非互換、`log_bin=OFF`、checksum 不検証、非 InnoDB table のいずれかで非 0 終了する。
- **HFP-03-AC-001-03** production 固有値または代表profile ID/SHA・容量許容差・restore resource上限が未確定なら `BLOCKED` と記録され、後続 production gate は PASS にならない。

### HFP-03-RQ-002：DB と uploads の同一整合点を取得する

- 毎日 02:00 Asia/Tokyo の full と、15 分間隔の checkpoint を取得する。
- full/checkpoint は、全 app replica と scheduler の書込みを静止し、DDL/change freeze を確認してから coordinate と uploads snapshot を採取する。
- 書込み静止は任意の `bash -c` 文字列ではなく、version 管理された provider と bounded timeout を用いる。静止確認に失敗した場合は snapshot を発行しない。
- full は `mysqldump --single-transaction --quick --routines --events --triggers --hex-blob --source-data=2` を基本とし、dump に記録された file/position を機械抽出する。
- uploads がローカル volume の場合、snapshot 中の symlink、hardlink、device、FIFO、socket、path traversal を拒否する。原子的 volume snapshot がない場合は書込み静止を snapshot 完了まで維持する。

受入基準:

- **HFP-03-AC-002-01** full/checkpoint metadata に静止開始/解除、UTC 整合時刻、source UUID、file/position、GTID、uploads snapshot ID がそろう。
- **HFP-03-AC-002-02** 静止中に試みた更新が成功せず、checkpoint 前 marker は DB/files 双方に含まれ、解除後 marker は含まれない。
- **HFP-03-AC-002-03** DDL、別 app replica、scheduler、uploads snapshot provider の失敗時に不完全 snapshot を `valid=true` として登録しない。

### HFP-03-RQ-003：自己完結した manifest と完全性検証を提供する

- `metadata.json` と全 payload を先に確定し、`manifest.json` が全 payload の相対 path、type、size、SHA-256 を列挙し、最後に `manifest.sha256` で manifest 自体を固定する。
- metadata は schema version、app commit/build、Flyway version、tool version、DB 名の非可逆 fingerprint、source UUID、coordinate/GTID、table count、uploads inventory を含む。秘密、接続 URL、個人データを含めない。
- restore は restic `--verify`、`manifest.sha256`、全 payload SHA、binlog checksum、archive entry 安全性の順で検証し、ひとつでも不一致なら import 前に停止する。
- 定期的に `restic check`、周期的に `restic check --read-data` を行う。

受入基準:

- **HFP-03-AC-003-01** metadata が manifest 作成後に変更された fixture で検証が失敗する。
- **HFP-03-AC-003-02** DB dump、uploads、binlog、manifest の各 1 byte 破損で非 0 終了し、target DB/uploads は未変更である。
- **HFP-03-AC-003-03** manifest に列挙されない payload、絶対 path、`..`、特殊 file、外向き symlink を拒否する。

### HFP-03-RQ-004：連続した binlog と checkpoint を RPO 内で保管する

- `mysqlbinlog --read-from-remote-server --raw --stop-never` は、明示した開始 log、source ごとに一意な `--connection-server-id`、TLS `VERIFY_IDENTITY`（最低 `VERIFY_CA`）、checksum 検証を使用する。
- active raw binlog を restic snapshot に含めない。checkpoint で log rotation を行い、閉じた file の size/end position/checksum を確認してから immutable snapshot にする。
- full coordinate から checkpoint end coordinate まで、同一 source UUID の file sequence と event position が連続することを検証する。欠番、重複、truncation、別 lineage を拒否する。
- source 側 purge より先に archive が永続化されたことを監視する。archive 復旧不能 gap を検出した時点で重大 alert とし、RPO 達成を停止扱いにする。

受入基準:

- **HFP-03-AC-004-01** 15 分 cadence の正常系で最新の検証済み checkpoint/event upload lag が 15 分以内となる。
- **HFP-03-AC-004-02** 欠番、truncated active file、checksum error、source UUID 差替え、重複 connection-server-id で checkpoint/restore plan を作成しない。
- **HFP-03-AC-004-03** archiver 再起動時に最後の確定 coordinate から再開し、既存 file を黙って上書きしない。

### HFP-03-RQ-005：要求時刻から再現可能な restore plan を作成する

- 要求時刻は RFC 3339 UTC のみ受け付ける。実効復旧点は `checkpoint.consistency_time <= requested_target` のうち最新、base full は `full.consistency_time <= effective_checkpoint` のうち最新かつ同一 lineage を選ぶ。
- restic snapshot の作成時刻や「単に最新の full」を選択根拠にしない。
- binlog replay の開始は dump metadata の file/position、終了は checkpoint の file/position とする。時刻だけを replay 境界にしない。
- `mysqlbinlog --start/stop-datetime` を使うのは event position の調査に限定し、必ず `TZ=UTC` を強制・記録する。apply は position を使う。
- plan は必要 snapshot/binlog の ID、SHA、順序、start/stop position、target fingerprint、期限を含み、生成後の変更を SHA-256 で検出する。

受入基準:

- **HFP-03-AC-005-01** target 前後に複数 full/checkpoint がある fixture で、target より後の snapshot を選ばない。
- **HFP-03-AC-005-02** host timezone を UTC/JST/DST 地域へ変えても同じ plan SHA と start/stop coordinate になる。
- **HFP-03-AC-005-03** 要求時刻と実効復旧点の差が 15 分を超える、または必要 binlog が連続しない場合は plan を `RPO_MISSED` とし apply 不可にする。

### HFP-03-RQ-006：誤接続・誤破壊を技術的に防止する

- restore は production source と異なる `@@server_uuid` を持ち、事前 provision された recovery-target marker と allowlist に一致する MySQL だけを対象にする。
- target DB は存在しないか完全に空でなければならない。既存 production DB への drop/truncate/import、`localhost` 等の暗黙 default、未解決環境変数を禁止する。
- source credential は restore runtime に mount しない。target credential は対象 recovery target 以外に権限を持たない。
- production plan の apply/cutover には、同一 plan SHA・target UUID・change ticket・有効期限に結び付く、異なる 2 名の検証済み承認を必要とする。同一 actor、期限切れ、署名/claim 不正を拒否する。
- `--apply` 単独、固定文字列 `CONFIRM_RESTORE=YES` 単独では書込みを許可しない。

受入基準:

- **HFP-03-AC-006-01** source UUID と同じ、allowlist 外、marker 不一致、非空 DB、既存 schema、承認 0/1 名、同一 actor 2 件の各ケースで import 前に非 0 終了する。
- **HFP-03-AC-006-02** 誤接続 fixture の production marker/count/SHA が実行前後で不変である。
- **HFP-03-AC-006-03** plan の target host/UUID/DB または payload を 1 byte 変更すると承認と plan 検証が失敗する。

### HFP-03-RQ-007：staging 復元、検証、cutover、rollback を分離する

- DB は recovery target の新規 schema へ、uploads は本番 path と同一 filesystem の versioned staging directory へ復元する。
- 複数 binlog は file order を固定し、**単一の `mysqlbinlog` 出力を単一の `mysql --binary-mode` connection** へ渡す。途中 file ごとの別 connection を禁止する。
- restore 後、Flyway history、全 table check、checkpoint 主要件数、DB→ファイル参照、保存済み SHA、復元前/後 marker、read-only アプリ smoke を検証する。
- uploads は staging で検証後、停止中に versioned `current` symlink/rename で切り替える。DB 接続先切替と uploads 切替を同一 change 手順に固定する。
- 旧 DB/uploads は cutover 確定まで保持する。read-only smoke 失敗時は旧 DB/uploads へ戻す。新環境で書込みを再開した後は単純 rollback を禁止し、別の復旧判断とする。
- restore script はアプリを自動再開しない。

受入基準:

- **HFP-03-AC-007-01** target 前 marker が存在し、target 後 marker が DB/uploads 双方に存在しない。
- **HFP-03-AC-007-02** 途中 binlog apply、uploads hash、Flyway、app smoke の各失敗で production source は不変、staging は非公開、状態は `FAILED_VALIDATION` となる。
- **HFP-03-AC-007-03** read-only cutover smoke の意図的失敗で旧 DB/uploads へ戻り、書込み受付前であることを証明する。

### HFP-03-RQ-008：秘密・暗号・権限を安全に扱う

- restic 暗号鍵、S3 credential、DB credential、approval verifier key は Docker secret、root-only file、workload identity 等から取得し、CLI 引数、log、manifest、環境 dump に出さない。
- `MYSQL_PWD` を export しない。mode 0600 の一時 option file または client credential mechanism を使用し、trap で削除する。
- backup/archive/restore/retention の DB・repository 権限を分離し、通常 backup role に delete/prune 権限を与えない。
- repository key のオフライン escrow、二名管理、rotation、旧 key での復元確認を runbook 化する。鍵紛失を成功扱いにしない。
- DB remote 接続と S3 は証明書検証付き TLS を必須とする。

受入基準:

- **HFP-03-AC-008-01** process argv、`/proc/<pid>/environ`、標準出力/標準エラー、evidence grep に secret が出ない。
- **HFP-03-AC-008-02** backup role で snapshot 削除、restore role で source 書込み、retention role 不在で prune ができない。
- **HFP-03-AC-008-03** 鍵 rotation 後に新旧の保持対象 snapshot を隔離復元でき、旧 key 撤去の承認証跡が残る。

### HFP-03-RQ-009：依存関係を壊さない保存・清掃を行う

- PITR window は 30 日とし、その期間の checkpoint と、最古の保持 checkpoint を復元できる base full から最新 checkpoint までの全 binlog/uploads snapshot を保持する。
- weekly 8、monthly 12 の full-only archive を別 tier として保持してよいが、PITR 可能と表示しない。
- `forget/prune` は full/binlog/checkpoint の依存 graph を評価してから retention 専用 role で行う。
- backup、binlog snapshot、repository check、forget/prune は共有 lock で排他し、prune を full/checkpoint の成功 path に直結させない。
- S3 versioning/immutability または同等の削除耐性を有効化し、通常 backup credential の侵害だけで全世代を消せないようにする。

受入基準:

- **HFP-03-AC-009-01** 最古/中間/最新の保持 checkpoint ごとに依存 full/binlog/uploads が 1 組以上残る。
- **HFP-03-AC-009-02** prune と snapshot の競合 test で同時実行されず、lock timeout は非 0 と明確な alert になる。
- **HFP-03-AC-009-03** dry-run dependency report と削除後 report が一致し、復元不能 snapshot を `PITR_AVAILABLE` と表示しない。

### HFP-03-RQ-010：RPO を event/checkpoint 基準で監視する

- full freshness、最新の検証済み checkpoint、最新の閉じた binlog の**repository 到達時刻/終了 coordinate**、archiver heartbeat、source purge gap、repository integrity、空き容量、直近 drill を監視する。
- 「directory 内に古い file がひとつある」ことを binlog lag と誤判定せず、最新成功 watermark と source current coordinate の差を使う。
- warning/critical threshold、非 0 exit、機械可読 JSON/metric、運用 alert routing を提供する。

受入基準:

- **HFP-03-AC-010-01** full 26 時間超、checkpoint/event 20 分 warning・30 分 critical、gap、repository check 失敗、drill 期限超過を個別 code で検出する。
- **HFP-03-AC-010-02** 古い archive file が残っていても最新 watermark が正常なら false alert を出さない。
- **HFP-03-AC-010-03** archiver が止まったまま source log が進む fixture で critical となる。

### HFP-03-RQ-011：自動 test と隔離演習で偽 green を防ぐ

- shell static test、command mock unit test、MySQL 8/MinIO または隔離 restic repository の Docker integration test、実 restore drill を用意する。
- integration test は Docker がなければ PASS/skip にせず、CI では failure、ローカルでは `BLOCKED(Docker unavailable)` とする。
- drill は source/target を別 container/network/credential にし、host port を本番と共有せず、本番 repository/secret を mount しない。
- 四半期ごと、および tool/image/restore logic 変更時に、production 相当データ量で restore drill を行う。

受入基準:

- **HFP-03-AC-011-01** target 前/後 DB marker と uploads marker を用いた実 PITR が自動 test と手動 Demo の双方で成功する。
- **HFP-03-AC-011-02** 破損、gap、誤 target、非空 DB、別 timezone、同一 source/target、承認不足、concurrent prune の negative test が存在する。
- **HFP-03-AC-011-03** CI artifact に test 件数、failure、skip、plan SHA、RPO/RTO、image digest があり、secret grep が 0 件である。

### HFP-03-RQ-012：RPO/RTO、runbook、証跡を運用可能にする

- RPO は incident/requested target と実効復旧点の差で計算し 15 分以内、RTO は incident declaration から read-write 再開承認までを計測し 4 時間以内とする。
- RTO を plan/承認、download/verify、DB replay、uploads、validation、cutover の区間に分ける。
- backup、gap、鍵紛失、restore failure、validation failure、cutover rollback、書込み再開後障害の runbook を提供する。
- 各 task は Objective、依存、変更 file、automated test、隔離 Demo、証跡、失敗/rollback 判定がそろった時だけ完了にする。

受入基準:

- **HFP-03-AC-012-01** HFP-03-PROD-007のprofile ID/SHAと許容差を満たすsynthetic data・同等以下のrestore resourceで演習し、RPO ≤ 15分、RTO ≤ 4時間と各区間時間を実測する。profile/tolerance照合が無ければ演習をPASSにしない。
- **HFP-03-AC-012-02** 目標未達、未実行、Docker/credential/topology 不足を PASS にせず `FAIL` または `BLOCKED` とする。
- **HFP-03-AC-012-03** `review-ledger.md` から全 RQ/AC/task/test/Demo/evidence SHA を双方向 trace できる。

## 5. 非機能 gate

| Gate ID | 合格条件 |
|---|---|
| HFP-03-GATE-01 | 既存 production source へ destructive SQL/file 操作が 0。 |
| HFP-03-GATE-02 | 要求時刻以下の正しい full/checkpoint と file/position を再現可能に選択。 |
| HFP-03-GATE-03 | binlog gap/checksum/truncation 0、複数 log は単一 connection replay。 |
| HFP-03-GATE-04 | DB→uploads missing reference 0、検証対象 hash mismatch 0。 |
| HFP-03-GATE-05 | negative safety test がすべて期待どおり非 0。 |
| HFP-03-GATE-06 | RPO ≤ 15 分、RTO ≤ 4 時間を実測。 |
| HFP-03-GATE-07 | secret evidence grep 0、repository 削除権限分離済み。 |
| HFP-03-GATE-08 | Docker integration、隔離 drill、CI の skipped test 0。 |
