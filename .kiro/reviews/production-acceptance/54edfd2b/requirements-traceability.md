# Requirements & Business Traceability Review (Commit: 54edfd2b)

---

## 1. 全仕様ディレクトリ一覧とステータス追溯

`.kiro/specs/` 配下の全 72 仕様ディレクトリについて、要件 (`requirements.md`)、タスク (`tasks.md`)、コード実装、および実機検証証跡を監査しました。

| 分類 | 仕様ディレクトリ / Spec | 要求P0/P1要件 | 実装ステータス | 受入判定 | 残存課題 / ブロッカー |
|---|---|---|---|---|---|
| **半完成本番化** | `payroll-management` (HFP-01) | freee OAuth2 / 給与明細DTO / 会社境界 | V102_4 実装済 | **BLOCKED** | 実 freee Sandbox 認証情報未提供 (`ACC-REQ-P0-001`) |
| **半完成本番化** | `contract-document-esign` (HFP-02) | 4工程直列ディスパッチ / 状態CAS | V103_1 実装済 | **BLOCKED** | CloudSign Sandbox & 本番運用承認未取得 (`ACC-REQ-P0-002`) |
| **半完成本番化** | `database-backup-recovery` (HFP-03) | 15分RPO / 二者承認 / PITR演習 | ops/backup 完備 | **PASS (Sandbox)** | 本番ターゲットトポロジ確定待ち (`ACC-OPS-P0-002`) |
| **プロダクト拡張** | `multi-company-tenant-isolation` (S01) | 共有DBマルチテナント化 | G0決定で顧客別独立DB採用 | **DEFERRED** | V59 永久欠番。安全にスコープ外化済。 |
| **プロダクト拡張** | `organization-management-accounting` (S02)| 組織マスタ / 所属履歴 / 部門別採算 | V60〜V62 実装済 | **PASS** | OrgScopeService により部門別統制確立。 |
| **プロダクト拡張** | `enterprise-identity-security` (S03) | OIDC / MFA / セッション管理 | V63〜V66.1 実装済 | **FAIL (P0脆弱性)**| OIDC 外部ID紐付けエンドポイントに管理者昇格欠陥 (`ACC-SEC-P0-001`) |
| **プロダクト拡張** | `legal-document-ledger-archive` (S04) | 法定帳票台帳 / 電帳法 / 改ざん防止 | V67 実装済 | **PASS** | SHA-256 三重ハッシュによる版管理完備。 |
| **プロダクト拡張** | `productivity-search-saved-view` (S05) | 横断検索 / ToDo / 保存ビュー | V68〜V69 実装済 | **PASS** | 全文インデックス・権限グループ制御完備。 |
| **プロダクト拡張** | `bp-company-master-procurement` (S06) | BP会社 / 反社チェック / 適格請求書 | V70〜V71 実装済 | **PASS** | 法人番号・インボイス登録番号検証完備。 |
| **プロダクト拡張** | `approval-workflow-internal-control` (S07)| 多段階承認 / 申請者ガード / SLA | V75〜V79.1 実装済| **PASS** | 申請者≠承認者ガード・排他制御完備。 |
| **プロダクト拡張** | `crm-contact-opportunity` (S08) | 商機フェーズ / 顧客複数担当者 | V73〜V74.2 実装済| **PASS** | NFKC正規化・商機ステージ遷移完備。 |
| **プロダクト拡張** | `order-acceptance-workflow` (S09) | 注文書・請書発行 / 月次検収 | V80〜V81 実装済 | **PASS** | 月次検収と請求書自動連携完備。 |
| **プロダクト拡張** | `dispatch-outsourcing-compliance` (S10) | 派遣元台帳 / 抵触日 / 待遇マスク | V84〜V85, V102 | **BLOCKED** | Phase B 人間・外部資格者レビュー証跡未取得 (`ACC-REQ-P1-001`) |
| **プロダクト拡張** | `attendance-leave-overtime` (S11) | 勤怠打刻 / 有休台帳 / 36協定 | V83, V91, V98 | **PASS** | 特別条項・過重労働アラート完備。 |
| **プロダクト拡張** | `staffing-capacity-planning` (S12) | 需給ヒートマップ / アサイン計画 | V103 実装済 | **PASS** | 300人規模キャパシティ検証済。 |
| **プロダクト拡張** | `external-customer-bp-portal` (S13) | 外部ポータル / トークン認証 | V104〜V104.4 実装 | **PASS** | MagicLink・IP制限・帳票共有完備。 |
| **プロダクト拡張** | `engineer-self-service-portal-v2` (S14)| 要員ポータル / 勤怠 / 経費精算 | V105〜V105.3 実装 | **PASS** | 要員専用動線 (`/my/**`) 完全分離。 |
| **プロダクト拡張** | `accounting-payment-integration` (S15) | 会計仕訳連携 / 入金消込 / 照合 | V106〜V106.2 実装 | **CONDITIONAL** | 本番 freee 接続ゲート待ち (`ACC-REQ-P1-002`) |
| **プロダクト拡張** | `jp-pint-digital-invoice` (S16) | デジタルインボイス JP PINT XML | V107〜V107.3 実装 | **BLOCKED** | Peppol サービスプロバイダー Sandbox 待ち (`ACC-REQ-P1-002`) |
| **プロダクト拡張** | `ai-feedback-learning` (S17) | 推薦フィードバック / PIIゲート | V108〜V108.3 実装 | **BLOCKED** | 外部 LLM プロバイダ DPA 締結待ち (`ACC-REQ-P1-003`) |

---

## 2. 5ロール権限マトリクス監査

| ロール | 許可される主要機能 | 遮断・保護される境界 (403 / 404 / Masking) | 監査判定 |
|---|---|---|---|
| **管理者 (ADMIN)** | 全画面・全API・システム設定・ユーザー管理・監査ログ・全承認 | ロックアウト防止のため MenuPermissionFilter をバイパス。自己削除・自己降格は禁止。 | **PASS** |
| **営業 (SALES)** | 顧客/案件/商機/提案/契約/見積作成、電子契約依頼送信、営業成績閲覧 | DataScope により非担当データは 404 秘匿。給与・労務コンプライアンス機微情報・システム設定は 403 遮断。 | **PASS** |
| **HR** | 要員マスタ・採用候補者・勤怠・有休・給与明細参照・労務台帳閲覧 | 注文書/検収・電子契約手動同期・売上/粗利/会計仕訳連携・システム設定は 403 遮断。 | **PASS** |
| **マネージャー (MANAGER)**| 部門配下要員の配置・需給計画・勤怠承認・休暇申請承認・自部門売上 | OrgScope により配下組織外データは 0件/404。給与管理・他部門商機・システム設定は 403 遮断。 | **PASS** |
| **要員 (ENGINEER)** | 本人専用マイページ (`/my/**`)、日次勤怠入力、経費精算、スキル更新 | 全ての管理系・業務系画面・API は 403 遮断。ログイン直後は自動で `/my/timesheet` へ強制転送。 | **PASS** |
