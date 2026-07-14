# 国際化(i18n)+ UI言語切替 実行タスク

設計の詳細は `docs/i18n/plan.md` を参照。各タスクは「実装 → `mvn test` → Demo検証」まで完了してからチェックを付ける。

## ステージ1: 基盤(直列 — 並行不可)

以降の全タスクの前提となるため、T1 → T2 → T3 の順に**単独で**実施する。

- [ ] **T1. Locale切替基盤 + layout/login**
  - 対象: `application.yml`(spring.messages ブロック書換)、`config/WebConfig.java`(localeResolver / localeChangeInterceptor / addInterceptors)、`config/GlobalControllerAdvice.java`(`langSwitchBase` 注入)、`messages.properties` 新設(既存 messages_ja を UTF-8 素文化して移管・削除)+ en/zh_CN/ko の骨格(既存411キー分の翻訳)、`layout/base.html`(th:lang・フォント)、`layout/header.html`(言語ドロップダウン + 全文言キー化)、`layout/sidebar.html`(ハードコード残り8箇所)、`login.html`(切替リンク + キー化)
  - テスト: `I18nLocaleSwitchTest` 新設
  - Demo: `/login?lang=en` で SES_LOCALE Cookie 書込 + 英語表示 → ログイン後ヘッダーで zh-CN へ切替 → サイドバー中国語 → ブラウザ再起動でも言語維持 → `<html lang>` が追従

- [ ] **T2. フロントJS辞書機構 + common.js**(T1 完了後)
  - 対象: `common/i18n/I18nMessagesLoader.java`、`controller/page/I18nJsController.java`(`/js/i18n.js`)、`base.html`/`login.html` への読込追加(common.js より前)、`common.js`(`SES.i18n.t/e` + 全文言キー化 + Intl 日時)
  - テスト: `I18nJsControllerTest` 新設
  - Demo: 未認証で `GET /js/i18n.js?lang=ko` が 200/application/javascript/Cache-Control → 言語切替後にトースト(403を故意に発生)・ヘッダー時計・通知ドロップダウン文言が追従

- [ ] **T3. 列挙値マッピング機構 + engineer での雛形**(T2 完了後)
  - 対象: `common/i18n/EnumMappings.java`(V1 SQL の全 ENUM 約15グループを網羅)、`common/i18n/EnumLabelResolver.java`、`enum.*` キー4言語分、`I18nJsController` へ `SES_ENUMS` 追加、`SES.i18n.e()`。雛形として `engineer/list.html` のステータスバッジ + 絞り込み `<select>` のみ先行適用
  - テスト: `EnumMappingsCoverageTest` 新設
  - Demo: en 切替でバッジが Active、option の value は DOM 上 `稼動中` のまま、絞り込み・保存後の DB 値不変

## ステージ2: 業務画面 + Java層(並行可能 — サブエージェント割当)

T3 完了後、以下 **P-A〜P-E は互いにファイルが重ならないため同時開工できる**(最大5並行)。

### 並行実行ルール(全エージェント共通・厳守)

1. **担当外のファイルに触れない**。特に `common.js` / `WebConfig` / `base.html` / `EnumMappings.java` はステージ1で凍結済み(不足があれば orchestrator へ報告、直接編集しない)
2. プロパティ4ファイルだけが共有資源。**自モジュールのセクション(`# ===== engineer =====` 等)への追記のみ**、キーは自分の名前空間(`<module>.*` / `error.<module>.*`)限定 → 追記同士なので競合してもマージが容易
3. 鉄則の再掲: JS 比較ロジック(`=== '稼動中'`)不変 / `<option>` value は日本語DB値のまま / 送信値不変
4. 完了条件: 担当テンプレート・JS に日本語リテラル残存ゼロ(コメント除く、`grep` で確認)+ 4言語でCRUD主要動線を通す
5. git worktree でエージェントごとに分離し、orchestrator が順次マージ(プロパティの追記競合はマージ時に解消)

### 並行タスク

- [ ] **P-A. ダッシュボード + ToDo**
  - `dashboard/index.html` `dashboard/profit.html` `todo/list.html` + `dashboard.js` `dashboard-profit.js` `todo.js`
  - Demo: 4言語でKPIカード・グラフ凡例・ToDo一覧のCRUD

- [ ] **P-B. 要員 + 顧客**
  - `engineer/list.html`(T3雛形の残り) `engineer/detail.html` `customer/list.html` `customer/detail.html` + `engineer.js` `engineer-detail.js` `ai-skillsheet.js` `customer.js` `customer-detail.js`
  - Demo: 4言語で要員/顧客の一覧・検索・登録・編集・削除、ステータスバッジ表示

- [ ] **P-C. 案件 + 提案かんばん + 契約**(列挙値最密集エリア)
  - `project/list.html` `proposal/kanban.html` `contract/list.html` `contract/gantt.html` + `project.js` `proposal-kanban.js` `contract.js` `contract-gantt.js`
  - Demo: かんばんドラッグでステータス遷移(送信値が日本語のままであること)、ガントの凡例、4言語でCRUD

- [ ] **P-D. 請求 + 勤怠 + 管理系画面**
  - `invoice/list.html`(print.html は対象外) `work-record/list.html` `user/list.html` `email-template/list.html` `system-config/list.html` `audit-log/list.html` `analytics/index.html` `ai/matching.html` + 対応する modules JS + `profile.js`
  - Demo: 4言語で各画面の主要動線(ユーザー管理はロール表示ラベルの翻訳と権限判定の非影響を確認)

- [ ] **P-E. Java層メッセージ**(テンプレート/JSに触れないため P-A〜D と競合しない)
  - `common/exception/BusinessException.java`(messageKey+args、旧互換維持) `GlobalExceptionHandler.java`(MessageSource 解決) `common/util/MessageUtils.java` 新設 `MessageConstants.java` キー化、各 service impl の `throw new BusinessException("...")` → `BusinessException.of("error....", args)`、DTO の `@NotBlank(message="{validation....}")` + WebConfig `getValidator()`、`CsvUtils` ヘッダのロケール追従
  - プロパティ追記は `error.*` / `validation.*` / `common.msg.*` 名前空間のみ
  - Demo: en 切替でビジネス例外(重複ユーザー名等)のトーストが英語、validation エラーが英語、既存テスト全緑

## ステージ3: 収束(直列 — P-A〜P-E 全完了後)

- [ ] **T9. 翻訳整合 + テスト収口**
  - 4ファイルのキー整合(`MessageBundleConsistencyTest` を正式追加し全チェック有効化)、翻訳レビュー(特に en の単引用符エスケープ)、残存日本語リテラルの全体 grep 監査、遺留事項(メールテンプレート/PDF/CSVデータ列)を README または本ドキュメントに明記
  - Demo: `mvn test` 全緑 → 4言語でログイン→主要8画面を一巡(表示・トースト・確認ダイアログ・例外メッセージ・列挙ラベル)→ DB 値が日本語のまま不変であることを抽査

## 依存関係まとめ

```
T1 ──> T2 ──> T3 ──┬──> P-A ─┐
                   ├──> P-B ─┤
                   ├──> P-C ─┼──> T9
                   ├──> P-D ─┤
                   └──> P-E ─┘   (P-A〜P-E は並行可能)
```
