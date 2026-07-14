# 国際化(i18n)+ UI言語切替 実装計画

## 背景と目的

UIは日本語を主言語としつつ、ヘッダー(およびログイン画面)から言語を切り替えられるようにする。対象言語は **日本語(デフォルト)/ 英語 / 簡体字中国語 / 韓国語** の4言語。

**追加ライブラリは不要**。Spring Boot 標準の `MessageSource` 自動構成と Thymeleaf の `#{...}` 式で実現できる(pom.xml の既存依存で完結)。本当の作業は依存追加ではなく、以下の4点である:

1. 現状存在しない Locale 切替機構(`LocaleResolver` / `LocaleChangeInterceptor`)と言語切替UIの追加
2. テンプレート約640箇所・JS約580箇所・Java層約334箇所にハードコードされた日本語文言の外部化
3. バンドラー無しのフロントエンドへの JS 辞書機構の導入
4. DBに日本語のまま格納されている列挙値(ステータス/ロール等)の**表示層のみ**の翻訳マッピング

### スコープ原則: 「利用者の目に見えるもの」だけを翻訳する

- **対象**: 画面の静的文言(見出し/ボタン/表ヘッダ等)、JSのトースト/SweetAlert確認ダイアログ、バックエンドが返しユーザーに表示されるエラー・成功メッセージ、ステータス・ロール等の列挙値の表示ラベル
- **対象外(日本語のまま維持)**: ログ、コードコメント、DB格納値そのもの、V2シードのメールテンプレート、コミットメッセージ、`invoice/print.html` + PDF出力(日本法務書類のため。中韓フォント埋込課題もあり遺留事項とする)
- CSV/Excel出力: ヘッダは現在ロケールに追従、データ列の列挙値は元のDB日本語値のまま(再取込・突合の一貫性のため)

### 現状の事実

- `messages_ja.properties`(411キー、unicodeエスケープ)は存在するが、`#{...}` を使うテンプレートは4ファイル21箇所のみ(sidebar/login/base/dashboard)
- `application.yml` の `spring.messages.basename: messages_ja` は非標準(要修正)
- `LocaleResolver` / `LocaleChangeInterceptor` / カスタム `MessageSource` は一切存在しない
- Thymeleaf 3.1(Spring Boot 3.3)ではテンプレート内で `#request` が使えない → 言語切替リンクの現在URL保持は Controller 層(既存 `GlobalControllerAdvice`)から注入する
- `/js/**` は既に permitAll → JS辞書エンドポイントを `/js/i18n.js` に置けば SecurityConfig 修正ゼロでログイン前でも取得可能
- `SecurityConfig` に `hasRole("管理者")` があり、ロールの日本語値は認可判定に使われている → **DB値は絶対に変更しない。表示層マッピングのみ**

## アーキテクチャ決定

### 決定1: Locale保持 = CookieLocaleResolver(`config/WebConfig.java` に追加)

ログイン前後・セッション切れ・複数タブでも言語選択を維持するため Cookie 方式を採用。

```java
@Bean
public LocaleResolver localeResolver() {          // Bean名は必ず localeResolver
    CookieLocaleResolver resolver = new CookieLocaleResolver("SES_LOCALE");
    resolver.setDefaultLocale(Locale.JAPANESE);
    resolver.setCookieMaxAge(Duration.ofDays(365));
    resolver.setCookiePath("/");                  // ★全パスで有効に(既定はコンテキストパス依存)
    return resolver;
}
@Bean
public LocaleChangeInterceptor localeChangeInterceptor() {
    LocaleChangeInterceptor i = new LocaleChangeInterceptor();
    i.setParamName("lang");                       // ?lang=ja|en|zh-CN|ko
    i.setIgnoreInvalidLocale(true);               // 不正値で500にしない
    return i;
}
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(localeChangeInterceptor())
            .excludePathPatterns("/js/**", "/css/**", "/lib/**", "/img/**", "/api/**");
}
```

