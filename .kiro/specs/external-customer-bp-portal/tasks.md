# Implementation Plan — 顧客・BP外部ポータル

- [ ] 0. G3/G8と公開field inventory
  - **Objective**: 0. G3/G8と公開field inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: domain/規約/本人確認/permission×画面×field matrix、threat model。
  - **Demo**: G3 security boundary/field matrixの社内security・support承認。規約の外部法務承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. portal org/user/invite/consent DDL
  - **Objective**: F1. portal org/user/invite/consent DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V71/V1/H2/smoke、token/hash/session/permission。
  - **テスト要件**: token/reuse/expiry/email/tenant/停止。
  - **Demo**: F1. portal org/user/invite/consent DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. 専用security chain/DTO boundary
  - **Objective**: F2. 専用security chain/DTO boundary を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: principal/CSRF/cookie/rate limit/authorization/field allowlist。
  - **テスト要件**: A/B IDOR matrix、内部API拒否、秘密非ログ。
  - **Demo**: portal userが内部URLへ403。

- [ ] A1. 顧客portal
  - **Objective**: A1. 顧客portal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: documents/acceptance/invoice/支払予定/問い合わせ。
  - **テスト要件**: acceptance冪等/差戻し/file ACL。
  - **Demo**: 作業報告→顧客検収→内部請求可。

- [ ] A2. BP portal
  - **Objective**: A2. BP portal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: availability submission、発注確認、請求提出、支払参照、口座変更申請。
  - **テスト要件**: review/approval前非反映、BP組織scope。
  - **Demo**: BP提出→内部review→支払予定表示。

- [ ] B1. 管理/通知/利用規約
  - **Objective**: B1. 管理/通知/利用規約 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: user/invite/session/log、terms consent、email preference。
  - **テスト要件**: return URL、通知重複、terms更新。
  - **Demo**: 規約改定後再同意。

- [ ] M. penetration/回帰/運用
  - **Objective**: M. penetration/回帰/運用 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/IDOR/rate/mobile/scan。
  - **Demo**: 顧客A/B/BPの3組織受入と停止/復旧訓練。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
