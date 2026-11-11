# Q1 Portal BP API H2 隔離証跡

## 変更

`ExpenseRequestFlowIntegrationTest`、`OneOnOneSurveyFlowIntegrationTest`、`PortalBpApiTest` にクラス専用の H2 URL を割り当てた。すべて `DB_CLOSE_DELAY=0` とし、Spring context が閉じるとデータベースも破棄される。3クラスは `@DirtiesContext(AFTER_CLASS)` で class 終了時に context / connection pool を閉じる。OneOnOne の `BEFORE_CLASS` は `AFTER_CLASS` に変更した。Portal の `/api/portal/bp/availabilities` に対する法人 1 の fixture、期待値、認可・業務ロジックは変更していない。

## 失敗時の観測と仮説の境界

基準 Fast 実行では `PortalBpApiTest.空き要員はreview後に有効化されreview前は内部候補に出ない` の初回登録が HTTP 403 となり、期待 HTTP 200 に失敗した。障害レポートは `C:\work\ses-snf-status-audit\verification-evidence\20261002-control-fast-03` に保存されている。同時に確認された fixture は Expense が `m_organization_unit.legal_entity_id=70001`、OneOnOne が `70002` を登録する。Portal は `TenantTestSecurity.ensureLegalEntity(..., 1L)` で法人 1 を用意し、Portal principal の法人解決は法人 ID が一意である場合だけ成功する。

これらのコードと失敗結果は共有 H2 の汚染を有力な隔離リスクとして示すが、この作業では当該 403 を同一 cached Spring context 上で再現していない。Expense→Portal の未変更構成の限定 pair は 16 件成功し、Portal の context 再起動時に初期 schema が適用される順序だった。さらに別の限定 pair では Surefire の実順序が Portal→OneOnOne であり、producer が先行していない。したがって、共有 H2 の法人重複を元障害の確定原因とは記載しない。`LegalEntityContextService` の fail-closed guard は変更していない。

## 修正後の実行

Java 21、Maven 3.9.6、`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds` で指定 3 クラスを実行した。POM の Surefire `random` 設定は維持し、実行順は各ログの `Running` 行で確認した。

| Seed | 実際のクラス順 | 結果 | ログ SHA-256 |
|---|---|---|---|
| `48992185070100`（制御 Fast と同じ seed） | Expense → Portal BP → OneOnOne | 23 tests, 0 failures, 0 errors, 0 skipped | `EB543E788629EF98A96FA9362E41DC9CADC65852A7102AB351FFC1B9FADDB34B` |
| `1` | OneOnOne → Portal BP → Expense | 23 tests, 0 failures, 0 errors, 0 skipped | `E7F5098B905F7314750AC731B833A9E17A66C799091B33E56311B7D8FDE2440B` |

修正後ログと run metadata は `target/verification-evidence/q1-portal-isolation/after-isolation-seed-48992185070100/` および `target/verification-evidence/q1-portal-isolation/after-isolation-seed-1/` にある。Seed 1 の Portal BP Surefire XML は `C1FEECF45005CD03DC010FCA1F5F90CCD51C7BCD8239D118FDD153435E876052`、テキストレポートは `FEA8A03CB09022CFADD4D72F89E02700962A9ED301C61C49E03E5C3630613C47`。基準 Portal 単独 run の保存済み証跡は `target/verification-evidence/q1-portal-isolation/baseline-portal-alone/` に残した。

変更ソースの SHA-256: `ExpenseRequestFlowIntegrationTest.java` `8989BE4B04788B1351AE9B184D8DF790D6ACA0BA8B831774B2D592A1F8E636A3`、`OneOnOneSurveyFlowIntegrationTest.java` `DFC589C371FF6F8D631BE2C0E5D21F93F8B19FC580C9732FFFFEB01E32EBBA8B`、`PortalBpApiTest.java` `046E9167B0AAEFF2D0833A9E003918AF268E17C9A2BB179186A799520DB48388`。

基準 Fast の失敗証跡 SHA-256: `maven.log` `650EA5DA5F545386868D487984DAB35DB6D1F0F87F6E5636326BC3C521A7AD19`、`result.json` `DC0E3C5E49C89DCB85E5F9CEEFDC17DE3B9B319BAC29F75D7890656FA287BAD7`、`failures.json` `EC5978C9C06B7F948B4993027FD8FA15B6A633802E39216C900AF90A4A69EFE4`。この修正後の 3 クラス結果は限定検証であり、Fast 全体の合格を意味しない。