- 除外パスは「lang パラメータによる書き換え」の除外であり、Locale 解決自体は全リクエストで Cookie から行われる → API のエラーメッセージも正しくローカライズされる
- `base.html` / `login.html` の `<html lang="ja">` → `th:lang="${#locale.toLanguageTag()}"`
- Google Fonts に Noto Sans SC / Noto Sans KR を追加(中韓のフォントフォールバック対策)
- **★リスク(ロケールタグとファイル名の対応)**: `?lang=zh-CN`(ハイフン・国コード大文字)は Spring Framework 6.1 で `Locale(zh, CN)` に解決され、MessageSource は `messages_zh_CN.properties`(**アンダースコア・CN大文字**)を探す。ファイル名の綴りが1文字でもズレると無言でデフォルト(日本語)にフォールバックするため、4ファイル名を厳密に `messages_en` / `messages_zh_CN` / `messages_ko` とし、この往復を `I18nLocaleSwitchTest` の zh-CN ケースで必ず検証する
- **★リスク(テストスライス互換)**: `WebConfig` に追加する `localeResolver` / interceptor は外部依存を持たないため `@WebMvcTest` でも安全だが、既存の H2 系スライステスト(`MobileResponsiveLayoutTest` 等)が Accept-Language 依存で動いていないことを確認する

### 決定2: 言語切替UI

- `header.html` の `div.header-tools` 内、テーマ切替ボタンと通知ベルの間に bi-globe2 アイコンのドロップダウン(日本語 / English / 简体中文 / 한국어、現在言語を `active` 強調)
- `login.html` はカード下部に同構造のリンク行
- 切替は `<a th:href="${langSwitchBase} + 'en'">` による GET 全画面リロード(JS不要)
- **★CSRFに関する訂正**: 現行 `SecurityConfig` は `CookieCsrfTokenRepository`(XSRF-TOKEN Cookie → X-XSRF-TOKEN ヘッダー)で **全リクエストにCSRFが有効**(かつては `/api/**` を除外していたが現在は除外なし)。言語切替と `/js/i18n.js` は**GETのため CSRF 検証対象外**で問題なし。ただし言語切替を将来 POST 化してはならない(XSRFヘッダーが必要になり、未認証のログイン画面で破綻する)
- `langSwitchBase`(現在URI+クエリ、既存 lang を除去し `lang=` で終わる文字列)は `GlobalControllerAdvice`(スコープは `com.ses.controller.page` のみ=ページ限定で API を汚さない)の `@ModelAttribute` で全画面へ供給

### 決定3: プロパティファイル構成

- `messages.properties` = 日本語全量(デフォルト兼フォールバック)。既存 `messages_ja.properties` の内容を移して**削除**(ja とデフォルトの二重管理を回避。locale=ja は自然にデフォルトへフォールバック)
- 新規 `messages_en.properties` / `messages_zh_CN.properties` / `messages_ko.properties`。キー集合はデフォルトと完全一致(テストで強制)
- 全ファイル **UTF-8 素文**(既存の unicode エスケープは一括逆変換)
- `application.yml`:

```yaml
spring:
  messages:
    basename: messages
    encoding: UTF-8
    fallback-to-system-locale: false   # 必須: サーバーOSロケールへ落ちるのを防ぐ
    use-code-as-default-message: true  # キー欠落でも画面が死なない(欠落はテストで検出)
```

- キー命名規約(既存 `モジュール.名詞` 流儀を踏襲):
  - `common.action.*` 保存/更新/削除/検索/クリア/編集/詳細/キャンセル等の横断ボタン(重複文言を大幅集約)
  - `common.label.*` / `common.msg.*` / `common.confirm.*` 共通ラベル・トースト・確認ダイアログ
  - `<module>.list.*` / `<module>.form.*` / `<module>.msg.*` 各業務画面
  - `enum.<group>.<code>` 列挙値表示(決定5)
  - `error.*` 例外メッセージ / `validation.*` Bean Validation
- 規模: 既存411キー + 新規650〜800キー ≒ 各言語ファイル約1,100キー
- MessageFormat 注意: `{0}` を含む英語訳の単引用符は `''` にエスケープ(`doesn''t`)

### 決定4: フロントJS i18n = 辞書エンドポイント + `SES.i18n`

- 新規 `controller/page/I18nJsController.java`: `GET /js/i18n.js?lang=xx&v=版本` が
  `window.SES_LANG / SES_MESSAGES / SES_ENUMS` を `application/javascript` で配信。
  permitAll 済みの `/js/**` 配下なので SecurityConfig 修正ゼロ、未ログインでも取得可。Cache-Control 24h(lang/v パラメータで言語別・リリース別キャッシュ)
