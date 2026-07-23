# Design — スキルシート匿名化＋多形式出力（FR-04）

親: ロードマップ FR-04。既存 `service/skillsheet/SkillSheetGenerator`（`generatePDF`/`generateExcel`）・`SkillSheetDtoMapper`・`Engineer.initialName` を拡張。新規テーブル原則不要。

## 1. 匿名化

- `SkillSheetGenerator.generatePDF(engineerId, options)` / `generateExcel(engineerId, options)` に `SkillSheetOptions`（`anonymize:boolean`, `template:enum`）を追加（後方互換で既定=非匿名/標準）。
- `SkillSheetDtoMapper` で DTO 化する際、`anonymize` 時に:
  - 氏名 → `Engineer.initialName`（空なら姓イニシャル等を生成）。
  - 生年月日 → 非表示（年齢または年代のみ）。連絡先・住所・顔写真 → 非表示。
  - 経歴の客先実名 → 業種/規模へ丸める（`EngineerCareer.clientIndustry` を優先表示）。
- 匿名化は DTO 段で行い、PDF/Excel 双方が同じ匿名DTOを描画する（二重実装しない）。

## 2. 様式

- `SkillSheetTemplate` enum（STANDARD/SIMPLE/CLIENT_A…）。様式ごとの項目順・表示有無を定義。
- テンプレ設定は `m_system_config`（キー `skillsheet.templates`）に保持し、追加可能に。
- 既存の OpenPDF（`Document`）/POI（Excel）描画を様式で分岐。CJKフォントは共通プロバイダ（A7-17 の集約があればそれ）を使用。

## 3. API・提案連携

- 既存のスキルシートDL経路（`/api/engineers/{id}/skill-sheet.pdf|xlsx`）に `?anonymize=true&template=SIMPLE` を追加、または `/api/engineers/{id}/skill-sheet/export`（POST, options）。
- 提案（`Proposal`）から起動時は生成物を `FileStorageService` に保存し `Proposal.skillSheetPath` に紐付け。ダウンロードは `FileScopeValidationService`（A8-04）を通す。

## 4. フロント・i18n・テスト
- 要員詳細/提案UIに「スキルシート出力」ダイアログ（匿名化ON/OFF・様式選択・PDF/Excel）。
- `messages*` 5ロケールにラベル追加。
- テスト: 匿名化時に氏名/生年月日/連絡先がPDF・Excelテキストから抽出されない、`initialName` が出る、様式で項目順が変わる。既存 `SkillSheetGenerator` テストは非匿名の後方互換を維持。
</content>
