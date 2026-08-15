# R23-P1-01 repair delta（CHANGES_REQUIRED対応・migration V102_3確定）

- **状態**: `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-14）
- **根拠**: R10 2026-08-14独立照合（origin/main `ec2c5cee`）CHANGES_REQUIRED判定。P0 6件・P1 7件・migration・docs修正。
- **migration番号確定（R10指示・受理前にV102_3を作成しない）**:
  - **S10 repair = V102_3**（`V102_3__compliance_gate_dynamic_policy_repair.sql`）
  - S12 reservation = V103維持
  - version順序: V102 < V102_1 < V102_2 < **V102_3** < V103
- **published/immutable**: V102・V102_1・V102_2は変更禁止。追加DDLはV102_3のみ。

## 1. Docs修正の正式契約（R10指示5件）

### 1.1 GATE-T066-HISTORY（TRACKED P2・分離の明文化）

- `GATE-T066-HISTORY`は**TRACKED P2 / production release gate**。S10 PASS・S12開始を阻害しない。
- 対象history fieldを必要とする**production帳票の交付だけが禁止**。
- 旧記載「証跡1〜5全部がM PASS必須」から分離: 証跡5（T066-HISTORY可否）はM PASSの前提ではなく、**production release gateとして独立追跡**。
- design.md §3.1（既修正）と整合。履歴文書（external-review-20260812.md等）は書き換えない。

### 1.2 FM-C-28/一次source判断（HISTORY blockerとしない）

- 今回freezeするmappingへ影響する場合は、**mapping version・実在Review・actor approvalの中で解決**する。
- `mapping-amendment-proposal-fm-c-28.md`の「発注者判断待ち（証跡5）」はHISTORY blockerとして扱わない。
- 証跡2のmapping_hashはFM-C-28版管理判断後の固定値を記録（既存様式のまま）。

### 1.3 registration identifier（全type固定必須化しない）

- registration identifierは**全reviewer typeへ固定必須化しない**。
- dynamic frozen policyとofficial verification methodに従う（§3.3 result別・kind別nullability・§3.8 dynamic master）。
- 実装: verificationのregistration identifierはoptional入力（credential_required判定はfreeze済みsnapshot）。

### 1.4 g2-gate-evidence-templates.md改訂（実施済み）

- 直接SQL例を廃止し、UI/API/domain event経由へ改訂（`g2-gate-evidence-templates.md`・2026-08-14改訂版）。
- SQLは記録確認クエリとしてのみ記載。

### 1.5 新規テンプレート（実施済み）

- `g2-gate-evidence-templates-r23.md`新規作成: 証跡3様式（IDENTITY/AUTHORSHIP＋条件付きQUALIFICATION/ACTIVE_STATUS）・official source/manual check記録・Phase A/B screenshot/viewport/role/hash manifest・exact evidence document/version/hash/CLEAN記録。

## 2. P0対応設計（受理後の実装スコープ）

| # | P0 | 対応 |
|---|---|---|
| 1 | 6 tabs placeholder | Assignment/Internal Approval/External Review/本人・資格・作成者確認/ACTIVE/Event Historyの実装＋Policy tabのgroup/type設定・freeze操作（UI・JS・API既存の完全結線） |
| 2 | SecurityConfig matcher順序 | `/api/compliance-gate/approvals`等を**先に**マッチさせ、`/api/compliance-gate/**`（管理者）を後に。HR/マネージャー本人approval到達可能化＋実SecurityFilterChainテスト |
| 3 | dynamic policy契約 | V102_3で追加: `m_compliance_review_verification_source`（source/master）・`m_compliance_review_verification_method`（method/master）・`m_compliance_reviewer_qualification`（subject×資格association）・`t_compliance_mapping_requirement_type`へ `qualification_verification_required`/`active_status_verification_required`（TINYINT NULL=UNCONFIGURED・§8）・`verification_source_id`/`verification_method_id`・`max_age_days`・`effective_from`/`effective_to`。API/UIで設定・freeze・snapshot化。credential_required_snapshotからの流用廃止 |
| 4 | subject create path | `POST /api/compliance-gate/subjects`（subject作成・fingerprint計算）＋資格association API/UI |
| 5 | exact CLEAN evidence | `GET /api/compliance-gate/evidence-picker`（document/version/title/originalName/SHA-256/scan/createdAt allow-list）＋picker UI。internal approvalもexact version/hash/CLEAN/file scopeをsnapshot |
| 6 | verification/adoption binding | 全verificationの同一tenant・submitted review・subject・reviewer type・mapping/policy/hash・review chain・exact evidence強制（service＋DB制約）。cross-chain混在拒否・maxAge未設定fail-closed・evidence NULL fail-closed |

## 3. P1対応設計

| # | P1 | 対応 |
|---|---|---|
| 1 | typed DTO | assignment/approval responseもtyped allow-list DTO化（`ComplianceAssignmentDto`・`ComplianceApprovalEventDto`） |
| 2 | tenant/DataScope境界 | tenant='default'固定・裸selectByIdを除去し、tenant/workplace/DataScopeをSQL境界で適用（`DataScopeService`連携） |
| 3 | idempotency | 同一key＋同hash=200 replay・異hash=409（canonical request hashの保存と比較） |
| 4 | subject immutable | subject masterのUPDATE/DELETE拒否（service＋DB trigger） |
| 5 | 並行adoption一意化 | 同一SUBMITTED chainの並行first adoptionをDB UNIQUEで一意化（`UNIQUE(tenant_id, submitted_review_event_id)`を初回adoption行へ） |
| 6 | watermark preview | contract画面へwatermark preview導線（archive/delivery 0・watermarkあり） |
| 7 | Phase B manifest API | 完全hash/IDを取得できるallow-list API（`GET /api/compliance-gate/manifest/{mappingId}`等） |

## 4. 修正後必須回帰（受理後）

- 管理者/HR/マネージャー/営業/要員の実SecurityFilterChainテスト
- HR/マネージャー本人assignment→approval成功
- 9 tabs全実操作可能（placeholder 0）
- dynamic type/source/method/policy作成→freeze
- subject＋資格association作成
- exact CLEAN evidence picker
- SUBMITTED→verification→adoption→ACTIVEのbrowser/API E2E
- cross-tenant/cross-subject/cross-chain/cross-evidence拒否
- qualification/ACTIVE_STATUS frozen flag true/false/NULL
- idempotency 200 replay/409 conflict
- previewはarchive/delivery 0・watermarkあり
- MySQL fresh/upgrade/forward-repair・skip 0
- final HeadでL1〜L4/CI

## 5. 停止条件（変更なし）

- R10が本repair deltaを受理するまでV102_3を作成しない。
- 修正PR merge前に固定HeadをR10へ独立Review提出。
- R10がimplementation completenessを受理した後にのみ正式な人間証跡取得へ進む。
