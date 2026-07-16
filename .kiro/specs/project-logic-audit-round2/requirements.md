# Requirements — 全体ロジック再監査・第2次是正

この spec は 2026-07-16 に実施した全体再監査で確認した、権限境界、入力モデル、
状態機械、通知、外部連携、監査ログの残存不整合を是正するためのもの。

監査時点の自動テスト結果は以下の通り。

- `mvn -DforkCount=0 test`: 434 tests、failure 0、error 0、skip 2
- skip された項目は Docker 必須の MySQL Flyway smoke test と外部環境依存 test
- test green は既存 test の範囲を示すものであり、本 spec の不備が存在しないことを意味しない

既存の user 変更は上書き・巻き戻ししない。特に契約・提案画面とレスポンシブ対応には
監査開始前から未 commit の変更があるため、実装時に差分を確認して統合する。

## R1. 顧客営業活動の親子関係を強制する

現状:

- `/api/customers/{customerId}/activities/{activityId}` の更新、完了、削除は、
  `activityId` が URL の `customerId` に属するか確認していない。
- 別顧客の activityId を指定すると、URL 上の顧客とは無関係な活動を操作できる。

受け入れ基準:

1. 参照、更新、完了、削除は `activity.customerId == customerId` を必須とする。
2. 顧客または活動が存在しない場合と、他顧客に属する場合は 404 とする。
3. POST 前に顧客の存在を確認する。
4. request body の `id/customerId/createdBy/deletedFlag` は採用しない。
5. 他顧客の activityId を使った更新、完了、削除がすべて失敗する。

## R2. メニュー権限の曖昧な prefix をなくす

現状:

- `contract` と `contract-document` が同じ `path_prefix=/contract` を持つ。
- `MenuPermissionFilter` は最長一致を採用するが、同じ長さの候補が複数ある場合の結果が不定。
- `contract-document` は API と DB は存在するが、独立した page/template/sidebar 導線がない。

受け入れ基準:

1. 1 つの page URI が複数 menu の同長 prefix に一致しない。
2. 電子契約書機能は `/contract-document` と `/api/contract-documents` に統一する。
3. 管理者、営業、HR、マネージャーの許可 menu が明示される。
4. 管理者 bypass と sidebar 表示の結果が矛盾しない。
5. 同長 prefix が再登録された場合、起動時または権限 test で検出できる。

## R3. 通知を権限対象者だけに表示する

現状:

- `t_notification` は全ユーザー共通で、既読状態だけがユーザー別。
- 契約、請求、営業、採用等の通知本文が、その menu 権限を持たないユーザーにも見える。
- 遷移先だけを MenuPermissionFilter で拒否しても、通知本文の情報漏洩は防げない。

受け入れ基準:

1. 通知は関連する `menu_key` を保持できる。`NULL` は全員向けとする。
2. 通知一覧、ページング、未読件数、全既読は同じ可視性条件を使う。
3. 管理者は全通知を閲覧できる。
4. 非管理者は自 role に許可された menu の通知だけ閲覧できる。
5. 既存通知は migration 後も表示でき、未分類通知は全員向けとして扱う。

## R4. 書き込み API の mass assignment を防ぐ

現状:

- 複数 Controller が DB entity をそのまま `@RequestBody` で受け取る。
- `id`、`createdAt`、`updatedAt`、`createdBy`、`deletedFlag` 等を client から送信できる。
- `Proposal.proposedBy` や非正規化 field のように、service だけが決定すべき値も露出する。

受け入れ基準:

1. 外部入力用 Create/Update DTO と DB entity を分離する。
2. system field は DTO に含めない。
3. create 時の ID、作成者、作成日時、論理削除状態は server が決定する。
4. update は URL path の ID を正とし、body ID を使用しない。
5. entity を直接受ける既存 write endpoint を段階的に廃止する。
6. client が system field を送っても DB 値を変更できない。

## R5. 数値、日付、参照先の validation を完成させる

現状:

- 一部 field は frontend の `min/max` だけに依存している。
- `experienceYears`、`requiredCount`、各種金額、インセンティブ率、営業活動の必須 field 等に
  backend validation が不足している。
- `workMonth` の正規表現は `2026-99` を許可する。
- skill replace は parent/skill の存在確認前に全削除を開始する。

受け入れ基準:

1. 人数、経験年数、金額、工数、精算幅は 0 以上とする。
2. `commissionRate` は 0～100 とする。
3. email、文字列長、営業活動の必須 field を Bean Validation で検証する。
4. `workMonth/billingMonth` は `YearMonth.parse` 可能な値だけ許可する。
5. work record の対象月は契約期間と 1 日以上重なる必要がある。
6. 解約済み契約には新規工数を登録できない。
7. skill replace は parent と全 skill ID を検証してから置換する。
8. validation 失敗は DB 例外や 500 ではなく 400/409 の業務応答になる。

## R6. 契約・提案・担当営業の状態と並行実行を守る

現状:

- 契約編集 API から status を直接更新でき、専用状態機械がない。
- 同一要員への担当営業割当と主担当変更は application check のみで、
  並行 request により重複担当や複数主担当が成立しうる。
- 同一要員・案件の進行中提案を複数作成できる。

受け入れ基準:

1. 契約通常更新は status を変更しない。
2. 契約状態は専用 API で `準備中→稼動中/解約`、
   `稼動中→終了/解約` のみ許可する。
3. `終了/解約` は終端状態とし、通常操作では戻せない。
4. 契約状態変更と要員状態再計算は同一 transaction とする。
5. 同一要員・営業ユーザーの有効割当は 1 件だけにする。
6. 1 要員の有効主担当は 1 件だけにする。
7. 同一要員・案件の未クローズ提案は 1 件だけにする。
8. 並行衝突は DB unique 制約で最終防衛し、分かる business error に変換する。

## R7. 画面 route と通知リンクを一致させる

現状:

- 要員詳細の実 route は `/engineer/detail?id={id}` だが、
  一部通知と availability calendar は `/engineer/detail/{id}` を使う。
- 契約通知は存在しない `/contract/detail/{id}` へ遷移する。
- 案件通知は `/project/detail/{id}` を使うが、対応 template は存在しない。
- `ProjectPageController` は存在しない `project/detail.html` を返す mapping を持つ。

受け入れ基準:

1. 要員詳細は `/engineer/detail?id={id}` に統一する。
2. 契約通知は `/contract/list` へ遷移する。
3. 案件通知は `/project/list` へ遷移する。
4. 存在しない project detail mapping を削除する。
5. 通知と主要画面の全 link を test し、404 を残さない。

## R8. 電子契約書機能を実利用可能な状態にする

現状:

- CloudSign の status request に Authorization header がない。
- `cloudsign.enabled=false` でも stub ID を作り、送信成功相当の状態へ進む。
- sync result の signed PDF/certificate を保存していない。
- 生成 PDF と署名済みファイルを取得する API/UI がない。

受け入れ基準:

1. send/status の両方で Bearer Token を送信する。
2. 外部 HTTP は共通 timeout 設定済み client を使用する。
3. disabled または設定不足時は明示的な business error とし、送信済みにしない。
4. signed PDF、certificate、completedAt、errorMessage を同期結果から保存する。
5. 契約書作成、送信、同期、PDF download を行える独立画面を追加する。
6. 外部 API の 401、429、5xx、timeout を状態と監査ログへ残す。

## R9. freee 給与連携を画面・token・監査まで完成させる

現状:

- menu `/payroll` と API は存在するが、PageController、template、JS がない。
- OAuth callback と社員紐付けが `connectedBy/confirmedBy=null` を保存する。
- access token refresh がなく、期限切れ後は常に provider unavailable になる。
- token encryption key に危険な default 値がある。

受け入れ基準:

1. `/payroll` で接続状態、社員一覧、要員紐付け、給与明細を操作できる。
2. OAuth 接続者と社員紐付け確認者にログイン user ID を保存する。
3. token 期限前または最初の 401 時に refresh token で 1 回だけ更新・再試行する。
4. prod は明示的な暗号鍵がない場合に起動失敗する。
5. 無効な state、期限切れ token、refresh 失敗、重複 employee link を業務エラー化する。

## R10. メール送信結果を正しく伝える

現状:

- 提案メール API は非同期処理を開始した時点で success を返す。
- template 不存在、SMTP 未設定の dry-run、実送信失敗を呼び出し元が区別できない。

受け入れ基準:

1. template と宛先は queue 投入前に同期検証する。
2. API は `QUEUED/DRY_RUN` を区別して返す。
3. 実送信状態を `QUEUED/SENDING/SENT/FAILED` で永続化する。
4. failed record は error message と retry count を保持する。
5. 同じ操作の二重 submit で意図しない重複送信を行わない。

## R11. HTTP status と監査結果を実際の処理結果に合わせる

現状:

- validation/business error も HTTP 200 で返り、body の code だけが 400/500 になる。
- `ApiAuditFilter` は最終 authorization/error handling より内側で実行されるため、
  実際は 403 の request を 200 として記録する場合がある。

受け入れ基準:

1. `ApiResult` の JSON shape は維持する。
2. validation=400、not found=404、state/duplicate conflict=409、unexpected=500 とする。
3. frontend 共通 API wrapper は非 2xx の JSON message を表示できる。
4. audit log は最終 HTTP status、application code、success flag を保持する。
5. 403/400/409/500 がすべて正しい audit result になる。
6. password、token、Webhook URL、request body の機微情報は audit log に保存しない。

## R12. テストと migration の実行保証を強化する

受け入れ基準:

1. 各修正は再現 test を先に追加し、その後実装する。
2. H2 schema と migration 対象 schema を同期する。
3. Docker 使用可能な CI では MySQL 8 Flyway smoke test を skip しない。
4. 外部連携は mock server で成功、401、429、5xx、timeout を検証する。
5. `mvn test`、`git diff --check`、各 task の Demo を完了条件とする。
