# 現行モジュール横断バグ監査 — 改修方法と期待効果

- 監査日: 2026-07-22
- 対象: main@6f160db
- 対象範囲: 画面/API、サービス間トランザクション、権限・データスコープ、通知、AI、契約・勤怠・請求、論理削除、Flyway、本番/テスト設定
- 本書の性質: **調査結果のまとめのみ**。ソースコード、SQL、設定、テストの改修は実施していない
- 判定方針: 現行コードから到達経路と不整合を確認できたものを「確認済み」とし、要件判断または実環境確認が残るものは末尾の「リスク・確認事項」へ分離した

## 1. 結論

現行スナップショットでは、モジュール境界に **30件の確認済み不具合** が残っている。

| 優先度 | 件数 | 意味 |
|---|---:|---|
| P1 | 8 | 本番起動不能、権限/機密性の破壊、または並行実行で中核データが矛盾するため、リリース前に対応 |
| P2 | 20 | 通常操作で機能不全、誤計上、誤表示、再操作不能、または権限/UIの不一致が起きるため、直近改修 |
| P3 | 2 | 公開ルートの500および回帰テストの構造的盲点。P1/P2の再発防止と合わせて対応 |

特に先に止めるべきものは次のとおり。

1. **MI-01 / MI-02**: 本番Flywayと旧手動DBアップグレードが起動前に失敗する。
2. **MI-03〜MI-06**: 通知・AIに、越権閲覧、XSS、外部送信停止不能、APIキー露出の経路がある。
3. **MI-07 / MI-08**: 提案・契約の終端遷移が並行実行されると、契約、履歴、要員状態、通知、日付が矛盾する。
4. **MI-09〜MI-12**: 契約終了、単価改定、設定キャッシュ、Webhookが月次計上またはトランザクション境界と一致しない。

## 2. 実施した確認

- 全量テスト:
  - 実行: .\apache-maven-3.9.6\bin\mvn.cmd test
  - Maven最終結果: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**
  - ただし、Dockerが利用できないため FlywayMigrationSmokeTest はスキップされた。したがって「全テスト成功」は実MySQLの移行経路が成功することを意味しない。
- DB/マッパー対象限定テスト:
  - AllMappersSchemaSweepTest、EntitySchemaMismatchIntegrationTest、SesManagerApplicationTests、FlywayMigrationSmokeTest
  - **43 tests / 0 failures / 0 errors / 1 skipped**。スキップは実MySQLコンテナのテスト。
- 本番Flyway locationの直接確認:
  - db/migration と db/migration-prod を同時指定して Flyway info を実行。
  - **Found more than one migration with version 10** を再現し、MI-01を確認した。
- 静的到達性確認:
  - Controller → Service → Mapper/DB、テンプレート → JavaScript → API、通知publish → 可視性SQL、設定更新 → キャッシュ、AI入力 → 外部HTTPの各経路を追跡した。
- 既存監査との突合:
  - 既に修正済みの旧指摘は再掲していない。
  - 例として、ルールマッチングの円/万円換算は現行 RuleMatchingServiceImpl で両側とも1万円単位へ変換済み、請求の一括督促/履歴UIも現行実装に存在するため、旧内容をそのまま残件扱いしていない。

---

## 3. P1 — リリース阻断・セキュリティ・中核データ不整合

### MI-01 【P1】prodでFlywayのV10が重複し、本番起動が必ず失敗する

- 対象:
  - **src/main/resources/application-prod.yml:18**
  - **src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql**
  - **src/main/resources/db/migration-prod/V10__update_admin_password_bcrypt.sql**
- 現象:
  - prodは db/migration と db/migration-prod を同時に読み込む。
  - 両方に version 10 が存在するため、FlywayはSQL実行前のresolver段階で重複エラーを出す。
- 影響:
  - Webサーバーが利用可能になる前にprodが停止し、全モジュールが利用不能になる。
  - BP索引修正と管理者パスワードのBCrypt化のどちらも信頼して適用できない。
- 改修方法:
  1. 先に各環境の flyway_schema_history で version 10 の description/checksum を確認し、過去にどちらが記録されたかを特定する。
  2. profile専用移行も含めてversionを全locationで一意にする。平文adminだけを更新する処理は、冪等なRepeatable migrationへ移す案も有効。
  3. 既存環境には、履歴を誤認させない明示的なrepair/整理手順を用意する。履歴確認なしの単純リネームは行わない。
  4. CIに main+prod のcombined-location validate/migrateを追加する。
- 期待効果:
  - prodが正常起動し、BP索引変更と管理者パスワード移行がそれぞれ一度だけ適用される。

### MI-02 【P1】「旧手動SQL適用済みDBをbaseline=9から更新」の経路がV10で停止する

- 対象:
  - **src/main/resources/application.yml:50-59**
  - **src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql**
  - **src/main/resources/db/migration/V17__fix_bp_payment_unique_key.sql:17-26**
  - **README.md:90-98**
- 現象:
  - 旧手動V1〜V9相当の t_bp_payment には layer_order、payee_company_name、parent_payment_id、deleted_flag、uk_work_record_layer がない。
  - baseline=9では現在のV5が再実行されず、直後のV10が存在しない uk_work_record_layer をDROPして失敗する。
  - MySQL DDLは途中までコミットされ得るため、再試行時は先行CREATE INDEXの重複でさらに復旧しにくい。
