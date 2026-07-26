# Implementation Plan — 法定文書台帳・電子保存

- [ ] 0. G2法務確認と既存file inventory
  - **Objective**: 0. G2法務確認と既存file inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 文書種別、起算日、保存年、法的hold、既存path/参照元/件数/容量。
  - **Demo**: 公式URL/版/確認日付きprovisional mappingと社内コンプライアンス責任者の確認記録。外部専門家承認はM/本番gateへ記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. 文書DDLとDocumentService
  - **Objective**: F1. 文書DDLとDocumentService を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V62/V1/H2/smoke、version/link/access/disposal。
  - **テスト要件**: hash/version/冪等/hold/楽観ロック。
  - **Demo**: 受領PDFを登録しmetadataとhash表示。

- [ ] F2. Storage adapterとstream download
  - **Objective**: F2. Storage adapterとstream download を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: local/S3 interface、quarantine、orphan cleanup、fail-closed。
  - **テスト要件**: large file固定heap、scan失敗、DB失敗補償、A→B download拒否。
  - **Demo**: local/S3 fakeを設定切替して同じAPIで取得。

- [ ] A1. 台帳検索/詳細/version UI
  - **Objective**: A1. 台帳検索/詳細/version UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 日付/金額/相手先/種別、関連業務link、履歴。
  - **テスト要件**: filter組合せ、権限、mobile。
  - **Demo**: 3条件検索→文書→旧版→業務画面。

- [ ] B1. 既存帳票/CloudSign統合
  - **Objective**: B1. 既存帳票/CloudSign統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 見積/契約/作業報告/請求/署名済文書を冪等登録。
  - **テスト要件**: 再生成/再同期で重複なし、旧機能回帰。
  - **Demo**: 契約生成→署名同期→2文書版を確認。

- [ ] B2. 税務export/retention/disposal
  - **Objective**: B2. 税務export/retention/disposal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 非同期ZIP+manifest、候補→承認→廃棄、legal hold。
  - **テスト要件**: ZIP hash再計算、上限、approval、storage delete failure。
  - **Demo**: 検索結果exportと廃棄訓練。

- [ ] M. 移行/回帰/復元
  - **Objective**: M. 移行/回帰/復元 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: inventory件数/hash、全test、MySQL smoke、backup整合。
  - **Demo**: DB+storageを隔離環境へ復元し文書表示。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
