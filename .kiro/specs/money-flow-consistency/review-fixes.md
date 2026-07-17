# Review Fixes — 実装レビュー指摘と修正ガイド（money-flow-consistency）

対象: コミット `1dd2b27`（R1〜R8 実装、ブランチ `claude/money-flow-consistency-u7xqw7`）に対する
コードレビュー（2026-07-16、effort=high）の指摘事項と修正材料。

レビュー時の確認済み事項:

- R1〜R8 すべて実装済み。design.md への準拠度は高い（契約専用DTO・V27採番・月末判定のJava側確定・
  Excel帳票の一括ロード化・H2スキーマ3点同期・i18n 4言語・新規参照キーの実在をすべて確認）。
- `mvn test`: **458 tests / failures 0 / errors 0 / skipped 2**（skip は Docker 必須の smoke test）。
- 追加のプラス: `getStatusBadge` が DB の日本語ステータスをローカライズ済みラベルと比較していた
  既存 locale バグも修正されている。

指摘は 8 件。**F1〜F5 はマージ前修正を推奨（ブロッカー）**、F6〜F8 は任意（フォローアップ可）。

> **対応状況（2026-07-16 追記）**: F1〜F8 すべて本ブランチで修正済み。対応するテストを同一コミットに含め、
> `mvn test` 全緑（**463 tests / failures 0 / errors 0 / skipped 2**、skip は Docker 必須の smoke test）を再確認済み。
> 各節見出しに **[対応済み]** を付記。残存検証課題（V27 の実MySQL smoke test・ブラウザDemo）は
> Docker/GUI 環境が必要なため本環境では未実施（下記「残存する検証課題」参照）。

---

## F1.【重大・データ破壊】退職済み担当営業の契約を編集すると sales_user_id が黙って NULL 化される　**[対応済み]**

- **場所**: `static/js/modules/contract.js:214`（`openEditContract` の salesUserId プリセット）
  ＋ `buildContractPayload`（`contract.js:234`）
- **原因**: 担当営業セレクトは `/api/engineers/sales-user-options` で **`status=1` の在職営業のみ**
  ロードされる（`EngineerSalesServiceImpl.salesUserOptions`）。退職済み営業が担当の契約を編集すると
  該当 `<option>` が無く `val()` が空になり、payload は `salesUserId: null` を送る。
  `Contract.salesUserId` は `FieldStrategy.ALWAYS` のため **NULL が実際に DB へ書き込まれる**。