- 影響:
  - READMEでサポートすると説明している既存DBアップグレードが完遂できない。
  - V10を手作業で飛ばしても、BpPaymentエンティティと実DBの列が一致せず、勤怠/BP支払機能が壊れる。
- 改修方法:
  1. V10より前に実行されるlegacy bridgeを用意し、information_schemaで列・索引の存在を確認しながら不足列、既定値、制約を補う。
  2. 既にV10以降まで到達した環境との履歴差を調査し、必要なら一度限りのbeforeMigrate callbackまたは運用SQLとして提供する。
  3. 「旧手動001〜009 → baseline 9 → 現行migrate」を実MySQLでCI化し、空DB構築後のschemaと比較する。
- 期待効果:
  - 新規空DBと旧手動DBの両方が同じ最終schemaへ収束し、途中失敗後の再実行も可能になる。

### MI-03 【P1】通知の未定義種別が全ユーザー公開になり、メール・BP・契約情報が越権表示される

- 対象:
  - **NotificationServiceImpl.java:95-110**
  - **NotificationMapper.java:15-53**
  - **MailServiceImpl.java:117-125**
  - **WorkRecordServiceImpl.java:273-280**
  - **ContractRenewalServiceImpl.java:61-67**
  - **SecurityConfig.java:110-114**
- 現象:
  - 未定義通知タイプは menuKey=null、通常publishは recipientUserId=null になる。
  - 検索SQLは menu_key IS NULL と recipient_user_id IS NULL を「全員閲覧可」と解釈する。
  - MAIL_FAILED、BP_AMOUNT_MISMATCH、CONTRACT_RENEWAL_DRAFT は対応表にない。
- 影響:
  - 要員ロールを含む全ログインユーザーが、送信先メールアドレス、BP支払不一致、契約番号・更新日を閲覧できる。
  - Todo画面の種別フィルター/アイコン定義も実際の通知タイプとずれ、運用上の追跡性が低い。
- 改修方法:
  1. 通知作成時に recipient、menu、roleのいずれかのaudienceを必須にする。
  2. 未知タイプはfail-closedとし、拒否または管理者限定にする。検索SQLでnullを自動的な全体公開にしない。
  3. MAIL_FAILEDは管理者/作成者へ個別送信し、メールアドレスをマスクする。
  4. BP不一致は請求/勤怠、更新ドラフトは契約の適切な権限へ割り当てる。
  5. 通知タイプ、表示名、アイコン、フィルター、権限を単一レジストリから生成する。
- 期待効果:
  - 契約・財務・個人連絡先が役割をまたいで漏れず、通知画面とバックエンドの種別定義も一致する。

### MI-04 【P1】営業データスコープ有効時もAI APIが制限を迂回し、担当外の要員・案件を取得/外部送信できる

- 対象:
  - **AiApiController.java:28-48**
  - **AiRestController.java:30-60**
  - **RuleMatchingServiceImpl.java:47-60,111-163**
- 現象:
  - scope.sales-own-data-only=true かつ営業ロールの場合に、通常の一覧/詳細へ適用されるデータスコープがAI経路へ適用されない。seed既定値はfalseだが、この設定を有効化すると境界が部分的にしか成立しない。
  - AI APIは任意の engineerId/projectId を受け取るが DataScopeService を使用しない。
  - 逆向きマッチングは全Bench/提案中要員、順向きマッチングは全募集中案件を母集団にする。
  - chatは指定IDの氏名、経歴、単価、案件詳細をそのままGemini promptへ含める。
- 影響:
  - 営業Aが営業Bの担当IDを直接指定し、通常の一覧/詳細では見えない情報を取得できる。
  - 担当外情報が外部AIへ送信されるため、画面上の越権表示より影響が大きい。
- 改修方法:
  1. Controller/Serviceの双方で DataScopeService を使用し、DBロードまたは外部送信の前に入力IDを検証する。
  2. 候補集合を取得するSQL段階で allowedEngineerIds と許可顧客から導出したprojectIdsに限定する。
  3. skill-sheet生成にも同じ要員スコープを適用する。
  4. 担当外IDは存在を推測させない404とし、BusinessExceptionを汎用500へ変換しない。
- 期待効果:
  - AIが通常CRUDと同じデータ境界内で動作し、担当外情報が結果にも外部promptにも入らない。

### MI-05 【P1】AI Markdownを無害化せずHTML挿入し、同一オリジンXSSとAPIキー窃取が可能

- 対象:
  - **src/main/resources/templates/ai/matching.html:7-8**
  - **src/main/resources/static/js/modules/ai.js:2-27,97-105,286-294**
- 現象:
  - marked.parseの結果をsanitizerなしでjQueryの html() へ挿入する。
  - モデルが返したイベント属性、javascript: URL、SVG等がDOMとして実行され得る。
  - Gemini APIキーは localStorage に保存される。
- 影響:
  - モデル応答、prompt injection、上流汚染を起点に、SES Manager Proの同一オリジンで任意スクリプトが実行される。
  - セッションで可能なAPI操作に加え、localStorageのGemini APIキーも窃取され得る。
- 改修方法:
  1. DOMPurify等でMarkdown変換後HTMLを厳格にsanitiseし、許可タグ/属性/URI schemeを限定する。
  2. またはMarkedのraw HTMLを無効化し、テキスト主体の描画へ切り替える。
  3. APIキーをlocalStorageへ永続保存せず、サーバー側secretまたは短命なメモリ保持へ移す。
  4. CSPも追加防御として導入する。
