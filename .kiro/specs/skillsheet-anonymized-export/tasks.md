# Tasks — スキルシート匿名化＋多形式出力（FR-04）

既存 `SkillSheetGenerator` 拡張。後方互換維持。

- [ ] A. 匿名化DTO＋オプション
  - **Objective**: `SkillSheetOptions`（anonymize/template）と匿名化DTOマッピング。
  - **実装ガイダンス**: design 1章。匿名化は `SkillSheetDtoMapper` 段で。氏名→initialName、生年月日/連絡先/写真を伏せ、客先→業種。
  - **テスト要件**: 匿名DTOに氏名/生年月日/連絡先が含まれない。
  - **Demo**: 匿名DTOをログ/テストで確認。

- [ ] B. PDF/Excel描画＋様式
  - **Objective**: 既存 `generatePDF`/`generateExcel` を options 対応、様式分岐。
  - **実装ガイダンス**: design 2章。CJKフォント共通化利用。`SkillSheetTemplate` enum。
  - **テスト要件**: 匿名PDF/Excelから氏名等が抽出されない、様式で項目順が変わる、非匿名の後方互換。
  - **Demo**: 匿名PDFとExcelを目視。

- [ ] C. API・提案連携・フロント
  - **Objective**: export API（options）、提案から出力→`Proposal.skillSheetPath` 保存、出力ダイアログ。
  - **実装ガイダンス**: design 3章。DL は `FileScopeValidationService`（A8-04）経由。
  - **テスト要件**: 権限外DL不可。
  - **Demo**: 提案から匿名スキルシート出力→保存→再DL。

- [ ] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 匿名化ONで氏名/生年月日/連絡先がPDF・Excelに出ない（`initialName` 表示）テストが緑。
- 様式選択で体裁が変わり、非匿名の既存出力は不変。
- 提案から出力した匿名スキルシートが `Proposal.skillSheetPath` に保存され、スコープ検証付きでDLできる。
</content>