- **影響**: 終了日を直したいだけの編集で、成約帰属・営業成績・以降月のインセンティブが無警告で消える。
- **修正方針**: `openEditContract` でプリセット前に「現在値がセレクトに存在するか」を確認し、
  無ければ退職者ラベル付きの option を動的補完して原値を保持する。

  ```js
  // openEditContract 内、salesUserId プリセットの直前に挿入
  if (c.salesUserId != null && $(`#cont-salesUserId option[value="${c.salesUserId}"]`).length === 0) {
      // 退職済み等でセレクト対象外の担当営業: 原値を保持するための option を補完する
      const label = c.salesUserName
          ? `${c.salesUserName}（${SES.i18n.t('contract.salesRep.inactive')}）`
          : SES.i18n.t('contract.salesRep.inactive');
      $('#cont-salesUserId').append(`<option value="${c.salesUserId}">${SES.escapeHtml(label)}</option>`);
  }
  ```

  - `GET /api/contracts/{id}` は現状エンティティを返すため `salesUserName` を持たない。
    ラベル表示用に (a) detail レスポンスを `ContractListDto` 相当（`selectPageWithNames` の1件版）へ
    差し替える、または (b) 名前なしで「退職済み担当」の固定ラベルにする——どちらでも可（(b)が最小）。
  - **サーバ側の注意**: `ContractServiceImpl.validate` は `salesUserId != null` のとき
    「在職の営業」であることを要求するため、原値保持のまま PUT すると更新が拒否される。
    validate に「**既存契約と同一の salesUserId なら在職チェックをスキップ**」の分岐を追加すること
    （帰属は成約時点の事実であり、退職後も保持する仕様。`engineer-sales-commission` R3-2 と整合）。

    ```java
    // updateWithBusinessRules 経由の validate で、変更がない場合は在職チェックを免除する
    boolean salesUserUnchanged = old != null && Objects.equals(old.getSalesUserId(), c.getSalesUserId());
    if (c.getSalesUserId() != null && !salesUserUnchanged) { /* 既存の在職チェック */ }
    ```

- **i18n**: `contract.salesRep.inactive`（例: ja=退職済み担当, en=Inactive rep）を4言語へ追加。
- **テスト**: `ContractServiceImplTest` — 退職済み担当のままの更新が通ること /
  退職済み担当への**変更**は引き続き拒否されること、の2ケース。

## F2.【重大・機能不全】選択肢APIのページング（size=10）により 11 件目以降のマスタを持つ契約が編集不能　**[対応済み]**

- **場所**: `static/js/modules/contract.js:59, 67, 75`（`loadSelectOptions`）
- **原因**: `/api/engineers`・`/api/projects`・`/api/customers` はいずれも `defaultValue size=10` の
  ページング API。セレクトには先頭 10 件しか入らないため、11 件目以降の要員/案件/顧客を持つ契約は
  `openEditContract` のプリセットが失敗して `null` になり、`@NotNull` 検証で保存が拒否される
  （＝**編集 UI が実データ量で機能しない**）。新規作成モーダルも先頭 10 件しか選べない（既存問題）。
- **修正方針（最小）**: 3 つの `$.get` に十分大きい `size` を明示する。

  ```js
  $.get('/api/engineers?size=1000', ...);
  $.get('/api/projects?size=1000', ...);
  $.get('/api/customers?size=1000', ...);
  ```

  恒久対応（任意・フォローアップ）: `sales-user-options` と同型の軽量 options 専用エンドポイント
  （id+名称のみ、ページングなし）を追加するか、Select2 等の検索型セレクトへ置換する。
- **テスト**: 自動テスト困難（JS）。A2 の Demo 手順（編集→保存）を 11 件以上のマスタ投入状態で再実施。

## F3.【中・データ破壊】confirmMonth の syncRootBpAmount が layer_order=1 条件を欠き、親なし手動階層の金額を上書きする　**[対応済み]**

- **場所**: `service/impl/WorkRecordServiceImpl.java:161`（`syncRootBpAmount` の selectList 条件）
- **原因**: design.md R4 は「**parent_payment_id IS NULL かつ layer_order = 1** の未払行のみ金額同期」
  と規定しているが、実装は `isNull("parent_payment_id")` のみ。`BpPaymentServiceImpl.addLayer` は
  parent なし・layer_order≥2 の行を許容するため、手動登録した親なし階層（支払先会社名・人為金額つき）
  が月次確定のたびに `record.getPaymentAmount()` で上書きされる。
- **修正**: 条件を1つ追加するだけ。

  ```java
  List<BpPayment> roots = bpPaymentMapper.selectList(new QueryWrapper<BpPayment>()
          .eq("work_record_id", record.getId())
          .isNull("parent_payment_id")
          .eq("layer_order", 1));   // ← 追加（design R4 準拠）
  ```

- **テスト**: `WorkRecordServiceImplTest` に「parent なし・layer_order=2 の未払行は confirm で
  金額更新されない」ケースを追加。

## F4.【中・UX】編集 UI で nullable 非 ALWAYS フィールドのクリアが黙って無効　**[対応済み]**

- **場所**: `static/js/modules/contract.js:234`（`buildContractPayload`）＋ `entity/Contract.java`
- **原因**: `endDate` / `settlementHoursMin` / `settlementHoursMax` / `fractionRule` は
  グローバル `update-strategy: not_null` のため null 送信がスキップされる。編集で空にして保存すると
  成功トーストが出るのに値が残る（特に終了日: 「無期限に戻す」が不可能で、集計も旧 end_date で
  打ち切られ続ける）。
- **修正方針**: 4 フィールドに `@TableField(updateStrategy = FieldStrategy.ALWAYS)` を付与する。
  - 安全性の前提「**全更新経路が全項目を送ること**」の確認結果: 契約エンティティを部分更新するのは
    (1) `updateWithBusinessRules`（＝編集UI、全項目送信・今回の対象）、
    (2) `changeStatus`（selectById で全ロード後 updateById → 全フィールド保持で安全）の2経路のみ。
    `saveWithBusinessRules`/`createDraftFromProposal`/`ContractRenewalServiceImpl` は insert のため無関係。
  - `Contract.java` の各フィールドに ALWAYS の理由コメント（既存 salesUserId と同型）を付けること。
- **テスト**: `ContractServiceImplTest` or H2 統合テストで「endDate を null で update → DB 上も NULL」
  を検証（Mockito 単体では updateStrategy を検証できないため **H2 実 DB テスト推奨**。
  `AnalyticsProjectionMapperIntegrationTest` と同じ `BaseIntegrationTest` 系を利用）。

## F5.【低・1行】BP_AMOUNT_MISMATCH 通知のリンク先 /invoice/bp-payments が存在せず 404　**[対応済み]**

- **場所**: `service/impl/WorkRecordServiceImpl.java:177`
- **原因**: `InvoicePageController` は `/invoice` と `/invoice/{id}/print` のみ。BP支払は
  `/invoice` ページ内のタブ（`#bp-payment`）。
