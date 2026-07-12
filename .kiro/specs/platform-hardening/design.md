# Design Document — 横断的品質強化(P8)

## 1. 入力バリデーション

- `pom.xml` に `spring-boot-starter-validation` を追加(未依存の場合)。
- エンティティに制約注釈を直接付与(この規模では専用リクエスト DTO を作らず、既存の「エンティティ直受け」構造を維持):

```java
// Engineer
@NotBlank(message = "氏名は必須です") private String fullName;
@PositiveOrZero(message = "希望単価は0以上で入力してください") private BigDecimal expectedUnitPrice;
// Project: @NotBlank projectName / @NotNull customerId、単価・日付の相関は @AssertTrue メソッドで
@AssertTrue(message = "終了予定日は開始予定日以降を指定してください")
private boolean isDateRangeValid() { return startDate == null || endDate == null || !endDate.isBefore(startDate); }
```

- 各 ApiController の `@RequestBody` に `@Valid` を付与。
- `GlobalExceptionHandler` に `MethodArgumentNotValidException` ハンドラを追加(既に有れば流用):
  最初のフィールドエラーの `defaultMessage` を `ApiResult(400, message)` で返す。
- `@AssertTrue` の boolean getter が MyBatis-Plus に列と誤認されないよう `@TableField(exist = false)` は不要
  (メソッドのみでフィールドを持たなければ対象外)だが、命名は `isXxxValid` に統一し JSON へ出ないよう `@JsonIgnore` を付ける。

## 2. ファイルアップロード

- 設定: `app.upload.base-path: ./uploads`(`@ConfigurationProperties(prefix="app.upload")` → `config/UploadProperties`)。
- `service/FileStorageService`:

```java
public interface FileStorageService {
    StoredFile store(MultipartFile file, FileKind kind);  // kind: SKILL_SHEET(pdf/xlsx/docx,10MB) / PHOTO(png/jpg,2MB)
    Resource load(String storedName);                     // パストラバーサル検証必須(base-path 配下チェック)
}
// StoredFile: storedName(UUID+拡張子), originalName, size
```

- 検証: 拡張子ホワイトリスト + `MultipartFile.getSize()` + Content-Type 前方一致。違反は `BusinessException`。
- `controller/api/FileApiController`:
  - `POST /api/files?kind=SKILL_SHEET`(multipart)→ `ApiResult<StoredFile>`
  - `GET /api/files/{storedName}` → `ResponseEntity<Resource>`(認証必須は既存 SecurityConfig の `anyRequest().authenticated()` に従う)
- 要員への紐付け: `photo_url` / (提案の)`skill_sheet_path` に storedName を保存。要員詳細画面にアップロード UI + プレビュー/ダウンロードリンク。
- `application.yml` に `spring.servlet.multipart.max-file-size: 10MB` / `max-request-size: 12MB`。
- `m_menu` 追加不要(`/api/files` は全ロール共通機能のため `MenuPermissionFilter` 対象外パスとして扱う — フィルタは m_menu に一致しないパスを許可する既存挙動を利用)。

## 3. CSV 入出力

- ライブラリ追加なしで実装(フォーマットが単純なため手書き。引用符・カンマ・改行のエスケープを `CsvUtils` に集約):
  `common/util/CsvUtils.java` — `writeLine(StringBuilder, String...)` / `parse(InputStream)`(RFC4180 最小実装)。
- エクスポート: `GET /api/engineers/export-csv` / `GET /api/projects/export-csv`。UTF-8 BOM(`﻿`)先頭付与。
- インポート: `POST /api/engineers/import-csv`(multipart)→ `CsvImportResultDto {successCount, errors: [{line, message}]}`。
  1行ずつ Bean Validation(`Validator#validate`)にかけ、違反行はスキップして続行。`@Transactional` は行単位ではなく**全体なし**(部分成功を許す仕様のため)。
- 画面: 要員一覧に「CSV出力」「CSVインポート」ボタン + 結果モーダル(失敗行の一覧表示)。

## 4. 監査情報の自動設定

- `config/AuditMetaObjectHandler implements MetaObjectHandler`(MyBatis-Plus 標準機構):

```java
@Override public void insertFill(MetaObject metaObject) {
    strictInsertFill(metaObject, "createdBy", Long.class, SecurityUtils.currentUserId());
}
```

- 各エンティティの `createdBy` に `@TableField(fill = FieldFill.INSERT)` を付与し、
  既存コントローラーの手動セット(あれば)を削除。
- 操作ログ: `config/ApiAuditFilter extends OncePerRequestFilter`(`/api/**` の POST/PUT/DELETE のみ)。
  `log.info("操作ログ user={} {} {} status={}", username, method, uri, response.getStatus())`。
  `SecurityConfig` の `addFilterAfter(MenuPermissionFilter)` の後に配置。

## 5. メール送信

- `pom.xml`: `spring-boot-starter-mail`。
- `application.yml`:

