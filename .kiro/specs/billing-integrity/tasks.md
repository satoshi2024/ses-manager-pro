# Implementation Plan — 請求・実績データ整合性(billing-integrity)

タスクは上から順に実施する(1→2 は依存あり: 1 で追加する @Select を 2 が再利用する)。
編集対象ファイルは design.md 冒頭のリストから逸脱しないこと(他WSと並行実施のため)。

- [ ] 1. reopen ガード(支払済BP支払・請求済み実績の保護)
  - **Objective**: R1, R2。月次解除が支払実績・請求書と矛盾するデータを作れないようにする。
  - **実装ガイダンス**: design.md 1章。`InvoiceItemMapper` に `selectActiveInvoiceNosByWorkRecordIds`(`<script>`+`<foreach>` の IN 句)を追加 → `reopenMonth` でチェック(請求済み→支払済の順)を更新処理より先に実行 → BP支払削除に `status='未払'` 条件を追加。
  - **テスト要件**: `WorkRecordServiceImplTest` に追加 — (a) 支払済BP支払ありで reopen → BusinessException かつ実績ステータス不変、(b) 未払のみ → 成功かつ未払だけ削除、(c) 有効請求書の明細に紐づく実績を含む月の reopen → 請求書番号入りメッセージで拒否、(d) 取消済み(deleted_flag=1)請求書のみに紐づく場合 → 成功。
  - **Demo**: 実績確定→BP支払を支払済に→管理者で月次解除 → エラーToastに「支払済のBP支払が1件…」。

- [ ] 2. saveHours の請求済みガードと DTO 検証・競合処理
  - **Objective**: R3, R8。実績入力APIの入力検証・並行安全性・請求済み保護。
  - **実装ガイダンス**: design.md 2章・7章。`WorkRecordSaveRequest`(新規DTO+Bean Validation)→ コントローラー書き換え → サービスに請求済みチェックと `DuplicateKeyException`→`BusinessException` 変換を追加。
  - **テスト要件**: (a) workMonth "2026/07"(不正形式)で 400+日本語メッセージ、(b) actualHours=-1 で 400、(c) 請求書明細に紐づく実績の saveHours → 「計上済みの実績は編集できません」、(d) uk_work_record 衝突をシミュレート(同一契約×月を事前INSERTした状態で service の insert 分岐を通す)→ BusinessException になり 500 にならない。
  - **Demo**: 勤怠グリッドで -1 を入力保存 → 日本語エラーToast。1000 も同様。

- [ ] 3. 請求書取消(void)API + 未請求クエリの防御
  - **Objective**: R4。誤発行請求書の取消と実績の再請求可能化。
  - **実装ガイダンス**: design.md 3章。`InvoiceService.voidInvoice` → 明細物理削除+本体論理削除(この順)。`PUT /api/invoices/{id}/void` 追加。`selectUnbilledWorkRecords` の NOT IN を `t_invoice` JOIN + `deleted_flag=0` 形に書き換え。
  - **テスト要件**: `InvoiceServiceImplTest` に追加 — (a) 未送付の請求書を void → invoice_item 0件・invoice の deleted_flag=1(JdbcTemplate で生確認)、(b) void 後に同顧客×同月 generate → 解放された実績で新請求書が作れる、(c) 入金済を void → BusinessException、(d) 存在しないID → BusinessException。
  - **Demo**: 請求書生成→取消→同月で再生成が成功し、実績が新しい請求書に載る。

- [ ] 4. 採番の論理削除対応
  - **Objective**: R5。取消済み請求書番号の再利用による UNIQUE 衝突を根絶する。
  - **実装ガイダンス**: design.md 4章。`selectMaxInvoiceNoIncludingDeleted`(@Select、論理削除フィルタ迂回)→ `generateInvoiceNo` を最大番号+1 方式に書き換え。リトライ構造は維持。
  - **テスト要件**: (a) INV-YYYYMM-0002 を論理削除した状態で採番 → 0003 が返る(0002 を再利用しない)、(b) 該当月に1件もなければ 0001。
  - **Demo**: 生成→取消→再生成で番号が飛び番になる(重複エラーが出ない)。

- [ ] 5. 請求書ステータス状態機械
  - **Objective**: R6。不正遷移の拒否と paid_date の整合。
  - **実装ガイダンス**: design.md 5章。`ALLOWED` 遷移表 + 入金済離脱時の `UpdateWrapper` による paid_date 明示クリア(not_null 更新戦略に注意)。
  - **テスト要件**: (a) 未送付→入金済 が拒否、(b) 送付済→入金済 は paidDate 必須(なしで BusinessException)、(c) 入金済→送付済 で paid_date が NULL に戻る(生SQLで確認)、(d) 未知ステータスは拒否。
  - **Demo**: 一覧で「入金済」を「送付済」に戻す → 入金日表示が消える。

- [ ] 6. BP支払ステータスのサービス化
  - **Objective**: R7。黙殺されていた異常系の可視化とロジックのサービス層移動。
  - **実装ガイダンス**: design.md 6章。`InvoiceService.changeBpPaymentStatus` を追加しコントローラーは委譲のみに。未払戻し時の paid_date クリアは `UpdateWrapper` で。
  - **テスト要件**: (a) 存在しないID → BusinessException(success が返らないこと)、(b) 支払済→未払 で paid_date が NULL、(c) 不正値 "済" → 拒否。
  - **Demo**: BP支払タブで支払済→未払に戻す → 支払日が消える。

- [ ] 7. フロントエンド(取消ボタン)と回帰確認
  - **Objective**: R4-6。取消操作のUI提供と請求画面全体の回帰。
  - **実装ガイダンス**: design.md 3章フロント節。invoice.js に取消ボタン(未送付/送付済のみ表示)+ SweetAlert2 確認 + 既存 fetch/CSRF パターン。表示文字列は `SES.escapeHtml` を通す。
  - **テスト要件**: `InvoiceApiControllerTest` に void エンドポイントの正常/異常(入金済)ケースを追加。
  - **Demo**: 請求書一覧から取消 → 確認ダイアログ → 一覧から消え、成功Toast。`mvn test` 全件グリーン。