- 期待効果:
  - Markdown表示を維持しつつ、モデル応答からスクリプト実行・セッション/APIキー奪取へ連鎖しない。

### MI-06 【P1】ai.enabled=falseでもGeminiへ実送信でき、APIキーがログ/エラー応答へ出る可能性がある

- 対象:
  - **src/main/resources/application.yml:138-145**
  - **AiConfig.java:20-45**
  - **AiRestController.java:30-66**
  - **GeminiService.java:29,38-79**
- 現象:
  - 設定は ai.enabled=false だが、chat endpointとGeminiServiceはこのフラグを参照しない。
  - クライアントがapiKeyを渡せば実Gemini URLへ人員/案件コンテキストを送信する。
  - キーはURL queryへ連結され、例外全体をログ出力し、e.getMessage()をAPI応答へ返す。
- 影響:
  - 運用者がAIを無効化したつもりでも外部送信を止められない。
  - HTTP例外がrequest URLを含む場合、APIキーがサーバーログまたはクライアント応答へ露出する。
- 改修方法:
  1. ページ、API、外部HTTP clientを共通のAiConfig enabled/provider判定で強制停止する。
  2. endpoint/model/keyを設定から注入し、可能なら認証情報をheaderへ移す。
  3. ログにはprovider statusとtrace IDだけを残し、URL/query/secretを完全にマスクする。
  4. APIへは汎用化したエラーコードのみ返す。
- 期待効果:
  - 無効設定が実際のデータ外送信を止め、APIキーがログ・画面・例外へ残らない。

### MI-07 【P1】提案の「結果待ち→成約/見送り」が並行成功し、契約・履歴・要員状態が矛盾する

- 対象:
  - **ProposalServiceImpl.java:37-43,83-132**
- 現象:
  - changeStatusは通常SELECT後にupdateByIdし、行ロックも旧statusを条件にしたCASもない。
  - 二つのトランザクションが同じ「結果待ち」を読み、片方が成約、もう片方が見送りを実行できる。
  - 両方が履歴を追加し、成約側は契約ドラフト/通知、見送り側は要員解放を行う。
- 影響:
  - 最終提案が見送りなのに契約ドラフトが存在する、または成約なのに要員がBenchへ戻る等の矛盾が起きる。
  - 履歴には同じ旧statusから二つの終端遷移が記録される。
- 改修方法:
  1. ProposalMapperに selectByIdForUpdate を追加し、最新statusをロック下で再検証する。
  2. 代替として UPDATE ... WHERE id=? AND status=? のCASを行い、更新0件を409競合として返す。
  3. 状態更新、履歴、要員状態、契約ドラフト、通知を同じトランザクションに保つ。
- 期待効果:
  - 終端遷移は一要求だけが成功し、提案・契約・履歴・要員状態・通知が単一の事実として一致する。

### MI-08 【P1】契約の「稼動中→終了/解約」がlost updateし、解約日や同時編集を上書きする

- 対象:
  - **ContractServiceImpl.java:167-185,203-240,353-421**
  - **Contract.java の endDate更新戦略**
- 現象:
  - 通常編集と単価改定は selectByIdForUpdate を使う一方、changeStatusだけ通常selectById後の全エンティティupdateである。
  - 終了と解約が同時に旧「稼動中」を読み、双方成功できる。
  - endDateはnullも更新するため、終了側の古いnullが解約側のcancelDateを消す可能性がある。
  - 同じ全行updateは、同時の通常編集/単価改定を巻き戻し得る。
- 影響:
  - 最終status、終了日、価格、備考等が要求順ではなくコミット順で欠落する。
  - その後の勤怠・売上・コミッション集計まで誤る。
- 改修方法:
  1. changeStatusも selectByIdForUpdate を使用し、最新statusで遷移を再検証する。
  2. statusと終了日に限定した部分UPDATEまたはstatus CASを使い、影響件数0を409にする。
  3. 通常編集、単価改定、終端遷移で同じロック規約を共有する。
- 期待効果:
  - 終端状態は一つだけ成立し、解約日・価格・他項目が並行操作で失われない。

---

## 4. P2 — データ整合性・機能契約・画面/API境界

### MI-09 【P2】end_dateなしで契約を「終了」にでき、未来月へ永続的に計上される

- 対象:
  - **ContractStatusChangeRequest.java:8-19**
  - **ContractServiceImpl.java:203-240**
  - **WorkRecordMapper.java:35-43,71-80**
  - **MonthlyRevenueCalcServiceImpl.java:118-126**
  - **SalesPerformanceServiceImpl.java:211-216**
- 現象:
  - 解約はcancelDate必須だが、終了遷移はendDateを要求・更新しない。
  - 集計側はstatus=終了を含め、endDate=nullを上限なしとして扱う。
- 影響:
  - 無期限契約を終了させても、任意の未来月で勤怠入力候補、売上、粗利、営業コミッション、稼働率に残り続ける。
- 改修方法:
  1. 終了時にcompletionDateを必須にし、end_dateへ保存する。
  2. 既存endDateが未来の場合に「予定満了」と「実終了」のどちらを採るかを業務ルールとして明示する。
  3. 契約が対象月に有効かの判定を共通関数/SQLへ集約し、全集計で共有する。
