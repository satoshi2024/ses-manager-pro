# R3_SCALE_300 シードデータ

300人規模のSES企業を想定した開発・結合テスト用の一括データです。

## 含まれるデータ

- ユーザー300名: 管理者2 / 営業25 / HR8 / マネージャー10 / 要員255
- 要員255名: 稼動中166 / Bench41 / 提案中31 / 退場予定17
- 顧客35社、案件100件、提案150件、契約252件、勤怠555件（日次8,415件）
- 請求66件、BP会社20社、候補者45名、CRM（リード/商機60件）、ToDo100件 ほか
- スキルシート取込6件 / 案件メール取込5件（要確認・確定済・却下・失敗・取込待ち）
- 現行moduleの代表データ: 承認route/申請、要員ポータル、変更申請・経費、外部連携、
  AI推薦、ライフサイクル、管理レポート、資格研修、資産・アカウント管理
- 日本人名・日本企業名・実在する金額規模で生成し、担当営業・契約・請求・勤怠の
  関連が一貫するようにしています。

V2初期マスタの要員3名（田中 太郎 / 山田 花子 / 伊藤 健太）も300ユーザーのうちの
「要員」としてカウントし、ログインアカウント・アカウント連携・BP所属・営業担当まで
紐付けた状態でシードします（追加分は要員252名）。

※ E2E実行によりDBへテスト生成レコードが加算される場合があります（顧客+3・案件+3等）。
これはシードSQL自体には含まれません。

## 自動適用（dev起動）

`application-dev.yml` が `db/migration-dev` をFlywayロケーションへ追加するため、
`mvn spring-boot:run`（devプロファイル）で空DBへ自動投入されます。

## 手動適用

```powershell
# スキーマは通常のマイグレーションで作成済みであること
mysql -uroot -p123456 ses_manager_db -e "source sql/seed/r3-scale-300/seed.sql"
```

## 再生成

```powershell
node scripts/seed-scale-300/generate-seed.mjs
```

生成先:
- `sql/seed/r3-scale-300/seed.sql`（手動適用用）
- `src/main/resources/db/migration-dev/V100__seed_r3_scale_300.sql`（V100以前の基礎データ）
- `src/main/resources/db/migration-dev/V134__seed_r3_current_modules.sql`（V101以降の現行module補助データ）
- `src/main/resources/db/migration-dev/V135__seed_r3_approval_route_extension.sql`（staffing.overallocation承認route）
