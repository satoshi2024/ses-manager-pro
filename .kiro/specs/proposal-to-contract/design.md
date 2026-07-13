# Design — 成約→契約ドラフト自動生成のサーバー側移管(proposal-to-contract)

**前提: WS-B(referential-integrity)マージ後の統合ブランチから着手する。**
編集対象:

- `src/main/resources/db/migration/V14__contract_proposal_link.sql`(新規)
- `src/test/resources/application-test.yml`(schema-locations へ V14 追記)
- `src/main/java/com/ses/entity/Contract.java`
- `src/main/java/com/ses/service/ContractService.java` / `service/impl/ContractServiceImpl.java`
- `src/main/java/com/ses/service/impl/ProposalServiceImpl.java`
- `src/main/resources/static/js/modules/proposal-kanban.js`
- `src/main/resources/static/js/common.js`(iconColorMap 1行のみ)
- テスト: `ProposalServiceImplTest` / `ContractServiceImplTest`

## 1. マイグレーション(R1)

```sql
ALTER TABLE t_contract ADD COLUMN proposal_id BIGINT NULL COMMENT '生成元提案ID';
CREATE INDEX idx_contract_proposal ON t_contract (proposal_id);
```

外部キーは張らない(提案は論理削除運用のため RESTRICT が意味を持たず、既存スタイルにも
FKなし参照が多い — `V12` の前例を確認して合わせる)。

## 2. 契約ドラフト生成(R2)

`ContractService` に新メソッドを追加:

```java
/** 成約した提案から契約ドラフト(準備中)を生成する。既に生成済みなら何もせず既存契約を返す。 */
Contract createDraftFromProposal(Proposal proposal);
```

`ContractServiceImpl` 実装(`@Transactional(rollbackFor = Exception.class)`):

```
1. 冪等チェック: selectOne(proposal_id = proposal.getId()) が存在すればそれを返す
2. Project を projectMapper で取得し customer_id を解決(ProjectMapper を注入追加。
   案件が見つからない場合は BusinessException("提案の案件が見つかりません"))
3. Contract を組み立て(requirements R2-2 の項目)て saveWithBusinessRules(contract) を呼ぶ
   ※ status=準備中 なので saveWithBusinessRules 内の要員ステータス連動(稼動中時のみ)は発火しない
4. 生成した契約を返す
```

`ProposalServiceImpl.changeStatus` の成約分岐(`"成約".equals(newStatus)`)に追加:

```java
if ("成約".equals(newStatus)) {
    Contract draft = contractService.createDraftFromProposal(proposal);
    notificationService.publish("CONTRACT_DRAFT", "契約ドラフト作成",
        "提案の成約により契約ドラフト(" + draft.getContractNo() + ")を作成しました。内容を確認して契約を確定してください",
        "/contract/list", "contract-draft:" + proposal.getId());
}
```

`ProposalServiceImpl` に `ContractService` / `NotificationService` を注入追加。
**循環依存に注意**: `ContractServiceImpl` は `EngineerStatusService` に依存しており、
`ProposalService` へは依存していないため `Proposal→Contract` 方向の注入で循環は生じない。
(万一 Bean 循環が出た場合は `ObjectProvider<ContractService>` で遅延解決する。)

通知の publish は成約トランザクションと同一トランザクション内でよい
(失敗時は成約ごとロールバックが要件どおり)。

## 3. カンバン側(R4)

proposal-kanban.js の成約時フロー(`newStatus === '成約'` 分岐、`check-active` 呼び出し、
契約作成モーダル/POST — **現物を読んで正確に把握してから**)を以下に置換:

- ステータス変更 API(`PUT /api/proposals/{id}/status` 等、既存のまま)成功後、
  `Swal.fire`(成功アイコン、「成約しました。契約ドラフトを作成しました。契約一覧を開きますか？」、
  確認で `location.href='/contract/list'`)を表示。
- クライアントからの契約 POST は削除。カンバン再描画は既存処理を維持。

## 4. テスト設計

- `ContractServiceImplTest`: `createDraftFromProposal` — (a) 正常生成(全項目・準備中・採番済み・
  proposalId 設定)、(b) 2回呼んでも契約は1件(冪等)、(c) 案件不在で BusinessException、
  (d) proposedUnitPrice NULL でも生成できる、(e) 生成後も要員ステータスが変わらない(準備中のため)。
- `ProposalServiceImplTest`: (a) 結果待ち→成約 で契約ドラフトと通知が生成される、
  (b) 契約生成が失敗(案件不在)なら提案ステータスもロールバックされる、
  (c) 見送り遷移では契約が生成されない。