- 期待効果:
  - 終了月より後の勤怠・売上・粗利・コミッション・稼働率から契約が確実に外れる。

### MI-10 【P2】単価改定後も既存未確定勤怠の金額が古いまま確定・請求される

- 対象:
  - **ContractServiceImpl.java:353-432**
  - **WorkRecordServiceImpl.java:204-225**
  - **InvoiceServiceImpl.java:91-120**
- 現象:
  - revisePriceは価格履歴と契約現在価格だけを更新し、適用月以降の既存WorkRecordを再計算しない。
  - confirmMonthはstatus変更だけを行い、請求生成は保存済み billingAmount を合算する。
- 影響:
  - 旧単価で勤怠入力 → 同月適用の単価改定 → 月確定 → 請求、の順で操作すると、契約履歴は新単価でも請求は旧単価になる。
  - BP支払、営業成績、月次締めにも別口径が残る。
- 改修方法:
  1. applyFrom以降の未請求かつ非確定状態（入力中/提出済/差戻し）を ContractPriceResolver と SettlementCalculator で再計算する。
  2. BP支払階層を同時再計算するか、再生成規約を定義する。
  3. 確定済み/請求済みは自動変更せず、差額調整または明示的な再オープン手順へ誘導する。
- 期待効果:
  - 契約価格履歴、勤怠金額、請求、BP支払、コミッションが同じ適用月ルールで一致する。

### MI-11 【P2】システム設定の一括更新がロールバックしてもJVMキャッシュだけ変更される

- 対象:
  - **SystemConfigApiController.java:48-63**
  - **SystemConfigServiceImpl.java:131-170**
- 現象:
  - Controllerのトランザクション内で設定を一件ずつDB更新し、その都度cache.put/removeする。
  - 先頭が有効、後続が未知キー等で失敗するとDBは全件ロールバックするが、ConcurrentHashMapは戻らない。
- 影響:
  - DB値と実行中アプリの税率、通知閾値、コミッション率、データスコープ等が再起動まで食い違う。
- 改修方法:
  1. バッチ全体を先に検証してからDBを書き込む。
  2. キャッシュ反映は TransactionSynchronization または AFTER_COMMIT eventでコミット後に行う。
  3. ロールバック時は対象キーをevictし、次回DBから再ロードする。
- 期待効果:
  - 失敗バッチはDB/キャッシュとも無変更、成功バッチはコミット後に一括可視化される。

### MI-12 【P2】通知WebhookがDBコミット前に非同期送信され、ロールバック後も外部通知だけ残る

- 対象:
  - **NotificationServiceImpl.java:129-147**
  - **WebhookNotifier.java:44-61**
  - **ProposalServiceImpl.java:83-132**
  - **ContractRenewalServiceImpl.java:39-68**
- 現象:
  - 通知INSERT直後に @Async のWebhookを呼び出す。
  - 呼出元の提案成約/契約更新トランザクションがまだ未コミットでも、外部HTTPは先に成功できる。
- 影響:
  - 後続処理が失敗してDBがロールバックしても、Slack等には存在しない契約/更新の通知が残る。
- 改修方法:
  1. 通知作成イベントを発行し、TransactionalEventListener(AFTER_COMMIT)から非同期送信する。
  2. トランザクション外publishは即時送信できる共通dispatcherを用意する。
  3. 再送・監査が必要ならoutboxテーブルへ発展させる。
- 期待効果:
  - 外部Webhookはコミット済みの業務事実だけを通知し、DBとSlackの食い違いがなくなる。

### MI-13 【P2】案件なし/存在しない要員の見積を受注できるが、契約ドラフト生成で500になる

- 対象:
  - **QuotationSaveRequest.java:15-20**
  - **QuotationServiceImpl.java:66-87,131-143,174-182**
  - **ContractServiceImpl.java:282-327**
  - **V29__quotation.sql:10-13**
  - **V1__create_tables.sql:303-337**
- 現象:
  - 見積のprojectId/engineerId/proposalIdはnullableで、保存時に要員・提案の存在も検証しない。
  - 受注時はengineerIdだけを要求し、projectIdなしでも終端「受注」へ進める。
  - 契約は project_id NOT NULL/FK のため、ドラフトINSERT時にDB例外になる。
  - 存在しないengineerIdも契約FKで失敗する。一方、存在しないproposalIdは契約ドラフトへ引き継がれないため、この500の直接原因ではないが、見積上の孤児参照として残る。
- 影響:
  - 見積は編集不能な受注状態なのに契約が存在せず、ユーザーには業務エラーでなく500が返る。
- 改修方法:
  1. 受注およびドラフト生成の両方で、有効な案件・要員と案件/顧客整合を再検証する。
  2. proposalIdがある場合は、ドラフト500とは別の参照整合性問題として存在と組合せを検証する。
  3. 顧客単位見積を許すなら、受注前または契約化前に案件を関連付けられる明示的ワークフローを設ける。
  4. 既存の孤児データを整理後、見積の参照列へ適切なFK/索引を追加する。
- 期待効果:
  - 契約化不能な見積は終端へ進まず、正常受注は必ず契約ドラフトを作成できる。

### MI-14 【P2】受注/失注後の見積備考追記で、JSとDTOのフィールド名が違い常に400になる

