# Requirements — 成約→契約ドラフト自動生成のサーバー側移管(proposal-to-contract)

**フェーズ2: WS-B(referential-integrity)完了後に着手すること**(`ContractServiceImpl` を共に編集するため)。

現状、提案カンバンで「成約」へ移動した際の契約作成は **proposal-kanban.js がクライアント側で
別リクエストとして**行っている。成約API成功後にブラウザを閉じる・契約作成が失敗する等で
「成約済みなのに契約が存在しない」中間状態が生まれ、検知手段もない。
成約時の契約ドラフト作成をサーバー側の同一トランザクションへ移管する。

新規マイグレーション: `V14__contract_proposal_link.sql`。

対象コード: `ProposalServiceImpl` / `ContractService(Impl)` / `entity/Contract` /
`proposal-kanban.js`。

## R1. 契約と提案の関連付け

受入基準:
1. `V14__contract_proposal_link.sql` で `t_contract` に
   `proposal_id BIGINT NULL COMMENT '生成元提案ID'` とインデックスを追加する。
2. マイグレーションは `application-test.yml` の `schema-locations` にも追記される。
3. `entity/Contract` に `proposalId` フィールドが追加される。

## R2. 成約時の契約ドラフト自動生成

受入基準:
1. `ProposalServiceImpl.changeStatus` で `成約` へ遷移した際、**同一トランザクション内で**
   契約ドラフトが自動生成される。トランザクション失敗時は成約への遷移ごとロールバックされる。
2. 生成される契約の内容:
   - `proposalId` = 提案ID、`engineerId` / `projectId` = 提案から引継ぎ
   - `customerId` = 提案の案件(`t_project.customer_id`)から解決
   - `sellingPrice` = 提案の `proposedUnitPrice`(NULL なら NULL のまま)
   - `startDate` = 翌月1日、`endDate` = NULL
   - `status` = `準備中`(ドラフト。要員ステータス連動は発火しない)
   - `contractNo` = 既存の自動採番(空で `saveWithBusinessRules` に渡す)
   - `remarks` = 「提案#{id}の成約により自動生成」
3. 冪等性: 同じ提案IDから生成された契約(`proposal_id` 一致、論理削除除く)が既に存在する場合は
   二重生成しない(既存の状態機械により成約の二重遷移自体が防がれているが、防御として実装)。
4. 契約作成は既存の `ContractService.saveWithBusinessRules` を経由する
   (採番リトライ・検証ロジックを再利用)。

## R3. 成約時の通知

受入基準:
1. ドラフト生成後、`NotificationService.publish` で通知を発行する:
   type=`CONTRACT_DRAFT`、メッセージ「提案の成約により契約ドラフト({契約番号})を作成しました。
   内容を確認して契約を確定してください」、リンク `/contract/list`、
   dedupeKey=`contract-draft:{proposalId}`。
2. `common.js` の `iconColorMap` に `CONTRACT_DRAFT` を追加する(既存WS-Fと衝突しないよう
   マージ順に注意。色は `text-accent-blue`)。

## R4. カンバン側の二重フロー撤去

受入基準:
1. proposal-kanban.js の「成約時にクライアントから契約を作成する」既存フロー
   (契約モーダル起動 or `/api/contracts` POST — 現物を確認)は撤去する。
2. 成約ドロップ成功後は Toast「成約しました。契約ドラフトを作成しました」を表示し、
   契約一覧画面へのリンク(または遷移確認の SweetAlert)を提示する。
3. `/api/contracts/check-active` を成約前チェックに使っている場合、そのチェックは
   サーバー側遷移検証で代替されるため、UX 目的で残すか撤去するかを実装時に判断してよい
   (残す場合も削除ガードの代替にはならない点をコメントで明記)。

## R5. 回帰

受入基準:
1. 成約以外の遷移(`見送り` 等)の挙動・履歴記録(`t_proposal_history`)は不変。
2. 既存 `ProposalServiceImplTest` / `ProposalApiControllerTest` がグリーンのまま
   (成約ケースは契約生成の検証を追加した形に更新してよい)。