- 新規 `common/i18n/I18nMessagesLoader.java`: デフォルト包→ロケール包の順にマージ、locale別に `ConcurrentHashMap` キャッシュ、Jackson で JSON 化
  - **★重大リスク(文字化けバグ)**: 決定3で `.properties` を **UTF-8 素文**で保存するが、`java.util.Properties.load(InputStream)` および `PropertiesLoaderUtils.loadProperties(Resource)` は仕様上 **ISO-8859-1** で読む。そのまま使うと日本語・中国語・韓国語が全て文字化けし、JS辞書に壊れた文字列が載る。**必ず UTF-8 を明示して読む**こと:
    ```java
    Properties p = new Properties();
    // どちらか: EncodedResource で UTF-8 を明示
    PropertiesLoaderUtils.fillProperties(p, new EncodedResource(resource, StandardCharsets.UTF_8));
    // または Reader を UTF-8 で開いて Properties.load(Reader)
    // try (Reader r = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) { p.load(r); }
    ```
    (Spring がDI する `ResourceBundleMessageSource` 側は JDK 17 が `.properties` を UTF-8 で読むため無影響。UTF-8明示が必要なのはこの独自ローダーのみ)
- `base.html` / `login.html` で **common.js より前に** 同期ロード
- `common.js` に追加する `SES.i18n`:
  - `t(key, ...args)`: `SES_MESSAGES` 参照 + `{0}` 置換。欠落時はキー名を返す
  - `e(group, dbValue)`: `SES_ENUMS` で日本語DB値→code 変換後 `t('enum.'+group+'.'+code)`。未登録値は原文返し
- `common.js` 本体: ヘッダー時計/曜日は `Intl.DateTimeFormat(SES.i18n.lang, ...)`、`toLocaleString(SES.i18n.lang)`(通貨記号 ¥ は業務通貨なので固定)、トースト/セッション切れ/通知文言は全て `t()`

### 決定5: 列挙値の表示層マッピング(唯一の情報源は Java)

- 新規 `common/i18n/EnumMappings.java`: `Map<group, Map<日本語DB値, ASCIIコード>>`。
  `V1__create_tables.sql` の全 ENUM(約15グループ)を網羅:
  engineerStatus(稼動中→active 等) / projectStatus / proposalStatus(6値) / contractStatus / userRole / contractType / gender / employmentType / priority / remoteType / proficiency 等
- メッセージキーは `enum.engineerStatus.active=稼動中`(ja)/ `Active`(en)/ `在岗`(zh)/ `가동 중`(ko)。**プロパティキーに日本語は使わない**
- Java/Thymeleaf 側: 新規 `common/i18n/EnumLabelResolver.java`(`@Component("enumLabels")`、MessageSource 注入)
  → `th:text="${@enumLabels.label('engineerStatus', eng.status)}"`
- JS 側: `SES_ENUMS` は同じ `EnumMappings.GROUPS` から I18nJsController が直列化 → `SES.i18n.e('engineerStatus', eng.status)`

**鉄則(全タスク共通チェック項目)**
- JS の比較ロジックは不変: `if (eng.status === '稼動中')` はそのまま、出力ラベルだけ `SES.i18n.e(...)` に置換
- `<select>` は `<option value="稼動中" th:text="#{enum.engineerStatus.active}">` — **value は常に日本語DB値、翻訳するのはラベルのみ**
- 送信値・かんばんドラッグのステータス値・`hasRole("管理者")` は一切変更しない

### 決定6: Java層メッセージ

- `BusinessException` に `messageKey + args` を追加(静的ファクトリ `BusinessException.of("error.xxx", args)`)。**既存コンストラクタは互換維持**して漸進移行
- `GlobalExceptionHandler` に MessageSource を注入し、`messageKey != null` なら `LocaleContextHolder.getLocale()` で解決。自身のインライン日本語3箇所もキー化
- `MessageConstants` の定数値を文言→キー名へ変更。新規 `common/util/MessageUtils.java`(MessageSource 静的保持)で `ApiResult.success(MessageUtils.get(MessageConstants.SAVE_SUCCESS))`
- **`StatusConstants` は一切変更しない**(あれはDB値であり文言ではない)
- Validation: WebConfig で `getValidator()` をオーバーライドし `setValidationMessageSource(messageSource)`。DTO は `@NotBlank(message = "{validation.xxx}")`。ValidationMessages.properties は作らない(messages に一本化)
  - **★リスク(未初期化バリデータ)**: `new LocalValidatorFactoryBean()` を `getValidator()` から返す際、初期化 (`afterPropertiesSet()`) が呼ばれないと `validate()` 実行時に `IllegalStateException` で全バリデーションが落ちる。確実性のため明示的に呼ぶ:
    ```java
    @Override
    public Validator getValidator() {
        LocalValidatorFactoryBean bean = new LocalValidatorFactoryBean();
        bean.setValidationMessageSource(messageSource);
        bean.afterPropertiesSet();   // ★必須: これが無いと未初期化で例外
        return bean;
    }
    ```
    (`messageSource` は WebConfig にコンストラクタ注入。既存の `configureMessageConverters` 等と同一クラスに同居させる)

