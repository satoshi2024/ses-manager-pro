# 全spec共通実装標準 v2.0

## 0. 必須実行基線

本書を読む全ての実装AI・子Agent・Review AIは、作業前に `execution-review-handbook.md` v2.0を全文読み、
READINESS、TASK CONTRACT、Review Packet、Issue Register、再Review収束規則を適用する。
S02の再発防止根拠は `s02-review-retrospective.md` を正とする。

本規則は2026-07-28以前に作成されたcopyable conversationにも適用する。既存対話は継続利用できるが、
旧対話文だけを完了条件にしてはならない。矛盾時の優先順位はhandbook §1に従う。
各specのreview-ledgerは `review-ledger-template.md` の現行判定、Issue Register、Review Packet、証拠表を使用する。
既存対話へ送る切替文面は `conversation-baseline-v2.md` を使用する。
S03〜S17（T014〜T115）のtest実行範囲は `test-execution-policy-s03-s17.md` を正とし、通常Taskへ
無条件の全量testを要求しない。全量はM task、明記された高risk checkpoint、CI/releaseへ集約する。

## 1. 既存資産を唯一の正として再利用する

- API応答: `ApiResult<T>`、業務例外: `BusinessException`、API例外変換: `GlobalExceptionHandler`。
- 認証者: `LoginUser`/`SecurityUtils`。更新系はCookie CSRFを維持する。
- メニュー: `m_menu`/`t_role_menu`/`MenuPermissionFilter`。組織/tenant権限追加後も管理者バイパスと
  platform-adminを混同しない。
- 行スコープ: `DataScopeService`。画面だけでなく詳細、検索、集計、通知、export、downloadへ同じ母集団を適用。
- 状態機械: サービスの`Map<現状態, Set<遷移先>>`を唯一の正とし、更新は条件付きUPDATE/CAS。
- 金額: DB/API/Javaは円。`BigDecimal`を使い、丸め規則を明記する。
- ファイル: `FileStorageService`を通し、`FileReferenceProvider`とdownload scopeを必ず追加する。
- 通知: `NotificationLinks`定数を使い、受信者ID/ロール/メニュー可視性を明示する。
- 外部API: `saasRestTemplate`、timeout、相関ID、冪等キー、retry可能/不可の分類、監査、秘密情報非ログ出力。

## 2. DDL・マイグレーション

1. 新規テーブル/カラムはV1統合baselineにも最終形を反映する。ただし増分migrationとの重複ADDを作らない。
2. 増分Flywayは過去ファイルを変更せず、新しい予約番号を使う。
3. H2は`application-test.yml`のreplay用SQLと`engineer-schema-h2.sql`を同一タスクで同期する。
4. MySQL固有のENUM、生成列、索引、FKは`FlywayMigrationSmokeTest`で実在をassertする。
5. tenant採用時、業務テーブルのUNIQUEは原則`tenant_id`を先頭に含める。
6. 外部IDのUNIQUEは`provider + company/tenant + external_id`とし、単独external_idを全社一意とみなさない。

## 3. API設計

- 一覧はMyBatis-Plusページング上限1000を守る。全件取得APIを新設しない。
- 一括操作は最大200件、各行結果を返し、全件原子的にするか部分成功にするかrequirementsで固定する。
- 作成/外部連携は`Idempotency-Key`または業務一意キーで再送安全にする。
- 更新対象には`version`による楽観ロック、または状態+updated_atのCASを使う。
- 403は存在秘匿が必要な詳細で404へ変換する既存規約を継承する。
- CSV/Excel/PDF/ZIPもAPI一覧と同じフィルタ/tenant/data scopeを使う。
- 外部ポータルAPIは内部`/api/**`を再利用せず、`/api/portal/**`の専用DTOで公開項目を限定する。

## 4. セキュリティ・個人情報

- 最小権限、MFA、セッション失効、監査、秘密情報暗号化、未知ファイルfail-closedを標準とする。
- パスワード/TOTP secret/OAuth token/API key/銀行口座/マイナンバーをログへ出さない。
- AI送信前に氏名、連絡先、住所、口座、自由記述PIIをマスキングし、送信項目を監査可能にする。
- ファイルは拡張子/MIMEだけでなくmagic bytes検証とマルウェアスキャン状態を持つ。
- tenant contextの欠落時は拒否する。scheduler/async処理は明示的tenant contextを設定しfinallyで解除する。
- `platform-admin`は通常の`管理者`とは別認証境界に置き、顧客データの通常閲覧権を与えない。
- G1〜G6のarchitecture decisionは`gate-decisions-g1-g6.md`を正とする。外部専門家署名、provider契約、
  sandbox、本番credentialが未取得でもmock/provisional mappingで基盤開発できるが、取得済みと偽ってはならない。
- 法務専門家、実provider、法人別36協定/就業規則の未確認事項は該当M taskと本番releaseをfail-closedにする。

## 5. UI/i18n/アクセシビリティ

- 既存Bootstrap/jQuery/Thymeleaf構成を維持し、新しいフロントビルド基盤を導入しない。
- PC/390px幅の両方をDemoし、キーボード操作、label、aria-live、focus復帰を確認する。
- 4言語バンドルへ同じキー集合を追加する。法定帳票の固定日本語は、帳票要件で日本語固定と明記した場合のみ可。
- 状態、金額、担当、法令警告は色だけで表現しない。

## 6. テストの最低セット

以下はspec全体で満たすべき種類であり、各通常Taskで全種類・全量を毎回実行する意味ではない。
Task単位の選択と昇格は `test-execution-policy-s03-s17.md` のL0〜L5に従う。

| 種別 | 必須内容 |
|---|---|
| 単体 | 状態機械、金額、期限、スコア、マッピング、法令警告境界 |
| サービス | 正常、入力不正、権限、競合、冪等、rollback、外部API失敗 |
| MVC | CSRF、ロール、404秘匿、ApiResult codeとHTTP status一致 |
| DB | H2 schema、MySQL Flyway smoke、UNIQUE/FK/tenant複合制約 |
| セキュリティ | tenant A→B漏洩、営業A→営業B漏洩、download/export/notification漏洩 |
| ブラウザDemo | desktop/mobile、状態遷移、再読込、二重クリック、戻る操作 |
| 外部連携 | WireMock等で200/400/401 refresh/429/500/timeout/重複再送 |

## 7. 他AIへの報告書式

各タスク完了時に次を必ず報告する。

1. 変更ファイル一覧
2. 対応したrequirements ID
3. DDL/H2/MySQL同期箇所
4. 実行したテストと件数/結果
5. Demo環境、操作、結果
6. 未検証事項・外部契約/法務待ち
7. 既知のトレードオフとロールバック方法
