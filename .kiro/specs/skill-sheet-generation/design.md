# Design Document — スキルシート自動生成

## 1. 対象データ(読み取り専用、スキーマ変更なし)

- `t_engineer`: `name`, `nearestStation`等の表示可能な基本情報のみ抽出（社員番号・連絡先等は除外）。
- `t_engineer_skill` JOIN `m_skill_tag`: スキル名・習熟度(初級/中級/上級)・経験年数。
- `t_engineer_career`: `periodFrom`/`periodTo`/`clientIndustry`/`role`/`description`/`techStack`/`teamSize`（`projectName`は社外秘の可能性があるため出力対象外とし、`clientIndustry`のみ使用）。

## 2. バックエンド

### 2.1 新規
- `service/skillsheet/SkillSheetGenerator.java`: `generatePdf(Long engineerId): byte[]` / `generateExcel(Long engineerId): byte[]`。
  - PDF: `openpdf`(`com.lowagie.text.Document`等)でA4レイアウト。日本語フォントは`src/main/resources/fonts/`配下にライセンス確認済みフォント(例: IPAex)を同梱し`BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED)`で明示埋め込み。
  - Excel: `poi-ooxml`(`XSSFWorkbook`)で既存`ExportApiController`のシート生成パターンを踏襲。
- `controller/api/SkillSheetApiController`: `/api/engineers/{id}/skill-sheet`配下。

### 2.2 API一覧

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/engineers/{id}/skill-sheet.pdf` | PDF生成・ダウンロード(Content-Type: application/pdf) |
| GET | `/api/engineers/{id}/skill-sheet.xlsx` | Excel生成・ダウンロード |

サーバー側永続化は行わず、リクエスト都度生成してストリーム返却（`FileStorageService`とは独立）。

### 2.3 出力項目ホワイトリスト

```
基本情報: 氏名, 最寄駅, 稼働可能日(availableDate)
スキル: スキル名, 習熟度, 経験年数
職務経歴(期間降順): 期間, 業界(clientIndustry), 役割(role), 概要(description), 技術スタック(techStack), チーム規模(teamSize)
```
`t_engineer`の社員番号・連絡先・単価等は出力対象外。

## 3. 画面

- `templates/engineer/detail.html`: 「スキルシート生成」ドロップダウン(PDF/Excel)ボタンを追加。
- `static/js/modules/engineer.js`: `window.location.href`でダウンロードURLへ遷移する形で実装(既存のCSVエクスポートと同パターン)。

## 4. テスト

- `SkillSheetGeneratorTest`: 経歴0件時のフォールバック表示、日本語フォント埋め込みの出力バイト列が空でないことの確認、出力項目ホワイトリストに機微情報が含まれないことのアサーション。
- `SkillSheetApiControllerTest`(`@WebMvcTest`または`MockMvc`統合): エンドポイントのContent-Type検証。

## 5. リスク・確定口径(注意点)

- **日本語フォント埋め込み**: README.mdに記載済みの請求書PDF日本語フォント問題と同根。フォントファイルのライセンス（IPAフォントライセンスやMigu等のオープンライセンス）を必ず確認し、リポジトリに同梱してよいものを選定すること。商用フォントを許可なく同梱しない。
- **生成のたびにサーバー側でPDF/Excelを都度生成する設計のため、大量同時アクセス時のCPU負荷**に注意。管理画面からの個別ダウンロードが主用途であり、バッチ大量生成のユースケースは本specの対象外と明記する。
- **個人情報の意図しない混入**: テンプレート実装時に`t_engineer`エンティティを丸ごとテンプレートへ渡すと、意図せず機微カラムが出力される事故が起きやすい。DTO(`dto/skillsheet/SkillSheetDto`)を介して出力項目を明示的に絞ること。
