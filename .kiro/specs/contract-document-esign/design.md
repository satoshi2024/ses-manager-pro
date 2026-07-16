# 設計

- `m_contract_template` と `t_contract_document` を V20 Flyway で追加し、H2 スキーマにも反映する。
- `ContractDocumentService` が変数置換、HTML サニタイズ、PDF 出力、SHA-256、状態遷移をトランザクションで管理する。
- `ContractDocumentApiController` はテンプレート CRUD と契約単位の文書作成・送信・同期 API を提供する。CloudSign 資格情報が無い環境では送信状態をローカルで保持する feature-flag 実装とする。
- PDF は `app.upload.base-path/contracts/{contractId}` に原子的に保存し、CloudSign 締結ファイル取得処理を後続アダプターとして追加できるよう ID/パス列を保持する。
- 動的メニューの API プレフィックスは既存の契約権限境界に従い、テンプレート操作は API 側でも管理者チェックを行う。
