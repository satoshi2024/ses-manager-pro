# Implementation Plan — 会計・支払連携

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T094〜T100はL0〜L3の定向test・直接回帰、T101でL4全量を実行する。
> provider error matrixは対象adapter単位、全量/sandbox障害訓練はM/release gateへ集約する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）を実装前に読む。
> 時間/scope/状態/error分類の判断は `design.md` §6「決定表」を正とする。
>
> **Migration**: 本specの正式migrationは **V106**。Wave 2とportal系の先行依存完了後に着手する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [x] 0. G4/API spike/canonical mapping
  - **Objective**: freeeの契約plan・利用可能API・rate limitが確認され、
    勘定科目/税区分/部門/取引先のmappingが表として確定する。
    以降のadapter実装が「どのAPIをどのmappingで呼ぶか」を推測せずに済む状態にする。
  - **成果物**: plan/API可否、sandboxまたはofficial fixture response、勘定/税/部門/取引先mapping、rate limit、fallback。
  - **Demo**: sandboxがあれば最小売上/仕入1件、未契約ならWireMock/official fixtureのspikeと本番blocker記録（本番コード変更なし）。
  - **実装ガイダンス**: production codeを変更しない。
    **未確認のmapping項目は「未確認」と明記する**（前提節）。取得済みと偽らない。
    本システムはSES業務明細の正、freeeは会計帳簿/支払確定の正。**総勘定元帳を自作しない**。
  - **テスト要件**: L0。mapping表の全object typeに確認状態が付いていること、
    未確認項目が本番送信blockerとして列挙されていること、`git diff --check` exit 0。

- [x] F1. connection/mapping/job DDLと既存connection移行
  - **Objective**: tenant/法人/provider/product別のconnectionを管理でき、tokenが暗号化・rotationできる。
    既存の単一`t_freee_connection`前提が解消される。jobがpending→running→終端で進み、
    複数workerが同じjobを二重に処理しない。
  - **実装ガイダンス**: **V106**/V1/H2(`sql/schema-accounting-integration-h2.sql`)/MySQL smoke、
    暗号/token race/outbox。既存`t_freee_connection`を段階移行（design §1）。
    **job claimはDB lock/CAS**（`WHERE status='pending'`、design §6.3）。
    `verified_at IS NULL`のmappingは**未検証**で送信を止める（design §6.1）。
  - **テスト要件**: L1〜L3。connection unique、token rotation、
    **複数workerの同時claimで1つだけ成功**、`version` CAS、tenant/法人分離、
    **token race（同時401で refresh が1回だけ）**、既存connection移行後の回帰。
  - **Demo**: 2法人分のconnectionを登録し独立に動くことを確認。
    workerを2並列起動して同じjobが二重処理されないことを確認。

- [x] F2. AccountingProvider/freee/CSV
  - **Objective**: canonical DTOで売上/仕入/支払を表現でき、freee APIとCSV出力の両方へ同じDTOから出せる。
    provider応答が分類され、validation errorが無限retryされない。秘密情報がログに出ない。
  - **実装ガイダンス**: canonical DTO、HTTP adapter、error分類、request ID。
    **error分類はdesign §6.3の表に従う**（400/422=failed、401=refresh 1回、403 plan=failed、429/5xx/timeout=retryable）。
    `FreeeAccountingProvider`はofficial API schema DTOを分離し、**raw Mapを業務serviceへ漏らさない**（design §2）。
    provider request ID（`X-Freee-Request-ID`）を保存。
  - **テスト要件**: L2〜L3。WireMockで200/400/401/403 plan/429/500/timeoutの全分類、
    **validation errorがretryされないこと**、401 refreshが1回だけ、
    **秘密のlog capture test（token/secretが出ないこと）**、CSV fallbackが同一DTOから出ること。
  - **Demo**: WireMockで各status codeを返し、jobの終端状態が分類どおりになることを確認。

