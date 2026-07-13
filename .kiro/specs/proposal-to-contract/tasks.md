# Implementation Plan — 成約→契約ドラフト自動生成のサーバー側移管(proposal-to-contract)

**着手条件: WS-B(referential-integrity)がマージ済みであること。** 上から順に実施する。

- [ ] 1. V14 マイグレーションとエンティティ
  - **Objective**: R1。契約と生成元提案の関連付け。
  - **実装ガイダンス**: design.md 1章。FKは張らない。`application-test.yml` schema-locations へ追記。`Contract` に `proposalId` 追加。
  - **テスト要件**: `mvn test` で H2 適用エラーなし(既存全テストグリーン)。
  - **Demo**: MySQL 起動で Flyway V14 適用。

- [ ] 2. `createDraftFromProposal` の実装
  - **Objective**: R2。冪等な契約ドラフト生成をサービス層に実装する。
  - **実装ガイダンス**: design.md 2章。`saveWithBusinessRules` 経由で採番・検証を再利用。customer_id は案件から解決。冪等チェックは proposal_id 一致。
  - **テスト要件**: design.md 4章の `ContractServiceImplTest` 5ケース。
  - **Demo**: なし(次タスクで統合確認)。

- [ ] 3. 成約遷移への組み込みと通知
  - **Objective**: R2, R3。成約と契約生成の同一トランザクション化。
  - **実装ガイダンス**: design.md 2章後半。`ProposalServiceImpl.changeStatus` の成約分岐に生成+publish。循環依存が出たら `ObjectProvider` で遅延解決。common.js iconColorMap に `CONTRACT_DRAFT` 追加。
  - **テスト要件**: design.md 4章の `ProposalServiceImplTest` 3ケース(特にロールバック検証: 案件を論理削除した提案を成約 → 提案ステータスが結果待ちのまま)。
  - **Demo**: カンバンで結果待ち→成約へドラッグ → 契約一覧に「準備中」ドラフトが自動採番付きで出現し、通知ベルにドラフト作成通知。

- [ ] 4. カンバンのクライアント側契約作成フロー撤去
  - **Objective**: R4, R5。二重フローの排除とUX整理。
  - **実装ガイダンス**: design.md 3章。**必ず proposal-kanban.js の成約分岐の現物を読んで**既存フロー(check-active、契約モーダル/POST)を特定してから置換する。
  - **テスト要件**: 既存 `ProposalApiControllerTest` グリーン。成約済み提案の再遷移が状態機械で拒否されることの既存テストも維持。
  - **Demo**: 成約ドラッグ → 確認ダイアログ「契約一覧を開きますか？」→ 遷移して確認。同じ提案で契約が2件できていないこと。`mvn test` 全件グリーン。
