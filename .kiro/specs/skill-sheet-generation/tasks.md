# Implementation Plan — スキルシート自動生成

新規ファイルのみで完結し、既存エンティティ・他specとファイル交差なし。逐次構成（並行分割不要な規模）。

- [ ] 1. `SkillSheetDto`と出力項目ホワイトリストの実装
  - **Objective**: `t_engineer`/`t_engineer_skill`/`t_engineer_career`から出力用DTOへの変換を固める。
  - **実装ガイダンス**: design.md 2.3章のホワイトリストに厳密に従う。`dto/skillsheet/SkillSheetDto`を新規作成。
  - **テスト要件**: `SkillSheetDtoMapperTest`で機微情報(社員番号・連絡先・単価)が出力に含まれないことをアサーション。
  - **Demo**: DTOのJSONダンプを目視確認。

- [ ] 2. PDF生成(`openpdf`+日本語フォント埋め込み)
  - **Objective**: `SkillSheetGenerator.generatePdf()`を実装。
  - **実装ガイダンス**: design.md 5章の日本語フォント注意点に従い、ライセンス確認済みフォントを`src/main/resources/fonts/`に配置。
  - **テスト要件**: `SkillSheetGeneratorTest`で経歴0件時のフォールバック、日本語文字列が正しくエンコードされること(バイト列検証)。
  - **Demo**: 生成PDFを実際に開き、日本語が文字化けしないことを目視確認。

- [ ] 3. Excel生成(`poi-ooxml`)
  - **Objective**: `SkillSheetGenerator.generateExcel()`を実装。
  - **実装ガイダンス**: 既存`ExportApiController`のシート生成パターンを踏襲。
  - **テスト要件**: 生成Excelをapache-poiで再読込し、スキル一覧の行数・列内容を検証。
  - **Demo**: 生成Excelを実際に開いて確認。

- [ ] 4. API・画面統合
  - **Objective**: `SkillSheetApiController`と`engineer/detail.html`のボタンを実装。
  - **実装ガイダンス**: design.md 2.2/3章。
  - **テスト要件**: `SkillSheetApiControllerTest`でContent-Type検証。
  - **Demo**: ブラウザでエンジニア詳細画面から実際にPDF/Excelをダウンロードして確認。
