# Inventory & DG-09 決定台帳 — `asset-account-license-lifecycle` (NF-09)

## 1. 現行システム資産・依存関係のインベントリ

| 領域 / エンティティ | 既存テーブル / サービス | 本機能（NF-09）との関係 | 留意事項 |
|---|---|---|---|
| **要員マスタ** | `t_engineer`, `EngineerService` | 貸与先 (`assignee_type = 'ENGINEER'`, `assignee_id = engineer.id`) | 退社ステータス (`status = '退社'`) との整合性確認 |
| **内部ユーザー** | `sys_user`, `SysUserService` | 貸与先 (`assignee_type = 'USER'`, `assignee_id = sys_user.id`) | ユーザー無効化 (`status = 0`) 時の資産・アカウント確認 |
| **法人・組織** | `m_organization_unit` | 資産所有法人 (`owner_company_id` = `legal_entity_id`), 費用負担組織 (`cost_center_id`) | 法人別資産集計、組織別ライセンス費用配賦 |
| **法定文書・証跡** | `t_document`, `DocumentService` | 受渡し・返却時の受領書・誓約書 (`handover_evidence_doc_id`, `return_evidence_doc_id`) | `DocumentLink` 構造に準拠、独自ファイル保存カラムを作らない |
| **退社ワークフロー** | `engineer-lifecycle-workflow` (NF-01) | 退社ゲートでの未返却資産・未失効アカウント blocker 検査 | `RESIGN_ASSET_RETURN` 阻害タスクの判定元データとして提供 |
| **内部統制・承認** | `ApprovalEngineService` | 資産未返却・アカウント未失効のまま退社させる場合の例外承認 | `RequestType = LIFECYCLE_EXCEPTION`, 二者承認 |
| **通知基盤** | `NotificationService`, `t_notification_outbox` | 返却期日接近・超過、棚卸し、失効未確認のアラート通知 | Deduplication Key による重複抑止 |

---

## 2. DG-09 決定台帳 (Decision Gate 09)

### 2.1 資産種別・所有法人・棚卸し頻度
- **資産種別 (`category`)**:
  - `PC`: ノートPC、デスクトップPC
  - `MONITOR`: 外部ディスプレイ
  - `SMARTPHONE`: 検証用・業務貸与スマートフォン
  - `TABLET`: タブレット端末
  - `SECURITY_KEY`: FIDO2/Passkey ハードウェアトークン
  - `OTHER`: ポケットWi-Fi、周辺機器、その他
- **所有法人 (`owner_company_id`)**:
  - `m_company` は存在しないため新設せず、`m_organization_unit.legal_entity_id` と同値の法人スコープ値を保持する。NULLの場合は「全社共通（自社保有）」として扱うが、空の許可集合を全件公開に使わない。
- **棚卸し頻度**:
  - 定期棚卸しは **半期に1回（年2回: 3月末・9月末基準日）** を標準とし、任意タイミングでの臨時棚卸しも実施可能とする。

### 2.2 外部MDM/IdP連携と正本性
- **正本（Source of Truth）**:
  - 物理資産の貸与状態およびアカウントの付与方針は **本システム（SES Manager Pro）が正本**。
  - 実際の認証認可・SSOセッションは **外部IdP（Microsoft Entra ID 等）が正本**。
  - 端末の遠隔制御・暗号化状態は **外部MDM（Microsoft Intune 等）が正本**。
- **連携範囲**:
  - 初版では外部システムとの直接同期は Mock Adapter / Webhook / 手動失効確認を基本とし、NF-05（`integration-hub-public-api`）整備後に自動プロビジョニング/デプロビジョニングへ拡張する。
  - 外部呼出しのタイムアウトやエラー時は失効完了とみなさない **Fail-Closed 原則** を徹底する。

### 2.3 紛失・事故インシデント運用
- 資産紛失発生時は、直ちにステータスを `LOST` に更新し、以下の項目を記録する:
  - 紛失発覚日時・場所・状況
  - 外部MDMを通じたリモートワイプ要求日時・ワイプ完了確認日時
  - 警察届出番号（遺失届受領番号）
  - 保険求償証拠書類（始末書、届出受理証明書スキャン等の `t_document` リンク）

---

## 3. 秘密非保存 (No Secrets Stored) 対象外インベントリ

本システムは以下の秘密情報を **一切保存・受領・ログ出力しない**:
- 外部アカウントのパスワード（平文・暗号化問わず）
- APIトークン / アクセストークン / リフレッシュトークン
- OAuth Client Secret / プライベートキー
- 2要素認証リカバリーコード / バックアップコード
- クレジットカード番号 / 銀行口座暗証番号

※ パスワード管理や認証情報配布は 1Password, Bitwarden 等の専用秘密情報管理ツールまたは SSO IdP のセルフサービスパスワードリセットに委ねる。
