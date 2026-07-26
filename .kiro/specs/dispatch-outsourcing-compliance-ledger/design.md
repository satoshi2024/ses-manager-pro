# Design — 派遣・準委任コンプライアンス台帳

## 1. DDL（予約V68）

- `m_workplace(id, customer_id, name, address, organization_unit, phone)`。
- `t_contract_compliance_profile(contract_id, contract_type_detail, workplace_id, work_description,
  work_time/break/holidays/overtime, command_person_contact_id, client/responsible_person,
  dispatch_responsible_user_id, complaint_contact, treatment_scheme, limitation_date,
  training/safety/insurance fields, instruction_route, subcontract_allowed, acceptance_method,
  snapshot_json, version)`。
- `t_compliance_finding(id, contract_id, code, severity, status, detected_at, due_date,
  acknowledged_by/at, resolution_note, evidence_document_id)`。
- `t_document_delivery(document_id, recipient_contact_id, delivery_method, delivered_at, confirmed_at)`。

## 2. Rule engine

- 既存`LaborComplianceService`を`ComplianceRule`群へ分解するが、4既存code/挙動を維持。
- Rule inputはcontract/profile/BP tier/attendance/acceptance/document delivery。
- findingsは同じ`contract+code+condition fingerprint`でupsertし、解消時にstatus更新。毎回重複insertしない。
- 法的適用判定ではなく`MISSING_*`, `DEADLINE_*`, `RISK_*`を返す。

## 3. 帳票

- `ComplianceDocumentGenerator`と帳票種別別template version。
- 公式様式の項目対応表を`field-mapping.md`としてG2確認付きで保存。
- PDF/Excelどちらを採用するか帳票別に決め、生成物はarchive登録。

## 4. UI

- contract detailにcompliance profile/findings/documents。
- `/compliance`は現行リスク一覧を拡張し、期限/状態/担当/filterを追加。
- sensitive fieldはpermission mask。

## 5. テスト

rule境界、finding upsert/解消、帳票field mapping、deadline scheduler、profile snapshot、PII permission、
既存4rule回帰、法務fixture golden file。