```yaml
spring.mail:
  host: ${SMTP_HOST:}
  port: ${SMTP_PORT:587}
  username: ${SMTP_USERNAME:}
  password: ${SMTP_PASSWORD:}
app.mail.from: ${MAIL_FROM:noreply@example.com}
```

- `service/MailService` + impl:

```java
@Async
public void sendWithTemplate(Long templateId, Map<String, String> params, String to);
// {{変数}} 置換は正規表現 Pattern.compile("\\{\\{(\\w+)\\}\\}") で subject/body 両方に適用
// spring.mail.host が空 → ドライラン: log.info でメール内容を出力して正常終了
```

- `SesManagerApplication` に `@EnableAsync`。送信失敗は catch して `log.error` + (P3 あれば)`publish("MAIL_FAILED", ...)`。
- 適用箇所: 提案メールモーダル(テンプレ選択 → 変数プレビュー → 送信)。`POST /api/proposals/{id}/send-mail`(body: templateId, to)。
  変数セット: `engineerName` / `projectName` / `unitPrice` / `customerName` など提案から解決。

## 6. セキュリティ強化

### 6.1 CSRF
- `SecurityConfig`: `csrf().ignoringRequestMatchers("/api/**")` を削除し
  `csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))` に変更。
- `common.js` の `$.ajaxSetup` に `beforeSend` を追加: cookie `XSRF-TOKEN` を読み `X-XSRF-TOKEN` ヘッダーへ。
- 全画面の AJAX が jQuery 経由であることを grep で確認(`fetch` 使用箇所があれば同様に対応)。

### 6.2 アカウントロック(`sql/007_user_security_columns.sql`)

```sql
ALTER TABLE sys_user
  ADD COLUMN failed_count INT DEFAULT 0 COMMENT 'ログイン失敗回数',
  ADD COLUMN locked_until DATETIME NULL COMMENT 'ロック解除日時';
```

- `config/LoginFailureHandler implements AuthenticationFailureHandler`: 失敗で failed_count++、5回で locked_until=now+30分。
- `CustomUserDetailsService`: locked_until 未来なら `LockedException`(ログイン画面に「アカウントがロックされています」)。
- 成功時(`AuthenticationSuccessHandler` or `CustomUserDetailsService` 後段)で failed_count リセット。
- H2 テストスキーマにも列追加。

### 6.3 パスワードポリシー
- `UserApiController` の作成/パスワード変更時: `password.length() >= 8 && 英字含む && 数字含む` を検証、違反は `BusinessException("パスワードは8文字以上で英字と数字を含めてください")`。

### 6.4 接続情報の環境変数化

```yaml
spring.datasource:
  url: ${DB_URL:jdbc:mysql://localhost:3306/ses_manager_db?...}
  username: ${DB_USERNAME:root}
  password: ${DB_PASSWORD:}
```

## 7. システム設定

- `entity/SystemConfig`(`@TableName("m_system_config")`、PK=config_key 文字列 → `@TableId(type = IdType.INPUT)`)。
- `service/SystemConfigService`:

```java
public interface SystemConfigService {
    String getString(String key, String def);
    int getInt(String key, int def);
    BigDecimal getDecimal(String key, BigDecimal def);
    void put(String key, String value, String description);
    Map<String, SystemConfig> all();
}
// impl: ConcurrentHashMap キャッシュ、put/画面保存時に evict。
```

- 定義キー(`002` または `007` にシード): `notice.contract-end-days=30` / `notice.proposal-stale-days=7` /
  `notice.bench-warn-days=30` / `billing.tax-rate=0.10` / `company.name=` / `company.bank-info=`。
- 呼び替え: `NotificationGenerateService`(P3)・`InvoiceService`(P5)・請求書印刷ビューの定数を本サービス経由に変更。
- 画面: `templates/system-config/list.html` + `SystemConfigPageController(/system-config)` + `SystemConfigApiController(/api/system-configs)`。
  `SecurityConfig` で `/system-config/**` `/api/system-configs/**` を `hasRole("管理者")`(user モジュールと同じ二重防御)。
  `m_menu` に `system-config` を追加(管理者のみ)。

## 8. テスト拡充

- 方針: コントローラー単体は `@WebMvcTest`(サービスモック)、業務ロジックは H2 統合。
- 追加対象と代表ケース:
  - `EngineerApiControllerTest`: 一覧フィルタ、登録時 `@Valid` 違反(氏名空)で 400 系 ApiResult。
  - `ProjectApiControllerTest`: 日付相関違反。
  - `CustomerApiControllerTest` / `ProposalApiControllerTest` / `ContractApiControllerTest` / `UserApiControllerTest`(自己削除ガード・重複 username・パスワードポリシー)。
- `MockMvc` の CSRF: `spring-security-test` の `csrf()` ポストプロセッサを共通ヘルパー化(6.1 移行後は API テストにも必要)。