- 対象:
  - **src/main/resources/static/js/modules/quotation.js:194-225**
  - **QuotationRemarkAppendRequest.java:6-9**
  - **QuotationApiController.java:158-163**
- 現象:
  - JSは additional を送るが、DTOとControllerは additionalRemark を要求する。
- 影響:
  - 有効な入力でも「追記内容は必須です」となり、終端見積へ備考を追記できない。
- 改修方法:
  1. JSを additionalRemark に統一する。
  2. 既存クライアント互換が必要なら短期間だけ JsonAlias("additional") を追加する。
  3. JSON propertyを検証するMockMvc契約テストと画面操作テストを追加する。
- 期待効果:
  - 受注/失注見積へ追記でき、前後端のリクエスト契約がテストで固定される。

### MI-15 【P2】動的メニュー設定、静的SecurityConfig、画面依存APIの3系統が一致しない

- 対象:
  - **RoleMenuApiController.java:30-82**
  - **LoginSuccessHandler.java:24-32**
  - **SecurityConfig.java:95-114**
  - **layout/sidebar.html:21-172**
  - **layout/header.html:73,83-88**
  - **user/list.html:99-114**
  - 例: **contract-document.js:20** が /api/contracts を参照
- 現象:
  - Dashboardを権限UIで外せるが、非要員ログイン先は常に/dashboardで、Sidebarにも常時表示されるため403になる。
  - HeaderのTodo/Quick AddはallowedMenusを見ず、権限撤回後も403リンクを表示する。
  - UIは管理者ロールの権限編集を許すがAPIは拒否する。
  - user/system-config/audit-log/my-timesheet等を不適合ロールへ付与できるが、静的SecurityConfigは拒否する。
  - contract-document等は別メニューのlookup APIへ依存するため、単独付与では画面内APIが403になる。
- 影響:
  - 「権限画面で設定できる」「画面に見える」「実際にアクセスできる」が一致しない。
  - ログイン直後403、空の選択肢、操作途中403が発生する。
- 改修方法:
  1. サーバー側にロール互換マトリクスとメニュー依存グラフを定義し、保存時に検証する。
  2. Dashboardを必須にするか、ログイン後に最初の許可メニューへ遷移する。
  3. Headerを含む全リンクをallowedMenusで制御する。
  4. lookup用途は呼出元メニュー権限で読める最小APIへ分離するか、依存メニューを連動付与する。
  5. 管理者を権限編集対象から除外し、スーパー権限を一貫させる。
- 期待効果:
  - 権限設定、ナビゲーション、ページ、内部APIが同じアクセスモデルで動作し、見えるが使えない機能がなくなる。

### MI-16 【P2】管理者だけcontract-documentのメニュー行がなく、直アクセス可能なのにSidebarから消える

- 対象:
  - **V20__contract_document_esign.sql:3-6**
  - **GlobalControllerAdvice.java:67-86**
  - **MenuPermissionFilter.java:59-65**
  - **layout/sidebar.html:85-89**
- 現象:
  - V20は営業/HR/マネージャーだけをt_role_menuへ追加し、管理者を追加しない。
  - 管理者はfilterをbypassするので直URLは開けるが、allowedMenusはDB行から作るためSidebarに出ない。
  - MI-15の一般的な権限モデル不一致とは別に、管理者ロールがAPIから変更禁止のため、権限画面では修復できないmigration seed欠落である。
- 改修方法:
  1. 新しいmigrationで管理者のcontract-document対応をINSERT IGNOREする。
  2. 将来のメニュー追加も漏れないよう「管理者は全m_menuを持つ」seed invariantをテストするか、GlobalControllerAdviceで管理者へ全メニューをunionする。
- 期待効果:
  - 管理者のスーパー権限とナビゲーションが一致し、URLを手入力せず契約書機能へ到達できる。

### MI-17 【P2】SkillTag書込みAPIがメニュー権限フィルターの対象外で、権限撤回後も更新できる

- 対象:
  - **SkillTagApiController.java:21-75**
  - **MenuPermissionFilter.java:72-79**
  - **SecurityConfig.java:113-114**
- 現象:
  - /api/skill-tags はm_menuのapi_prefixに一致せず、filterは未一致APIを許可する。
  - POST/PUT/DELETEに個別ロール制限がないため、要員以外の全業務ロールが書き込める。
- 影響:
  - engineer/project権限を外されたユーザーでも、直接APIで共有スキルマスタを変更・削除できる。
- 改修方法:
  1. 読取りと書込みを分け、書込みを管理者/HR等の明示した管理ロールへ限定する。
  2. 読取りはengineer/project等の依存メニューのいずれかを持つユーザーに許可する。
  3. 共通マスタ専用メニュー権限を作る場合はUIとAPIを同じmenuKeyへ紐付ける。
- 期待効果:
  - 共有マスタの変更権限が明確になり、メニュー撤回を直接APIで迂回できない。

### MI-18 【P2】fetch系APIはセッション切れで200 HTMLをJSON解析し、ログイン画面へ戻れない

- 対象:
  - **SecurityConfig.java:116-123**
  - **src/main/resources/static/js/common.js:81-121,533-546**
- 現象:
  - 未認証APIはform loginへ302され、fetchは自動追従して200のlogin HTMLを受け取る。
  - SES.apiは401/403だけを特別扱いし、その後response.json()を呼ぶ。
  - 200 HTML検出はjQuery ajaxSetupにしかない。
