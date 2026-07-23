# Design — 偽装請負・多重派遣リスクチェック（FR-10）

親: ロードマップ FR-10。再利用: `multi-tier-bp-management`（BP階層）・`Contract`（`contractType`）・`WorkRecord`・`AuditLog`・`monthly-closing-checklist`・`SystemConfig`。新規テーブル原則不要（該当は導出＋監査記録）。

## 1. ルールエンジン `service/compliance/LaborComplianceService`

- `List<ComplianceFinding> check(Contract contract)`:
  - **段数超過**: BP階層（`multi-tier-bp` の layer 数）が `compliance.max-tier`（`m_system_config`）超過。
  - **偽装請負兆候**: `contractType ∈ {準委任, 請負}` かつ 指揮命令フラグ（`Contract` に軽い bool 列 `direct_command_flag` を追加 or 運用入力）が true。
  - **二重派遣兆候**: `contractType=派遣` の契約に再派遣構造（BP階層×派遣種別）。
  - **実態不整合**: 請負なのに時間精算（`settlementHoursMin/Max` や `WorkRecord` の時間管理）を行っている等。
- 各 finding は `code`/`severity`/`message`/`contractId`。ルールは有効/閾値を `m_system_config` で調整。
- ※必要な軽い列（`direct_command_flag` 等）は V4x で追加。導出できるものは列を足さない。

## 2. 呼び出し点・記録

- 契約登録/更新（`ContractServiceImpl`）で `check` を呼び、finding があれば画面に警告返却（ブロックせず）＋ `AuditLog` へ記録。
- `monthly-closing-checklist` に「コンプライアンス」項目を追加し、締め時に未確認 finding を提示（既存チェックリスト機構へ1項目追加）。
- 管理者画面 `GET /api/compliance/findings`（現在該当契約一覧）。

## 3. 画面・権限
- 契約フォームに警告バナー。管理者向けリスク一覧画面（`templates/compliance/list.html`）。ルール設定は system-config。
- 管理者/マネージャー限定。

## 4. テスト
- `LaborComplianceServiceTest`: 各ルールの該当/非該当（段数境界、契約種別×指揮命令、再派遣、実態不整合）、設定で無効化。
- 契約更新時に finding が AuditLog へ記録される。
</content>
