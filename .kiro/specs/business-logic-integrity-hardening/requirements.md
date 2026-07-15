# Requirements — 業務ロジック整合性強化（business-logic-integrity-hardening）

この spec は 2026-07-15 の全工程レビューで見つかった、既存業務ロジックの関連不整合を修正するためのもの。
新機能追加ではなく、エンティティ間の関連、状態副作用、金額単位、DB migration、権限境界をそろえることを目的とする。

対象となる主な業務フロー:

`候補者 → 要員 → 提案 → 契約 → 月次実績 → 請求/BP支払 → Dashboard/営業成績`

## R1. Flyway を開発・本番環境で有効にする

現状: `application.yml` で `spring.flyway.enabled=false` になっており、`application-prod.yml` でも有効化されない。
そのため、新規 DB の作成や既存 DB の migration が止まる。

受け入れ基準:

1. dev/prod では Flyway が有効、test profile では H2 schema 初期化のため明示的に無効のままにする。
2. 空の MySQL 8 DB に対して `db/migration` の全 migration が順番に成功する。
3. prod profile は共通 migration と `db/migration-prod` を同時に読み込み、重複 version や重複 index で失敗しない。
4. V10 と V17 が同じ BP 支払 index 変更を二重実行しない。
5. MySQL 8 で使えない `CREATE INDEX IF NOT EXISTS` は使わない。

## R2. 金額単位を「円」に統一する

現状: 画面では `800000` を 80 万円として扱う一方、`SettlementCalculator` がさらに 10000 倍している。
テスト内でも「円」と「万円」の解釈が混在している。

受け入れ基準:

1. `Project.unitPriceMin/Max`、`Proposal.proposedUnitPrice`、`Contract.sellingPrice/costPrice`、`WorkRecord.billingAmount/paymentAmount`、Invoice/BP 金額、集計 DTO はすべて円で保存・送受信する。
2. 契約単価 800000、精算幅 140～180、実績 150h の結果は 800000 円になる。
3. 精算幅を超過/不足した場合も、円の基準額から既存 formula で計算し、1 円単位で切り捨てる。
4. 提案から契約ドラフトを作るとき、暗黙の乗除算をしない。
5. 画面 label、placeholder、カード表示、Excel、テストデータの単位をそろえる。
6. Dashboard と営業成績で、契約見込み値と実績値の表示に桁ずれが起きない。

## R3. 契約の案件・顧客・要員・担当営業を整合させる

現状: 契約 request で無関係な `projectId` と `customerId` を指定できる。
`salesUserId` も営業以外や無効ユーザーを指せる。稼動中契約の要員変更時に派生状態が同期されない。

受け入れ基準:

1. 契約作成/更新前に project の存在を確認し、`project.customerId == contract.customerId` を必須にする。
2. `salesUserId` は未削除・有効・role=`営業` のユーザーだけ許可する。
3. 稼動中契約で要員を変更した場合、旧要員は他の稼動契約/進行中提案がなければ Bench に戻り、新要員は稼動中になる。
4. 契約が稼動中から準備中/終了/解約などへ変わる場合、対象要員の派生状態を再計算する。
5. 同一 request で状態と要員を同時に変える場合、更新前後の snapshot で副作用対象を決める。
6. 関連チェック失敗は DB FK 由来の 500 ではなく `BusinessException` として返す。

## R4. 提案状態機械を新規作成 API で迂回させない

現状: 新規提案時に前端/API から一次面接、成約、見送りなどを直接指定できる。
`closedAt`、履歴、契約ドラフト、通知は `changeStatus` 側でしか発生しない。

受け入れ基準:

1. 新規提案は `書類選考中` からだけ開始する。
2. request が初期状態以外を指定した場合は拒否する。
3. 初期状態以外への遷移は必ず `ProposalService.changeStatus` を通す。
4. 成約時は同一 transaction で closedAt、ProposalHistory、契約ドラフト、通知を作る。
5. 見送り時は同一 transaction で closedAt、ProposalHistory、要員状態再計算を行う。

## R5. BP 支払編集を同一権限境界と状態ルールに集約する

現状: `/api/bp-payments/{id}` は menu の `api_prefix` に属さず、権限 filter で素通りする。
この入口では status、paidDate、支払済 record の削除も直接操作できる。

受け入れ基準:

1. BP 支払の参照、追加、編集、削除、状態変更は invoice または work-record 権限に属する。
2. 未分類の BP 支払 write API を残さない。
3. status は `未払 ↔ 支払済` のみ許可する。
4. `支払済` へ変更するとき paidDate 未指定なら当日を入れ、`未払` へ戻すときは paidDate を消す。
5. 支払済 record の金額編集と削除は拒否し、先に状態操作で未払へ戻す。
6. `BpPaymentService` と `InvoiceService` が別々の支払状態ルールを持たない。

## R6. 業務上の冪等性を DB 制約で最終防衛する

現状: BP 階層、提案からの契約生成、自動更新契約は「存在確認してから insert」になっており、複数 instance の並行実行で重複しうる。

受け入れ基準:

1. 同一の未削除 workRecord + layerOrder には有効 BP 階層を 1 件だけ許可する。
2. 論理削除済みの履歴 record は、同一関係の再作成を妨げない。
3. 同一 proposal から作れる有効契約ドラフトは 1 件だけにする。
4. 同一 renewed_from_contract_id から作れる有効更新契約は 1 件だけにする。
5. 並行衝突は DB unique 制約で捕捉し、必要に応じて分かる business error または既存 record 返却へ変換する。
6. migration 適用前に既存重複を確認し、業務データを自動削除しない。

## R7. 営業成約率を正しい百分率で返す

現状: backend が `1/2 = 0.5` を返し、frontend がそのまま `%` を付けるため `0.5%` と表示される。

受け入れ基準:

1. 1 件成約 / 2 件提案は 50% と表示される。
2. 1 件成約 / 1 件提案は 100% と表示される。
3. 分母 0 は 0% と誤表示せず、既存 UI の空表示/null 表示に従う。

## R8. 候補者 stage は固定集合だけ許可する

現状: `Candidate.currentStage` に任意文字列を保存でき、funnel 集計と履歴が壊れる。

受け入れ基準:

1. 新規候補者の default は `応募受付`。
2. save/changeStage のどちらでも未知 stage を拒否する。
3. changeStage は history 作成前に stage を検証する。
4. `不採用` と `内定辞退` の理由必須 rule は維持する。

## R9. i18n resource と test を一致させる

現状: 英語・韓国語 resource に通知 message key が不足し、`MessageBundleConsistencyTest` が失敗する。

受け入れ基準:

1. ja/zh/en/ko の message key set を一致させる。
2. 追加 key の placeholder 番号を全言語でそろえる。
3. 対象 business test、message consistency test が通る。
4. MySQL migration smoke test は Docker が使える CI では skip されない。
