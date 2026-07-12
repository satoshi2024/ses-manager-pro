# Implementation Plan — 提案・契約ワークフロー(P2)

- [ ] 1. `LoginUser` + `SecurityUtils` の導入
  - **Objective**: どの層からもログイン中ユーザー ID を取得できるようにする(P8 の created_by 自動設定でも再利用)。
  - **実装ガイダンス**: design.md 1章。`CustomUserDetailsService` の戻り値を `LoginUser`(SysUser 保持)に変更。既存ログインが壊れないことを確認。
  - **テスト要件**: `SecurityUtils` 単体テスト(SecurityContext にモック認証をセット)。
  - **Demo**: ログイン後、任意 API 内で `SecurityUtils.currentUserId()` が admin の ID を返す。

- [ ] 2. 提案ステータス状態機械(テスト駆動)
  - **Objective**: 不正遷移の禁止、closed_at / changed_by の記録。
  - **実装ガイダンス**: 先に `ProposalServiceImplTest` で許可/禁止遷移・closed_at・changed_by を固めてから design.md 2.1 を実装。
  - **テスト要件**: 遷移表の代表4ケース + 終端からの遷移禁止 + closed_at 設定 + 履歴 changed_by。
  - **Demo**: カンバンで「書類選考中」→「成約」に直接ドラッグするとエラー Toast が出てカードが戻る。

- [ ] 3. カンバン D&D の失敗差し戻し
  - **Objective**: API エラー時にカードを元の列へ戻す。
  - **実装ガイダンス**: `proposal-kanban.js` の Sortable `onStart` で元列を記録、AJAX 失敗/`code!==200` で差し戻し + エラーメッセージ表示。
  - **Demo**: タスク2のDemoと同時に確認。

- [ ] 4. `EngineerStatusService` の実装(テスト駆動)
  - **Objective**: 提案作成/見送り・契約稼動/終了に伴う要員ステータス自動連動。
  - **実装ガイダンス**: design.md 3章。`releaseIfIdle` の判定クエリは H2 で検証。
  - **テスト要件**: releaseIfIdle 3分岐 + onProposalCreated が Bench 以外を書き換えないこと。
  - **Demo**: 提案作成で要員が「提案中」になり、見送りで「Bench」へ戻る。

- [ ] 5. 契約番号採番 + 契約バリデーション(テスト駆動)
  - **Objective**: `C-YYYYMM-NNNN` 自動採番と入力検証。
  - **実装ガイダンス**: design.md 4.1〜4.3。`ContractApiController` の POST/PUT を業務メソッド経由に差し替え、`check-active` エンドポイント追加。
  - **テスト要件**: 採番形式・連番・重複再試行、日付逆転・精算逆転エラー、粗利マイナス警告メッセージ。
  - **Demo**: 契約番号を空で登録すると `C-202607-0001` 形式で採番される。終了日<開始日はエラー。

- [ ] 6. 成約→契約作成モーダル連携
  - **Objective**: カンバン成約時に事前入力済みの契約作成モーダルを開き、そのまま契約登録できる。
  - **実装ガイダンス**: design.md 5章。稼動中契約の重複は SweetAlert2 確認。保存成功で提案の proposal_id 紐付けを確認。
  - **Demo**: 提案を「結果待ち」→「成約」へ移動 → モーダルに要員/案件/単価が入っている → 保存で契約一覧に出現し、要員が「稼動中」になる。

- [ ] 7. 契約終了時の Bench 戻し
  - **Objective**: 契約を「終了/解約」へ更新した際に要員を Bench へ戻す(他契約が無い場合のみ)。
  - **実装ガイダンス**: `updateWithBusinessRules` 内で旧ステータスと比較し、終了系への変化時のみ `releaseIfIdle` 呼び出し。
  - **テスト要件**: 2契約持ちの要員は Bench に戻らないこと。
  - **Demo**: 契約を「終了」に更新 → 要員一覧でステータスが「Bench」。
