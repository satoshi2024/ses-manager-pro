# Requirements Document — スキルシート自動生成

## Introduction

現状、エンジニアのスキルシートは`FileKind.SKILL_SHEET`として営業担当者が手元で作成したPDF/Excel/Wordファイルをアップロードするだけの、完全にオペークな添付ファイルである。一方で`t_engineer_skill`（スキル名・習熟度・経験年数）と`t_engineer_career`（案件期間・案件名・業界・役割・技術スタック・チーム規模）という構造化データは既に登録・蓄積されている。この構造化データから顧客提示用のスキルシート（PDF/Excel）を自動生成できれば、営業担当者の作成工数を削減し、記載内容の表記ゆれも防げる。

`pom.xml`には`openpdf`（コメント「PDF生成 (スキルシート用)」）と`poi-ooxml`（「Excel生成 (帳票出力用)」）が既に導入されているが未使用であり、本specはこれらを活用する初のユースケースとなる。

### 確定済みの設計判断
- 既存の手動アップロード機能（`FileKind.SKILL_SHEET`）は廃止せず併存させる。自動生成はあくまで「たたき台生成」であり、生成後の手動編集・再アップロードは既存フローで対応する。
- 生成元データは`t_engineer`・`t_engineer_skill`・`t_engineer_career`のみを使用し、これら既存エンティティへの変更は行わない（読み取り専用）。
- PDF出力は`openpdf`、Excel出力は`poi-ooxml`を使用する（新規ライブラリの追加はしない）。

## Requirements

### Requirement 1: スキルシートのPDF自動生成

#### Acceptance Criteria
1. THE システム SHALL エンジニア詳細画面から「スキルシート生成(PDF)」操作を提供する。
2. WHEN 生成が実行された場合、THE システム SHALL 当該エンジニアの基本情報（氏名・保有スキル一覧・習熟度）と職務経歴（`t_engineer_career`を期間降順）をテンプレートレイアウトのPDFとして生成する。
3. THE 生成PDF SHALL 個人を特定しにくい表記（案件先の顧客名は`t_engineer_career.clientIndustry`のみ表示し、`t_customer`名は含めない）とする。これは複数顧客への同時提案時の情報漏洩を避けるため。
4. IF エンジニアに`t_engineer_career`が1件も登録されていない場合、THE システム SHALL 職務経歴欄を「登録なし」と表示し、生成自体は失敗させない。

### Requirement 2: スキルシートのExcel自動生成

#### Acceptance Criteria
1. THE システム SHALL PDF生成と同じ操作系から「Excel生成」を選択できる。
2. THE 生成Excel SHALL 既存の`ExportApiController`のExcel出力パターン（`poi-ooxml`利用）を踏襲したフォーマットとする。
3. THE Excel SHALL スキル一覧をシート内の表形式（スキル名/習熟度/経験年数の列）で出力する。

### Requirement 3: 生成結果のファイル管理

#### Acceptance Criteria
1. THE システム SHALL 生成したファイルをその場でダウンロードさせる（サーバー側への永続保存はしない、既存の`FileStorageService`とは独立した一時生成とする）。
2. THE システム SHALL 生成処理に失敗した場合（データ不整合等）、`ApiResult`のエラーレスポンス経由でエラーメッセージを返す。

## 踩坑点（実装時の注意）
- `openpdf`は日本語フォントを標準搭載していないため、**日本語が文字化けする/表示されない**という典型的な落とし穴がある。フォント埋め込み（例: IPAフォント等のライセンス確認済みフォントをリソースに同梱し`BaseFont.createFont()`で明示指定）が必須。README.mdに既に「請求書PDFが日本語フォント埋め込み問題でCN/KR非対応」という既知課題が記載されており、同じ問題を踏襲しないよう本specで先に解決するか、少なくとも同一の課題として認識しておくこと。
- `t_engineer_career`のデータ品質（自由記述の`description`/`techStack`）にばらつきがあるため、空文字列やnullに対するテンプレート側のレイアウト崩れ（空行・不揃いな表）に注意。
- 個人情報保護の観点で、生成PDFに社員番号や連絡先等の機微情報を含めないよう、出力項目のホワイトリストを明示的に設計すること（`t_engineer`の全カラムをそのまま出力しない）。
