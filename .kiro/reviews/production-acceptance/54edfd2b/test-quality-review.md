# Test Quality & Coverage Review (Commit: 54edfd2b)

---

## 1. 門禁別テスト品質評価

| テストスイート | 実行環境 | クラス数 / テスト数 | 実行品質・信頼性評価 |
|---|---|---|---|
| **Fast Suite** | H2 in-memory | 422 クラス (1200+ tests) | 実行速度約11分。回帰検出力は高いが、H2 と MySQL の方言差を吸収しきれない限界あり。 |
| **MySQL Suite** | MySQL 8.0 (Docker) | 39 クラス (61 tests) | Shard 1〜3 に厳密分割され、DDL 冪等性・排他制御・トランザクション分離を実証。 |
| **Performance**| ホスト JVM | 1 クラス (Staffing) | JaCoCo エージェントを排して純粋な wall-clock/heap を測定。p95=50ms 達成。 |
| **Browser Demo**| Headless Chrome | 3 クラス | Chrome CDP による実画面描画。Windows ファイルロックによる書き込みエラー発生 (`ACC-TEST-P1-001`)。 |
| **Backup Suite**| Docker Container | 13 スクリプト (465 tests)| RPO 60s, RTO 9s を実証。暗号鍵漏洩スキャン 0 件。 |

---

## 2. 検出されたテスト課題とリスク

### [ACC-TEST-P1-001] ブラウザテストが Git 管理下の証跡ディレクトリへ直接書き込みを行う
- `AttendanceBrowserMTest`, `StaffingBrowserMTest` が画像保存先として `.kiro/specs/**/evidence/browser-m/` を指定しているため、ファイルビューアやインデックスサービスが掴んでいる場合に `FileSystemException` でテストが失敗する。保存先は `target/` に統一すべきである。

### [ACC-TEST-P1-002] & [ACC-TEST-P1-003] JaCoCo 除外設定が広すぎる
- `pom.xml` において `com/ses/config/**` および `com/ses/mapper/**` がカバレッジ計測対象から除外されており、認証フィルタ (`SecurityConfig`, `MenuPermissionFilter`) やカスタム SQL アノテーションの網羅率が測定不能となっている。

### [ACC-TEST-P1-004] 共有 H2 DB と `<runOrder>alphabetical</runOrder>` 依存
- 約 200 の `@SpringBootTest` が単一のインメモリ H2 DB を共有しており、実行順序をアルファベット順に固定しないと先行テストの残存データによるフレーキーテストが発生する構造になっている。