## 新規 / 変更ファイル一覧

**新規**
- `src/main/resources/messages.properties`(ja全量・UTF-8素文)+ `messages_en.properties` + `messages_zh_CN.properties` + `messages_ko.properties`
- `src/main/java/com/ses/common/i18n/EnumMappings.java` / `EnumLabelResolver.java` / `I18nMessagesLoader.java`
- `src/main/java/com/ses/controller/page/I18nJsController.java`
- `src/main/java/com/ses/common/util/MessageUtils.java`
- テスト: `MessageBundleConsistencyTest` / `EnumMappingsCoverageTest` / `I18nLocaleSwitchTest` / `I18nJsControllerTest`

**削除**: `src/main/resources/messages_ja.properties`(デフォルト包へ移管)

**変更**
- `application.yml`、`config/WebConfig.java`、`config/GlobalControllerAdvice.java`
- `templates/layout/{base,header,sidebar}.html`、`templates/login.html`
- `static/js/common.js`
- `common/exception/{BusinessException,GlobalExceptionHandler}.java`、`common/constant/MessageConstants.java`、`common/util/CsvUtils.java`
- 業務テンプレート25ファイル + `static/js/modules/*.js` 21ファイル + `profile.js`
- 各 service impl(InvoiceServiceImpl 19箇所、NotificationGenerateService 18箇所 等の `throw new BusinessException("...")` キー化)、DTO validation アノテーション

## テスト設計

1. `MessageBundleConsistencyTest`(純JUnit): 4ファイルのキー集合完全一致 / 同一キーの `{n}` プレースホルダ集合一致 / 空値なし / `EnumMappings.GROUPS` の全 group×code に `enum.*` キーが存在
2. `EnumMappingsCoverageTest`: `V1__create_tables.sql` から正規表現で全 `ENUM(...)` 値域を抽出し、EnumMappings が網羅していることを断言
3. `I18nLocaleSwitchTest`(MockMvc/H2): `?lang=en` で Set-Cookie + 英語描画 / `SES_LOCALE=zh-CN` Cookie で中国語 / Cookieなしで日本語(fallback-to-system-locale=false の検証)
4. `I18nJsControllerTest`: 未認証で200 / `SES_MESSAGES`・`SES_ENUMS` 含有 / Cache-Control / 不正 lang で500にならない
5. 既存テスト適応: 日本語メッセージを断言するテストはデフォルト ja のため基本無傷。壊れる場合は MessageSource 経由で期待値取得に変更

## リスクと遺留事項

**レビューで検出・是正済みの技術リスク(実装時の必須チェック)**

1. **【重大】UTF-8 プロパティの文字化け** — 独自ローダー `I18nMessagesLoader` が `.properties` を ISO-8859-1 で読むと多言語が全滅。`EncodedResource(resource, UTF-8)` か UTF-8 `Reader` で読む(決定4参照)
2. **【中】未初期化バリデータ** — `getValidator()` で `afterPropertiesSet()` を呼ばないと全バリデーションが例外(決定6参照)
3. **【中】ロケールタグとファイル名の綴りズレ** — `zh-CN` → `messages_zh_CN.properties` の往復を zh-CN ケースのテストで担保(決定1参照)
4. **【中】CSRFは全リクエスト有効(Cookie方式)** — 言語切替は GET なので問題ないが POST 化禁止(決定2参照)

**設計上の意図的トレードオフ / 残リスク**

- `fallback-to-system-locale: false` 漏れ → 英語圏サーバーで Cookie なしユーザーが英語になる(テスト3で担保)
- MessageFormat の単引用符エスケープ(英語訳)
- `use-code-as-default-message: true` はキー欠落を静かにキー名表示にする意図的トレードオフ(画面を500にしない)。欠落は一貫性テストで検出
- 辞書キャッシュ(`I18nMessagesLoader`)は無効化しない。再デプロイ時は `/js/i18n.js?v=版本` の `v` を必ず更新して 24h キャッシュを破棄する
- 遺留: メールテンプレート(日本語のまま) / invoice print + PDF(日本語固定、中韓はフォント埋込方式の検討が必要) / CSVデータ列の列挙値(生の日本語値)

## タスク分解と並行実行ガイド

`docs/i18n/tasks.md` を参照。
