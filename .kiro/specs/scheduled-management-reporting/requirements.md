# 承認済み要求（NF-10 / DG-10）

Ownerは管理者（経営管理責任者）。利用者は管理者とマネージャーであり、管理者は全社、マネージャーは許可された組織scopeを対象とする。Base policyは再開時にfetchした最新`origin/main`、今回のBase SHAは`455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。以下は承認済みrequirementsである。

## R1. Template と scope

管理者/マネージャーは report type、section、period、timezone、format、scope owner、recipient を template version として管理できること。scheduleの有効化は管理者のみが行う。versionは同一templateの変更を識別し、runは生成時点のversionを固定すること。

対象sectionは売上、粗利、売上予測、稼働率、Bench、管理会計、Cash Flow、AR aging、BP支払予定、契約終了・更新見込みとする。NF-02がPASSするまでServiceDesk/SLAは対象外とする。scheduleの有効化は管理者のみ、timezoneは`Asia/Tokyo`とする。

受入観点:

- scope owner が明示され、空の許可集合を全社扱いしない。
- recipient preview が対象 section と scope を照合し、誤配布を拒否する。
- session のない scheduler が system principal と保存済み scope を使用する。
- 管理者の全社scopeとマネージャーの許可組織scopeを区別する。

## R2. Immutable snapshot と freshness

run の生成時に、各 section の値、actual/forecast、速報/確定、cutoff/as-of、timezone、data freshness、canonical service/DTO 識別子、scope policy、source hash を固定すること。過去 run は template 変更、現在 DB 値、現在の権限変更で変化しないこと。

速報は未締めデータとして`dataAsOf`とfreshnessを表示し、確定版は月次締め完了後のみ生成する。snapshot/documentは7年間保持する。明示的な再生成は旧runを上書きせず新versionを作り、generation retryは同一runの同一snapshotを再利用して重複snapshotを作らない。

受入観点:

- report snapshot、画面値、既存 export 値の同一指標が contract test で一致する。
- section 部分失敗と生成 retry を区別して監査できる。
- 再生成は新しい run/version として扱い、旧 run の本文を上書きしない。
- sectionが1つでも失敗したrunは`PARTIAL`/`FAILED`として配布停止し、section failureとgeneration retryを監査可能にする。

## R3. Document

PDF/XLSX/CSV は同じimmutable snapshotから生成し、`DocumentService` の generated document、content hash、version、CLEAN、scope/access audit を通ること。配布はnotification outbox経由のアプリ内通知＋期限付きlinkとし、メール添付は使用しない。期限切れlink、権限喪失、組織異動ではdownloadを拒否し、download時は再認証を要求すること。

受入観点:

- export と document が report snapshot の値を再集計しない。
- document restore 後に hash/version/access policy が検証できる。
- generation時とdownload時の両方でrecipient scopeを検証する。

## R4. Delivery と運用状態

delivery は recipient preview、scope decision、dedupe/idempotency、outbox status、retry、DLQ/FAILED、manual replay、link expiry を監査可能にすること。scheduler の二重起動を一つの run に収束させること。

受入観点:

- scheduler 二重起動、section 部分失敗、generation retry、delivery DLQ/manual replay をテストする。
- recipient scope 外への download/open を拒否する。
- 外部 I/O は DB transaction と分離し、retry で重複 document/delivery を作らない。

## R5. 受入・復旧

月末境界、tenant timezone、desktop/390px preview、document restore、配布障害訓練、base/head を証拠化すること。backup/restore は別 recovery target で実施し、snapshot/document/outbox の整合性を検証すること。

## 承認済み実装条件

R1〜R5は承認済みplan/spec/tasksとしてF1〜Mを実装する。既存の正本service/DTOを使用し、report独自SQL・集計式・丸めを作らない。schedulerは明示system principalを使用し、HTTP sessionに依存しない。
