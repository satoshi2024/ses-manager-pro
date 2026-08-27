# 要求ドラフト（DG-10 決定前）

この文書は NF-10 の受入後要求を開発可能な粒度へ写像したドラフトであり、承認済み requirements ではない。DG-10、approved report/recipient、Owner が確定するまで実装開始条件を満たさない。

## R1. Template と scope

管理者/マネージャーは report type、section、period、timezone、format、scope owner、schedule、recipient を template version として管理できること。version は同一 template の変更を識別し、run は生成時点の version を固定すること。

受入観点:

- scope owner が明示され、空の許可集合を全社扱いしない。
- recipient preview が対象 section と scope を照合し、誤配布を拒否する。
- session のない scheduler が system principal と保存済み scope を使用する。

## R2. Immutable snapshot と freshness

run の生成時に、各 section の値、actual/forecast、速報/確定、cutoff/as-of、timezone、data freshness、canonical service/DTO 識別子、scope policy、source hash を固定すること。過去 run は template 変更、現在 DB 値、現在の権限変更で変化しないこと。

受入観点:

- report snapshot、画面値、既存 export 値の同一指標が contract test で一致する。
- section 部分失敗と生成 retry を区別して監査できる。
- 再生成は新しい run/version として扱い、旧 run の本文を上書きしない。

## R3. Document

PDF/XLSX/CSV は同じ snapshot から生成し、`DocumentService` の generated document、content hash、version、CLEAN、scope/access audit を通ること。配布はデフォルトで添付ではなく期限付き portal/document link とし、期限切れ link は再認可すること。

受入観点:

- export と document が report snapshot の値を再集計しない。
- document restore 後に hash/version/access policy が検証できる。

## R4. Delivery と運用状態

delivery は recipient preview、scope decision、dedupe/idempotency、outbox status、retry、DLQ/FAILED、manual replay、link expiry を監査可能にすること。scheduler の二重起動を一つの run に収束させること。

受入観点:

- scheduler 二重起動、section 部分失敗、generation retry、delivery DLQ/manual replay をテストする。
- recipient scope 外への download/open を拒否する。
- 外部 I/O は DB transaction と分離し、retry で重複 document/delivery を作らない。

## R5. 受入・復旧

月末境界、tenant timezone、desktop/390px preview、document restore、配布障害訓練、base/head を証拠化すること。backup/restore は別 recovery target で実施し、snapshot/document/outbox の整合性を検証すること。

## 実装停止条件

R1〜R5 は DG-10 の確定後に approved plan/spec/tasks へ昇格する。現時点では要件の存在と検討対象だけを記録し、DDL・コード・画面・テスト実装は行わない。
