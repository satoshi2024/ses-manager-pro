# SES Manager Pro テストガイドライン (TESTING_GUIDE)

本ドキュメントは、プロジェクトにおける結合テスト（Integration Test）の構造、実行方法、および今後の改修時における小範囲（部分的）なテストの実施方法についてまとめたものです。

## 1. テストアーキテクチャの概要

- **フレームワーク**: JUnit 5, Spring Boot Test (`@SpringBootTest`)
- **データベース**: H2 Database（インメモリDB）。テスト実行時のみ起動し、永続化されません。
- **カバレッジ計測**: JaCoCo
- **モック化**: `MockMvc` を用いたエンドポイントテスト、`@MockBean` を用いた外部API（メール送信等）の遮断。

## 2. テストの基本構造

すべての結合テストは、原則として `BaseIntegrationTest` クラスを継承して作成されています。

```java
@SpringBootTest(classes = SesManagerApplication.class)
@ActiveProfiles("test") // application-test.yml が読み込まれます
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {
    // 共通のセットアップ（メールサービスのモック化など）
}
```

### データのセットアップ (`@Sql`)
各テストクラスやメソッドの実行前に、クリーンな状態のデータを用意するために `@Sql` アノテーションを使用します。
```java
@Sql(scripts = {"/sql/engineer-schema-h2.sql", "/sql/api-coverage-data.sql"})
public class ExampleTest extends BaseIntegrationTest { ... }
```
※ データの依存関係によるテストのフレイキー（不安定）な失敗を防ぐため、テスト用SQLでは `INSERT` 時に必ず **明示的なID（例: `id = 1`）** を指定するようにしています。

---

## 3. テストの実行方法（全範囲 / 小範囲）

今後の機能改修時、全体をテストすると時間がかかる場合は、改修した機能に関連する小範囲でのテスト実行（単独テスト）を推奨します。

### 3.1. プロジェクト全体のフルテスト（フルテスト）
すべてのテストを実行し、全体のカバレッジを再計測します。リリース前などに必ず実行してください。
```bash
# Windows (PowerShell)
$env:SPRING_PROFILES_ACTIVE="test"; mvn clean test

# Mac / Linux
SPRING_PROFILES_ACTIVE=test mvn clean test
```

### 3.2. 特定のクラスのみを実行する（小範囲テスト）
特定のControllerやServiceを修正した場合、そのクラスに関連するテストのみを高速に実行します。
```bash
# 例: UserApiController のテストのみを実行する
mvn test -Dtest=UserApiControllerTest -Dspring.profiles.active=test

# 例: 複数の特定クラスを実行する
mvn test -Dtest=UserApiControllerTest,DashboardServiceImplTest -Dspring.profiles.active=test
```

### 3.3. 特定のメソッドのみを実行する（ピンポイントテスト）
特定のテストメソッドのみをデバッグしたい場合に使用します。
```bash
# 例: UserApiControllerTest の testUserApiController_GetById_NotFound メソッドのみを実行
mvn test -Dtest=UserApiControllerTest#testUserApiController_GetById_NotFound -Dspring.profiles.active=test
```

---

## 3.4. CIと同じ条件で実行する（「ローカルは通るのにCIで落ちる」対策）

**ローカルの `mvn test` とCI（`.github/workflows/ci.yml`）は、実行されるテストの集合が同じではありません。** push前は次のスクリプトを使ってください。CIと同じコマンド・同じ判定を行い、**自分の環境で実行されなかった（skipされた）テストを名前付きで表示します**。

```bash
# Mac / Linux
./scripts/verify-like-ci.sh

# Windows (PowerShell)
.\scripts\verify-like-ci.ps1
```

差分の原因は次の4つで、うち2つは `pom.xml` で固定済み、2つは実行環境側で用意する必要があります。

| # | 差分 | 内容 | 対処 |
|---|---|---|---|
| 1 | **Docker** | `Flyway*SmokeTest`（×5）/ `FlywayRepairRunbookTest` / `ConcurrentUpdateTest` の8クラスは `@Testcontainers(disabledWithoutDocker = true)`。Dockerが無いと**エラーではなくskip**され、緑のまま**マイグレーション検証だけが消えます**。実MySQLでマイグレーションを流すテストはこれだけなので、MySQL方言やマイグレーション衝突は**CIでしか出ません**。 | ローカルでもDockerを起動して実行する（CIはDocker必須・skipも失敗扱い） |
| 2 | **Node.js** | `JsSyntaxCheckTest` が `static/js` 配下を `node --check` する。ローカルはNodeが無ければskipだが、CI（`CI=true`）ではNodeが無いこと自体が失敗。 | Node.jsを入れる |
| 3 | **テスト実行順** | surefireの既定 `filesystem` はディレクトリ列挙順依存でWindowsとLinuxで変わる。**全テストが1つのH2（`jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`）を共有する**ため、先行テストがcommitしたデータの有無で結果が変わる。 | `pom.xml` で `<runOrder>alphabetical</runOrder>` に固定済み。**自分でinsertしていない行を前提にしないこと**（`contractId = 1L` のような他テスト由来のIDに依存しない） |
| 4 | **TZ / ロケール / 文字コード** | 未固定だとローカル（JST・日本語）とCI（UTC・英語）で日付境界やメッセージ解決が食い違う。`LocalDate.now()` を使うテストは35ファイルある。 | `pom.xml` のsurefire `argLine` で `Asia/Tokyo` / `ja_JP` / UTF-8 に固定済み（`application.yml` の本番設定と同じ） |

**CIの契約は「skipされたテストが1件も無いこと」です。** 環境を判定して `Assumptions.assumeTrue(...)` でskipするテストは、どの環境でも実行されない「見せかけの緑」になりがちなので追加しないでください（見積書PDFテストが実際にそうなっていました。同梱フォント `resources/fonts/ipaexg.ttf` があるためOSのフォント判定自体が不要でした）。

なお `clean` を付けるのは、`MigrationScriptIntegrityTest` が**クラスパス上の**マイグレーションを読むためです。`target/classes` に古いコピーが残っていると、常にfresh checkoutのCIだけが問題を検出します。CIのJDKは Temurin 21、`pom.xml` の `java.version=17` はバイトコードのターゲット指定です。

---

## 4. カバレッジレポートの確認

フルテストを実行すると、JaCoCoによるテストカバレッジレポートが自動生成されます。
ブラウザで以下のファイルを開くことで、行単位・分岐単位でのテスト網羅率を視覚的に確認できます。

- **レポートパス**: `target/site/jacoco/index.html`

## 5. 新しいテストを作成する際のガイドライン

1. **クラスの継承**: 必ず `BaseIntegrationTest` を継承してください。これによりSpringコンテキストのキャッシュが効き、テスト全体が高速化されます。
2. **認証のモック**: エンドポイントのテストで認証が必要な場合は、`@WithMockUser` を使用してください。
   ```java
   @Test
   @WithMockUser(username = "admin", roles = {"管理者"})
   void testAdminEndpoint() { ... }
   ```
3. **外部連携のモック**: AI連携やメール送信機能などを呼び出す場合は、実際にリクエストが飛ばないよう `application-test.yml` でモックプロバイダーに切り替えるか（例: `ai.provider=mock`）、`@MockBean` でモック化してください。
4. **データの独立性**: テストメソッドは実行順序に依存しないように作成してください。必要であればメソッドレベルで `@Sql` を付与し、状態をリセットしてください。
