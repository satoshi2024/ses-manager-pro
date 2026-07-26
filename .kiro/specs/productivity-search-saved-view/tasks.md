# Implementation Plan — 横断検索・実ToDo・保存ビュー・一括操作

- [ ] F1. task/saved view基盤
  - **Objective**: F1. task/saved view基盤 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V63/V1/H2/smoke、schema registry、状態機械。
  - **テスト要件**: task遷移、view allowlist/owner/tenant。
  - **Demo**: task登録→担当変更→完了。

- [ ] A1. 横断検索
  - **Objective**: A1. 横断検索 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: provider、header UI、scope付き上限。
  - **テスト要件**: 種別別結果、A/B漏洩、timeout/2文字境界。
  - **Demo**: 顧客名から顧客/案件/契約/請求へ移動。

- [ ] A2. ToDo/通知分離
  - **Objective**: A2. ToDo/通知分離 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: todo tab、関連link、期限scheduler、通知→task。
  - **テスト要件**: 既読と完了の独立、通知冪等。
  - **Demo**: 通知を既読後もtask継続。

- [ ] B1. 保存ビュー/表示列
  - **Objective**: B1. 保存ビュー/表示列 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: engineer/customer/project/contract/invoiceから段階導入。
  - **テスト要件**: 個人/共有、無効field、default fallback。
  - **Demo**: 列/検索を保存し再login後復元。

- [ ] B2. 安全な一括操作
  - **Objective**: B2. 安全な一括操作 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: preview token、担当/状態/task、最大200。
  - **テスト要件**: 200/201、改ざん、partial、権限/状態競合。
  - **Demo**: 20要員へ担当営業変更preview→apply→結果CSV。

- [ ] M. 回帰/負荷
  - **Objective**: M. 回帰/負荷 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test、MySQLで検索p95、mobile keyboard。
  - **Demo**: 検索→saved view→bulk→taskの業務シナリオ。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