- **修正**: リンクを `"/invoice"` へ変更（タブ直開きしたければ `"/invoice#bp-payment"`）。
  あわせて message を他の publish 呼び出しと同じ i18n JSON 配列形式へ:

  ```java
  notificationService.publish(
          "BP_AMOUNT_MISMATCH",
          "BP支払金額の不一致",
          "[\"notification.msg.BP_AMOUNT_MISMATCH\", \"" + root.getId() + "\"]",
          "/invoice",
          "bp-amount-mismatch-" + root.getId());
  ```

  `notification.msg.BP_AMOUNT_MISMATCH` を 4 言語へ追加（例: ja=支払済BP支払(#{0})の金額が最新の精算額と一致しません）。
- **テスト**: 既存の `testConfirmMonth_支払済BP1階層目の不一致は更新せず通知する` の verify を
  リンク値 `eq("/invoice")` で厳密化。

---

## F6〜F8.（任意・フォローアップ可）

### F6.【低】解約日が元 end_date より後の場合に契約期間が黙って延長される　**[対応済み]**

- **場所**: `service/impl/ContractServiceImpl.java:193`
- 解約なのに end_date が延び、売上・粗利・インセンティブの計上月数が増える反直感な結果を許容している。
- **修正案**: 元 `end_date` 非 NULL かつ `cancelDate` がそれより後なら
  `error.contract.cancelDateAfterEnd` で拒否（メッセージ4言語追加）。テスト1ケース追加。

### F7.【低・保守性】STATUS_TRANSITIONS がフロントに複製されている　**[対応済み: 相互参照コメント追加]**

- **場所**: `static/js/modules/contract.js:7`
- サーバの `ALLOWED_STATUS_TRANSITIONS` が唯一の権威。恒久対応は
  `GET /api/contracts/{id}` のレスポンスに `allowedTransitions` を含めてフロントはそれを表示するだけに
  する。最低限なら両者に相互参照コメントを追加。

### F8.【低】解約日ダイアログの既定値が UTC 日付　**[対応済み]**

- **場所**: `static/js/modules/contract.js:326`
- `new Date().toISOString()` は UTC のため JST 0:00〜9:00 は前日がデフォルトになる。

  ```js
  const d = new Date();
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  ```

---

## 残存する検証課題（コード修正ではなく実行環境の問題）

1. **V27 マイグレーションは実 MySQL で未実行**: smoke test（`FlywayMigrationSmokeTest`）は
   Docker 不在のため skip。マージ前に Docker あり環境で
   `mvn test -Dtest=FlywayMigrationSmokeTest` を1回実行し、V27 の `ALTER TABLE ... MODIFY/ADD COLUMN`
   が MySQL 8 で通ること・`t_invoice.tax_rate` アサーションが成立することを確認する。
2. **ブラウザ Demo 未実施**: tasks.md 各タスクの Demo（成約→編集→稼動化→勤怠→請求の一気通貫、
   税率改定後の過去請求書表示など）はヘッドレス環境では未検証。F1/F2 修正後にまとめて実施する。

## 修正の進め方

- 推奨順序: **F2 → F1 → F3 → F5 → F4**（F4 のみ ALWAYS 化の影響確認に H2 統合テストが必要で重め）
  → F6〜F8 は任意。
- すべて本ブランチ（`claude/money-flow-consistency-u7xqw7`）上で修正し、各修正に対応するテストを
  同一コミットに含めること。完了後に `mvn test` 全緑を再確認し、本ドキュメントの各項目へ
  `[対応済み: <commit>]` を追記する。