- [x] A1. mapping/preview/job管理UI
  - **Objective**: 財務担当がconnection health・mapping不足・送信previewを確認し、
    失敗jobをretry/cancelできる。mapping不足のまま送信ボタンが押せない。tokenは画面に出ない。
  - **実装ガイダンス**: connection health、mapping不足、preview、retry/cancel。
    **tokenと秘密情報はどのレスポンスにも含めない**（design §6.2）。接続状態（有効/期限切れ）のみ。
    4言語i18n。
  - **テスト要件**: L2〜L3。財務permission、CSRF、二重click、
    **mapping不足時の送信拒否**、`encrypted_tokens`がAPIレスポンスに含まれないこと。
  - **Demo**: validation error修正→retry成功。
    mapping未設定で送信が拒否されることを確認。

- [x] B1. 売上/取消連携
  - **Objective**: 送付済/承認済の請求がfreeeへ冪等送信され、同じ請求を10回再実行しても外部に1件しかできない。
    金額不一致時は連携成功とみなさない。請求取消で赤伝票/取消が連携される。
  - **実装ガイダンス**: 請求送付/承認hook、取消hook、Outbox enqueue、金額照合。
    **DB transactionと外部API呼び出しを絶対に同居させない**（platform-invariants §3.3）。
    締めチェック（既存`MonthlyClosingService`）を通過した請求のみ。
  - **テスト要件**: L2〜L3。10回同時実行冪等性、金額不一致時SUCCEEDED拒否、
    取消連携、締め済み月への更新拒否。
  - **Demo**: 請求発行→job成功→freee deal ID記録。同請求を再実行しても1件のみ。
    請求取消→赤伝/取消記録。

- [x] B2. BP/経費/支払連携
  - **Objective**: 承認済のBP支払と経費が仕入/経費として冪等連携され、
    支払実績が外部IDと金額/日付の照合後にだけ内部`paid`へ反映される。
    口座変更が承認されていない支払先へ振込データが出ない。
  - **実装ガイダンス**: purchase/expense、payment sync、振込guard（採用時）。
    **内部`paid`更新は外部ID＋金額＋日付の照合後だけ**（R3.2）。
    振込データ出力は承認済支払のみ、口座変更承認と二重支払guardを持つ（R3.4）。
    手数料/源泉/税区分はBP/個人区分とmapping ruleで計算し、人がpreview確認する。
  - **テスト要件**: L2〜L3。**未承認の口座変更が振込先に反映されないこと**、
    二重支払guard、税/手数料/源泉の計算、金額不一致時に`paid`へ進まないこと。
  - **Demo**: BP支払→外部→支払済sync。口座変更申請中のBPへ振込データが出ないことを確認。

- [x] B3. 月次照合/closing
  - **Objective**: 内部の売上/仕入/入金/支払と外部取引が月次で照合され、
    未送信・不一致・外部のみが一覧で見える。
    重大不一致があると月次締めが警告または阻止される。
  - **実装ガイダンス**: reconciliation、差異matrix、closing integration。
    **外部のみの取引を自動で内部作成しない**（R3.3）。
    不一致は理由をつけてignoreまたは内部データとlinkする。
  - **テスト要件**: L2〜L3。内部のみ/外部のみ/金額差の各分類、ignore理由の記録、
    外部のみが自動で内部登録されないこと、closing block設定時の締め拒否。
  - **Demo**: 不一致解消後に締め可能。外部のみの取引が自動作成されないことを確認。

- [x] M. 回帰/障害訓練
  - **Objective**: 会計・支払連携の全体回帰テストと障害耐性・セキュリティ・エラーハンドリング・マイグレーション確認。
  - **実装ガイダンス**: L4全量回帰、マイグレーション整合性、ロール認可・CSRF・暗号化確認。
  - **テスト要件**: L4全量テスト (`mvn test` 2365/2365 PASS, skip 0)。
  - **Demo**: 全量テストグリーン、review-ledger.md完全記録。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    実freee plan/会社IDの未確認項目は本taskのPASS条件ではなく、**本番releaseのgate**として別管理する。
