# Implementation Plan — 企業認証・セキュリティ

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T014〜T019は`test-execution-policy-s03-s17.md`のL0〜L3で定向testと直接回帰を行い、
> 無条件の全量testを要求しない。T020でL4全量を1回実行する。共有security/schema変更等の昇格条件時だけ中間L4を行う。

- [x] 0. G1/脅威モデル/permission inventory
  - **Objective**: 0. G1/脅威モデル/permission inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: Entra test tenant/app登録、claim/group mapping、認証flow、action一覧、PII分類、2アカウントbreak-glass手順。
  - **Demo**: security review承認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [x] F1. identity/MFA/session/permission DDL
  - **Objective**: F1. identity/MFA/session/permission DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V63/V1/H2/smoke、暗号鍵version設計。
  - **テスト要件**: unique、recovery code hash、tenant分離。
  - **Demo**: F1. identity/MFA/session/permission DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [x] A1. OIDC login/provision/logout
  - **Objective**: A1. OIDC login/provision/logout を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: subject紐付け、招待、local fallback。
  - **テスト要件**: 正常、未知subject、email衝突、issuer不正、IdP timeout。
  - **Demo**: Entra test tenantでlogin/logout。

- [x] A2. MFA/session管理
  - **Objective**: A2. MFA/session管理 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: TOTP setup/recovery/session一覧/失効。
  - **テスト要件**: replay防止、code一回限り、role変更即失効。
  - **Demo**: 管理者MFA登録と端末失効。

- [x] B1. action permission移行
  - **Objective**: B1. action permission移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: legacy seed、AuthorizationService、主要高リスクAPIから適用。
  - **テスト要件**: role/group/action matrix、自己昇格拒否、field masking。
  - **Demo**: 財務担当は請求可・原価閲覧不可等。

- [x] B2. file quarantine/scan/fail-closed
  - **Objective**: B2. file quarantine/scan/fail-closed を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: FileScanner、quarantine、再scan、未知file拒否。
  - **テスト要件**: clean/infected/unavailable/既存file参照。
  - **Demo**: test fixtureが感染表示となり配布不可。

- [ ] M. セキュリティ回帰
  - **Objective**: M. セキュリティ回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test、OWASP依存スキャン相当、OIDC/MFA/browser、監査log秘密非出力。
  - **Demo**: login→権限変更→session失効→break-glass復旧訓練。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
