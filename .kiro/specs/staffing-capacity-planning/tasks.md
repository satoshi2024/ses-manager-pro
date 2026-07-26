# Implementation Plan — 要員配置・需給計画

- [ ] F1. position/allocation/scenario DDL
  - **Objective**: F1. position/allocation/scenario DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V70/V1/H2/smoke、状態/区間/競合service。
  - **テスト要件**: 50+50/60+50、期間、scenario isolation。
  - **Demo**: F1. position/allocation/scenario DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. proposal/contract/availability統合
  - **Objective**: F2. proposal/contract/availability統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: position link、actual allocation、renewal/leave/retirement。
  - **テスト要件**: 二重計上、更新済、退職、scope。
  - **Demo**: 提案→契約でposition充足。

- [ ] A1. position board/allocation timeline
  - **Objective**: A1. position board/allocation timeline を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: project/engineer画面、drag操作は失敗rollback。
  - **テスト要件**: API/CSRF/concurrency/mobile。
  - **Demo**: 兼務配置と過配賦拒否。

- [ ] B1. 需給heatmap/KPI
  - **Objective**: B1. 需給heatmap/KPI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: skill/role/location/月aggregate、bench cost。
  - **テスト要件**: FTE口径、全社=内訳、24か月。
  - **Demo**: Java需要不足をdrilldown。

- [ ] B2. scenario compare
  - **Objective**: B2. scenario compare を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: clone/仮配置/比較/共有、本データ非更新。
  - **テスト要件**: isolation/owner/scope。
  - **Demo**: 2scenarioの稼働率/粗利差。

- [ ] M. 回帰/性能
  - **Objective**: M. 回帰/性能 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL、代表データ量でp95/heap。
  - **Demo**: position作成→配置→提案→契約→需給更新。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
