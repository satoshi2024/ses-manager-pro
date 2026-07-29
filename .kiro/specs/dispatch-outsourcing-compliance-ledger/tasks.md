# Implementation Plan — 派遣・準委任コンプライアンス台帳

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T060〜T065はL0〜L3の定向test・直接回帰、T066でL4全量を実行する。
> 法務受入gateと全量testの実行時点を混同しない。

- [ ] 0. G2公式様式field mapping
  - **Objective**: 0. G2公式様式field mapping を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 帳票ごとの法定項目→DB/画面/生成位置、保存期間、権限。
  - **Demo**: 厚生労働省公式URL/版/確認日付きmappingの社内責任者承認。外部社労士/法務承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. workplace/profile/finding/delivery DDL
  - **Objective**: F1. workplace/profile/finding/delivery DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V68/V1/H2/smoke、snapshot/permission。
  - **テスト要件**: FK/期間/PII scope。
  - **Demo**: F1. workplace/profile/finding/delivery DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. ComplianceRule分割/拡張
  - **Objective**: F2. ComplianceRule分割/拡張 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存4rule維持、期限/欠落/期間/指示経路rule、upsert。
  - **テスト要件**: code別境界、解消、重複なし。
  - **Demo**: 欠落profileを補完してfinding解消。

- [ ] A1. 契約compliance profile/UI
  - **Objective**: A1. 契約compliance profile/UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 契約形態別field、help、権限、差分。
  - **テスト要件**: validation/field mask/mobile。
  - **Demo**: 派遣/準委任で異なる入力項目。

- [ ] B1. 法定帳票/交付/archive
  - **Objective**: B1. 法定帳票/交付/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: generator/template version/delivery/受領。
  - **テスト要件**: golden file、版、hash、ACL。
  - **Demo**: 派遣元台帳等を生成し交付記録。

- [ ] B2. deadline/リスク運用
  - **Objective**: B2. deadline/リスク運用 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 90/60/30日、担当、ack/resolution/evidence。
  - **テスト要件**: 日付境界/notification scope/冪等。
  - **Demo**: 抵触日alert→対応→解消。

- [ ] M. 法務受入/回帰
  - **Objective**: M. 法務受入/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/既存compliance回帰。
  - **Demo**: 法務fixture3契約の台帳とfindingを照合。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