- 影響:
  - fetch/SES.api使用モジュールはセッション失効時にJSON parse errorとなり、画面が止まる。
- 改修方法:
  1. /api/**専用AuthenticationEntryPointで401のApiResult JSONを返す。
  2. クライアント共通層で response.redirected、response.url、Content-TypeをJSON解析前に検査する。
  3. jQuery、fetch、SES.apiを同じセッション失効契約へ統一する。
- 期待効果:
  - どの通信方式でもセッション切れを同じように検出し、確実に再ログインへ誘導できる。

### MI-19 【P2】SES.apiが403/5xxをnullで解決し、呼出元が失敗を成功表示する

- 対象:
  - **src/main/resources/static/js/common.js:95-107**
  - **src/main/resources/templates/payroll/index.html:58-72**
- 現象:
  - SES.apiは403/5xxでToast後にnullをreturnし、Promiseをrejectしない。
  - freee link/unlink画面はawait後に結果を確認せず「連携しました/解除しました」を表示する。
- 影響:
  - 権限拒否やサーバーエラーでも成功Toastが出て、DB状態と画面認識が食い違う。
- 改修方法:
  1. 200以外またはApiResult code!=200は必ず例外としてrejectする。
  2. 共通層と画面層のどちらがToastを担当するかを統一し、二重表示を防ぐ。
  3. 403/500をmockし、成功Toastが出ずcatchへ入るテストを追加する。
- 期待効果:
  - 失敗操作が成功として案内されず、画面表示がサーバーの結果と一致する。

### MI-20 【P2】本番画面が空/エラー時に操作可能な偽データを表示する

- 対象:
  - **email-template.js:12-31,159-175**
  - **contract-gantt.js:5-31,98-103**
  - **ai-matching.js:129-146,198-225,262-267**
- 現象:
  - メールテンプレートはAPI失敗時にID 1/2のmock行を表示する。
  - Ganttは正常な空一覧でも架空契約を表示し、null日付も固定日付へ置換する。
  - AI matchingは失敗時にprojectId 101/105/112を表示し、「提案」操作まで許す。
- 影響:
  - 利用者が偽レコードを実データと判断する。
  - 偶然同じIDの実レコードへ編集/削除/提案APIが送られ、誤操作につながる。
- 改修方法:
  1. productionでは空状態とエラー状態を明示し、mockを一切表示しない。
  2. mockは独立したdev/testフラグ配下だけで有効にし、業務書込みボタンを無効化する。
  3. 空200、業務エラー、500、network errorを別々にテストする。
- 期待効果:
  - 架空データが業務データへ混入して見えず、偽IDによる誤更新も起きない。

### MI-21 【P2】契約・請求・Ganttが先頭100件しか取得せず、101件目以降が画面から消える

- 対象:
  - **ContractApiController.java:39-51**
  - **contract.js:24-45**
  - **contract-gantt.js:5-16**
  - **invoice.js:39-51**
- 現象:
  - 契約一覧/GanttはページングAPIの先頭ページだけを使い、ページャを描画しない。
  - 請求一覧は current=1&size=100 に固定される。
- 影響:
  - 101件目以降の契約/請求が検索・確認・操作できず、Ganttも全体を表さない。
- 改修方法:
  1. 一覧はcurrent/size/total/pagesを保持し、ページャを実装する。
  2. Ganttは表示期間で絞る専用APIを作るか、必要ページを明示的に取得する。
  3. 101件以上のfixtureで末尾レコードへ到達できることを検証する。
- 期待効果:
  - データ件数が増えても全レコードへ到達でき、一覧と集計/エクスポートの認識差がなくなる。

### MI-22 【P2】月次締め/ダッシュボードの深いリンクを遷移先JSが読まず、別条件を表示する

- 対象:
  - **monthly-closing.js:86,89,103,112,115**
  - **work-record.js:1-6**
  - **invoice.js:1-51**
  - **dashboard/index.html:66,159**
  - **engineer.js:8-40**
- 現象:
  - 月次締めは month、customerId、tab、invoiceIdをURLへ付けるが、勤怠/請求画面は初期化時にURLSearchParamsを反映しない。
  - Dashboardは status=Bench/退場予定 を付けるが、要員一覧はprefillCandidateId以外を読まない。
- 影響:
  - 「修正」「請求作成」「BP支払」「督促」「対象者一覧」を押しても、当月/全件/既定タブが表示される。
  - 利用者が誤月を確定・請求する危険がある。
- 改修方法:
  1. 初回APIロード前にURL queryを各フォーム/タブ/フィルターへ反映する。
  2. invoiceIdは対象行の強調または督促モーダル自動表示へ接続する。
  3. 月次締めとDashboardからのブラウザ遷移テストを追加する。
- 期待効果:
  - リンク元で選んだ月・顧客・タブ・対象レコード・statusが遷移先にも保持される。

### MI-23 【P2】案件モーダルの顧客placeholderがvalue=1で、非同期読込前の保存が顧客1へ誤登録する

- 対象:
  - **templates/project/list.html:118-121**
  - **project.js:3-6,97-112,240-255**
- 現象:
  - 初期optionの表示は「顧客を選択」だが value=1 である。
  - 顧客一覧のAJAX完了後は空valueへ置換されるものの、それ以前に保存するとcustomerId=1を送る。
- 影響:
  - 低速通信または素早い操作で、選択していない案件が顧客ID 1へ静かに帰属する。
- 改修方法:
  1. HTML初期optionを value="" にする。
  2. 顧客ロード完了までselect/保存ボタンをdisabledにし、失敗時も保存させない。
  3. バックエンドの既存 @NotNull 必須検証に加えて、顧客の存在とデータスコープを明示的に確認する。
- 期待効果:
  - 未選択が実顧客IDへ変換されず、案件と顧客の誤帰属を防げる。

### MI-24 【P2】freee連携を論理削除すると、DBの全体UNIQUEに阻まれて再連携できない

- 対象:
  - **FreeeEmployeeLink.java**
  - **FreeeIntegrationServiceImpl.java:84-111**
  - **V21__freee_payroll_integration.sql:7-11**
- 現象:
  - unlinkはMyBatis-Plusの論理削除だが、engineer_id/freee_employee_idのUNIQUEは削除済み行にも効く。
  - 次のlinkは削除行を検索できずINSERTし、duplicate keyになる。
- 影響:
  - 一度解除した要員を同じfreee従業員にも別従業員にも正常再連携できない。
- 改修方法:
  1. 履歴を残すなら、V18/V24と同様のactive generated keyで deleted_flag=0 の行だけを一意にする。
  2. 履歴不要ならunlinkを物理削除として明確化する。監査上は前者を推奨する。
  3. 同一再連携、従業員変更、要員変更、active重複拒否を実DBで検証する。
- 期待効果:
  - active一対一制約を維持しながら、解除履歴を残して再連携できる。

### MI-25 【P2】ユーザー論理削除とusername全体UNIQUEが衝突し、同名作成が事前検査を通って500になる

- 対象:
  - **V1__create_tables.sql:40-52**
  - **UserApiController.java:79-92,160-173**
- 現象:
  - 削除はdeleted_flag=1だが、username UNIQUEは削除済み行も対象。
  - 重複事前検査は論理削除行を自動除外するため「利用可」と判定し、INSERTで初めて失敗する。
- 影響:
  - ログインID再利用ポリシーが曖昧なまま、APIは想定した重複メッセージでなく500を返す。
- 改修方法:
  1. 永久再利用禁止なら、削除行を含む明示SQLで検査し409を返し、必要なら復元フローを用意する。
  2. 再利用可ならactive generated keyで deleted_flag=0 のみ一意にする。
  3. セキュリティ/監査要件としてどちらかを明文化する。
- 期待効果:
  - 削除後のログインID動作が決定的になり、DB例外ではなく業務応答になる。

### MI-26 【P2】ユーザーstatusに任意整数を保存でき、status=2で営業担当解除ガードを迂回できる

- 対象:
  - **UserApiController.java:145-157**
- 現象:
  - 担当営業の無効化ガードはstatus==0だけで動くが、入力値を0/1に限定しない。
  - status=2等はガードを通らず保存され、認証側のenabled判定では無効扱いになる。
- 影響:
  - ログイン不能な営業ユーザーにactive担当が残り、担当/契約帰属が孤立する。
- 改修方法:
  1. Booleanまたは0/1のallowlistを持つDTOへ変更する。
  2. 「現在1から1以外」への全遷移で担当解除ガードを実行する。
  3. DBにもCHECK(status IN (0,1))相当を追加する。
- 期待効果:
  - ユーザー状態が有効/無効の二値に固定され、無効化時の担当整合性チェックを迂回できない。

### MI-27 【P2】書込みEntityのBean ValidationとMySQL制約がずれ、通常の入力エラーがDB 500になる

- 対象例:
  - **Engineer.java:26-49 / EngineerApiController.java:116-120 / V1:84-94**
  - **SkillTag.java:37-46 / SkillTagApiController.java:51-64 / V1:144-148**
  - **Customer.java:43-45 / V1:64**
- 現象:
  - EngineerはemploymentType必須/ENUM、gender/status ENUMをAPI側で検証しない。
  - SkillTag.categoryは任意文字列を通すがDBはENUM。
  - Customer.contactEmailはAPIで255文字まで許すがDBはVARCHAR(100)。
- 影響:
  - Bean Validationを通過した要求がDBで失敗し500になる。
  - MySQL modeによっては不正ENUMや文字列が丸められ、静かなデータ劣化も起こり得る。
- 改修方法:
  1. 永続化Entityを直接受けず、書込みDTOで必須、長さ、列挙、桁数を定義する。
  2. 共通EnumMappings/定数とDB migrationの許可値を同期させる。
  3. DataIntegrityViolationExceptionの業務エラー変換は最後の防壁として残す。
- 期待効果:
  - 不正入力は安定した400で拒否され、DB例外や環境依存の切捨てが利用者へ露出しない。

### MI-28 【P2】Gemini chat promptが円の生値を「万円」と表示し、金額を1万倍に誤認させる

- 対象:
  - **AiRestController.java:36-54**
  - **V27__money_flow_consistency.sql**
- 現象:
  - DBのexpectedUnitPrice/unitPriceMin/unitPriceMaxは円だが、650000等の生値に「万円」を付ける。
- 影響:
  - Geminiは65万円を650000万円として受け取り、単価交渉・提案文・助言を誤る。
- 改修方法:
  1. 共通MoneyFormatterを使用し、円のまま「円/月」と表示するか、10000で割って「万円」に統一する。
  2. nullを「null万円」と連結せず未設定表示にする。
  3. mock HTTP clientでprompt本文を検査する。
- 期待効果:
  - AIが正しい経済条件を受け取り、単価に関する回答が1万倍ずれない。

---

## 5. P3 — 公開ルートとテスト基盤

### MI-29 【P3】公開されている要員/案件formルートが存在しないテンプレートを返して500になる

- 対象:
  - **EngineerPageController.java:22-27**
  - **ProjectPageController.java:22-27**
  - 不存在: **templates/engineer/form.html、templates/project/form.html**
- 現象:
  - /engineer/form と /project/form はマッピングされているが、返却先テンプレートがない。
- 改修方法:
  1. 遺留ルートを削除するか、一覧へredirectしてqueryで作成/編集modalを開く。
  2. 全PageControllerのview名が実テンプレートへ解決できるsmoke testを追加する。
- 期待効果:
  - 公開URLを直接開いてもTemplateInputException/500にならず、ルート契約が実画面と一致する。

### MI-30 【P3】H2/CIが本番・旧DB・active-only制約を再現せず、重大な移行不具合を緑で通す

- 対象:
  - **application-test.yml:11-31**
  - **schema-self-service-h2.sql:1-2**
  - **engineer-schema-h2.sql:309-324**
  - **FlywayMigrationSmokeTest.java:39-46**
  - **V18 / V24 / V32**
- 現象:
  - Flyway smokeは空DBのmain locationだけを対象にし、prod locationとlegacy baselineを試さない。
  - H2 schemaは最終ENUM（要員、提出済、差戻し）とactive-only生成キー制約を正確に模擬しない。
  - consolidated H2には旧UNIQUEが残る箇所があり、MySQLと再作成可否が逆になる。
- 影響:
  - MI-01/MI-02、論理削除後の再作成、active提案/担当/契約の一意性が通常テストで検出されない。
  - 今回も全量テストは成功したまま、本番Flyway重複が別確認で再現した。
- 改修方法:
  1. CIで実MySQLの三経路を必須化する: 空DB main、空DB main+prod、旧手動V9 baselineからのupgrade。
  2. H2特化migrationまたは一つの最終H2 schemaを管理し、ENUM/CHECKとactive-only一意性を同期する。
  3. 単なる列SELECTに加え、「active重複は失敗、論理削除/解放後は再作成成功」の振舞いテストを追加する。
- 期待効果:
  - 開発テストの緑が本番schema/制約の成功をより正確に意味し、移行と論理削除の回帰をリリース前に検知できる。

---

## 6. リスク・追加確認事項（確認済み30件には未算入）

### RISK-01 Java実行環境のCI差

- pom.xmlはJava 17を宣言する一方、GitHub ActionsはJava 21だけで実行する。
- release 17でコンパイルできても、実運用がJava 17ならランタイム差はCIで検証されない。
- 本番JDKを必須jobにし、必要なら17/21 matrixにする。

### RISK-02 実MySQL方言の未実行範囲

- 本機ではDockerが利用できず、FlywayMigrationSmokeTestがスキップされた。
- MI-01はFlyway resolverで直接再現し、MI-02は現行/旧schemaとmigration順序から確定できるが、それ以外のMySQL固有DDLはこの監査で実コンテナ実行していない。
- CI上ではDockerを必須にし、skipされた場合に成功扱いしない運用が必要。

---

## 7. 推奨改修順序

### 第1段階: デプロイと情報漏えいを止める

- MI-01、MI-02
- MI-03、MI-04、MI-05、MI-06

達成条件:

- main+prodとlegacy upgradeが実MySQLで完走する。
- 権限外通知/AI IDは取得不能、ai.enabled=falseでは外部HTTP 0件、XSS payloadは無害化される。

### 第2段階: 状態遷移と月次金額を一貫させる

- MI-07〜MI-12
- MI-13

達成条件:

- 同一提案/契約への並行終端要求は一方だけ成功する。
- 終了月、価格履歴、勤怠、請求、BP支払、コミッションが同じ期間/単価になる。
- ロールバックした設定/通知がキャッシュや外部Webhookへ残らない。

### 第3段階: 権限・API・画面契約を統一する

- MI-14〜MI-23

達成条件:

- role×menu×page×APIの組合せ試験が通る。
- エラー/空/セッション切れが偽成功や偽データにならない。
- 101件超とdeep linkを含む実運用操作が正しい対象へ到達する。

### 第4段階: 論理削除・入力契約・回帰ゲートを固める

- MI-24〜MI-30

達成条件:

- 論理削除後の再登録ポリシーが明確で、DB制約と一致する。
- 不正入力は400、公開ページは非500。
- H2とMySQLの重要な制約振舞いがCIで同じ結果になる。

## 8. 完了判定

この監査の「改修完了」は、単に各画面が開くことではなく、次を満たした時点とする。

1. P1の再現テストがすべて追加され、修正後に失敗しない。
2. main/prod/legacyのFlyway三経路が実MySQLで完走する。
3. 提案・契約の並行テストで一要求のみ成功する。
4. 営業データスコープON時、通知/AI/lookup APIを含む担当外IDが取得できない。
5. 契約期間・単価改定から勤怠、請求、BP、営業成績まで同一fixtureで金額が一致する。
6. 画面の可視性、動的メニュー、静的SecurityConfig、内部APIの権限がrole×menu試験で一致する。
