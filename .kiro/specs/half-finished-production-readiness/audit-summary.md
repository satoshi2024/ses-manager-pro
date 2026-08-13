# 半完成機能 再監査サマリー

## 1. 判定基準

計画基点 `99fbed8294dd1a6c320b4413b832f7c7b9292da1` の source、migration、UI、test、既存 spec と、2026-08-12 時点の公式一次資料を突合した。旧 `tasks.md` の未チェックだけでは半完成と判定せず、次のすべてを満たすものだけを対象にした。

1. 利用者または運用者が到達できる画面、API、script 等の入口が既にある。
2. 既存 requirements が約束する主要な production path が source 上で完結しない。
3. mock、静的表示、手動説明では代替できない再現可能な欠落がある。
4. S01〜S17 の対象外機能ではない。

この基準で高確度に該当したのは HFP-01〜03 の三件だけである。新しい候補を追加する場合は、再現手順、該当 requirement、source evidence、利用者影響を本書へ追記し、発注者承認を得る。未チェック checkbox や「改善できそう」という理由だけで追加しない。

## 2. HFP-01 freee 人事労務 給与・賞与参照

### 既にあるもの

- `FreeeOAuthController`: OAuth state の生成・session 検証。
- `FreeeIntegrationServiceImpl`: token 交換、AES-GCM 保存、row lock 付き refresh、従業員対応付け、給与取得の骨格。
- `FreeePayrollApiController`、`PayrollPageController`、`templates/payroll/index.html`: 接続状態、従業員、対応付け、給与一覧の画面/API。
- V21 の接続・従業員 link table と、限定的な mock/component test。

したがって「Controller の interface しかない」状態ではない。

### production path が閉じない証拠

- `FreeeIntegrationServiceImpl#handleCallback` は token を保存するが company ID/name を確定保存しない。`#getBankDeposits` は company ID が無い接続を拒否するため、接続表示と利用可能状態が一致しない。
- OAuth host、従業員 path、給与 path、response root/field が公式 freee 人事労務契約と異なる。HTTP 200 の mock が成功しても実 API 契約を証明しない。
- `#getPayrollStatements` は `PayrollStatementDto.items` と `engineerId` を埋めず、現在 company の確定 link と内部 BP 除外を通さず provider 全量を返す。
- 画面は給与/賞与 type を選択できず、項目明細も表示しない。
- 機微な給与 GET は既存の更新 API 監査だけでは記録されず、実 test company の E2E も未実施。

完成 scope は **freee を正本とする読み取り専用の都度参照**である。SES Manager 内の給与計算、金額書戻し、給与 payload 永続化、給与 webhook/scheduler は対象外とする。公式契約と詳細は `../payroll-management/research.md`、実装順は同 `tasks.md` を正とする。

## 3. HFP-02 CloudSign 電子契約

### 既にあるもの

- V20 の契約 template/document table。
- `ContractDocumentServiceImpl#create` の HTML sanitization、日本語 PDF、source hash、local file 保存。
- 契約文書の画面/API、scope、外部 artifact scan/台帳 hook。
- `CloudSignClient` と送信/手動同期の骨格。

ローカル PDF 作成機能は維持し、provider 閉ループの不足だけを完成させる。

### production path が閉じない証拠

- `CloudSignClientImpl#send` は title/name/email の JSON を一回 POST するだけで、作成済み source PDF を upload しない。公式の document作成→file upload→participant追加→send の工程と media type に一致しない。
- 静的 bearer token を使い、公式の `client_id` による短寿命 token 取得を実装していない。
- 外部成功後に local ID/state を保存するため、timeout、process crash、DB失敗、二重clickで provider orphan/重複が起こり得る。公式は mutation の 504 後も処理継続し得るため、盲目的 retry はできない。
- `ContractDocumentServiceImpl#sync` は provider/file I/O を長い transaction に含め、download error を握り潰す。certificate は実質取得されず、source hash を signed hash で上書きする path がある。
- 定期 polling がなく、entity/path/internal error を返し得る API と、外部確認前に送信成功と見せる UI が残る。

公式契約、未確認 sandbox decision、全 baseline finding は `../contract-document-esign/research.md` と `review-ledger.md`、実装順は同 `tasks.md` を正とする。

## 4. HFP-03 backup / PITR

### 既にあるもの

- `ops/backup/backup-full.sh`: DB dump、uploads archive、restic snapshot の骨格。
- `archive-binlog.sh` / `snapshot-binlog.sh`: binlog 保管の入口。
- `restore.sh`、`restore-drill.sh`、`check-backup.sh`: 復元、演習、監視を意図した CLI。

backup script が全く無い状態ではないが、復旧可能性は証明されていない。

### production path が閉じない証拠

- `restore.sh --target` は target を表示するだけで selection/replay に使用せず、単純に最新 full を既存 DB へ importする。binlog、uploads、manifest/SHA、Flyway/app validation を復元しない。
- `restore-drill.sh` は `mysqladmin ping` を確認するだけで restore を実行しない。
- full は DB と uploads の同一整合点を持たず、manifest 作成後に metadata を追加するため全 payload を保護しない。
- active raw binlog の snapshot、continuity/source UUID/checksum 未検証、単純 latest snapshot 選択では指定時刻の PITR を保証できない。
- target の source同一性、空DB、allow-list、二者承認、read-only cutover guard が無く、誤接続時の破壊を技術的に止められない。
- `check-backup.sh` は最新到達点ではなく古い file の存在を見て誤警報し得る。

全18 baseline finding は `../database-backup-recovery/baseline.md`、MySQL 8 の公式契約と判断は同 `research.md`、安全な実装順は同 `tasks.md` を正とする。本番 DB への destructive Demo は禁止し、隔離 source/target だけで検証する。

## 5. 半完成機能として数えないもの

| 候補 | 判定 | 理由 |
|---|---|---|
| S01〜S17 | 対象外 | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/COPY-INDEX.md` と発注者指示で明示除外。既存実装の再Reviewも行わない。 |
| AI / skillsheet ingestion | 半完成認定しない | 開発既定は mock だが、`GeminiTextServiceImpl`、`GeminiMatchingServiceImpl` と provider 切替実装が存在する。production で mock のままなら deployment configuration risk として扱い、旧 checkbox を根拠に再実装しない。 |
| proposal、work record、rule matching、sales activity 等の旧未チェック task | 半完成認定しない | 現行 source、migration、UI、test の実装を確認でき、checkbox が現状を表していない。新しい失敗再現なしに本プログラムへ追加しない。 |
| 管理者password変更、reverse proxy 実機確認 | production acceptance debt | repository 内だけでは完了できない運用作業であり、ユーザー機能の未実装ではない。deployment release gate として別管理する。 |
| freee 会計入金、freee勤怠、他SaaS連携 | 今回の隣接範囲外 | HFP-01〜03 の明示 acceptance を満たすために必要な shared regression だけ確認し、新機能へ拡張しない。 |

## 6. 監査結果の使い方

- 実装担当は本書を「追加実装一覧」ではなく scope boundary として読む。
- 各 task の完了は source変更ではなく、acceptance、test、Demo、証拠、rollback の組で判定する。
- 新しい事実が判定を覆す場合、過去記録を削除せず `execution-ledger.md` に decision を追記する。
- 同じ root cause を別名称で再起票せず、既存 HFP task/acceptance へ結び付ける。
