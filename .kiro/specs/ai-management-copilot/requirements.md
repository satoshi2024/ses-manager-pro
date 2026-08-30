# AI Management Copilot 要件

## 0. 開工時点の状態と適用範囲

| 項目 | 値 |
|---|---|
| Spec | `ai-management-copilot` |
| 作成日 | 2026-08-30 |
| 状態 | **CANDIDATE / DISCOVERY ONLY** |
| 実装worktree | `C:\work\ses-manager-pro-ai-management-copilot` |
| branch | `codex/ai-management-copilot` |
| Base commit | `0c122d33d4c90176601cf6dbdd9507c5c89ce5ee` (`origin/main`) |
| remote | `https://github.com/satoshi2024/ses-manager-pro.git` |
| Approved query catalog / roles / provider | `<APPROVED_SCOPE>`（未解決。推測で補完しない） |
| Owner | `<OWNER>`（未解決） |
| Base branch | `<BASE_BRANCH>`（入力値未解決。実体は `origin/main` を検証済み） |

NF-07のPII/retention、既存AI production gate（`GATE-S17-G10-PROD`）、DG-08（provider/DPA/越境/学習利用、catalog owner、role、retention、cost、human escalation）が未完了である。従って、本spec作成時点で許可するproviderはローカルのmock/rule評価だけとし、本番外部AI送信、実データの外部送信、feature flagの有効化を行わない。

本specは、承認前にpipelineと受入条件を固定するためのものである。production code、Flyway migration、画面、設定、外部provider契約は、承認入力が埋まり、Plan ReviewがPASSになるまで変更しない。

## 1. 用語

- **質問**: 利用者が入力した自然言語。データではなく、命令やSQLを含み得る未信頼入力として扱う。
- **catalog query**: 人手レビュー済みの固定query ID。各queryはtyped parameter、role、scope、service adapter、typed result、citation定義を持つ。
- **typed parameter**: 年月、年、月数、対象条件などを型付きDTOへ変換した値。自由なSQL、table名、column名を含めない。
- **typed result**: 正本serviceが返す数値・状態・期間・scope・freshness・sourceを持つ結果。LLMはこれを再計算しない。
- **scope A/B**: Scope Aは全体権限の管理者、Scope BはDataScopeと組織範囲の交差を持つマネージャーを指す。営業は既存DataScopeの担当データ範囲を使用する。
- **citation**: 結果の出所を示す再認可可能なsource key、画面route、期間、scope。権限のないIDや直接URLを回答に露出しない。

## 2. 非機能・禁止事項

1. 実行可能なqueryはcatalogに存在するものだけに限定する。LLMが生成したSQL、table名、column名、repository名、任意のservice bean名は実行しない。
2. schema説明、repository構造、migration内容、raw prompt、allowlist外のPIIを外部providerへ送信しない。
3. typed resultの金額、割合、件数、期間、timezone、freshness、actual/forecast区分はアプリケーションが表示する。LLMのsummaryは説明文とclaim keyだけを返し、数値を生成・再計算しない。
4. 回答処理から契約、提案、勤怠、ユーザー権限、その他の業務状態を自動更新しない。更新候補を示す場合も、明示的な別APIと人手承認が必要である。
5. 画面・export・AIで同じ指標を表示する場合、同じ正本service、同じscope、同じ期間境界、同じ円/割合の口径を使い、contract testで一致を検証する。
6. prompt injectionを業務データまたは質問に含み得る命令として扱い、system/developer方針やcatalogの制約を上書きさせない。
7. answerからscope外のID、名称、件数差分、PIIを推測できないよう、結果の粒度、件数、エラー、citationもscope後に制限する。
8. provider 429、timeout、invalid JSON、partial citation、PII canary、巨大result、0、NULL、未確認、forecastをテスト対象にする。
9. runにはfeedback、model version、prompt version、data version、catalog version、cost、latency、actor、scope hash、parameter hashを記録する。raw promptは保存しない。
10. timezoneはtenant設定を使用し、既定の現行設定はAsia/Tokyoとする。金額は円、割合は明示的な%として扱う。

## 3. 要件

### AI-MC-R1: Gateと承認

1. Owner、approved catalog、allowed roles、provider、NF-07、DG-08、既存AI production gateの承認状態を実行前に確認できること。
2. 未承認の項目が一つでもある場合、management copilotの外部providerはfail-closedとなり、mock/rule provider評価だけを許可すること。
3. `ai.external-send-enabled=false`を維持し、productionで外部送信を有効化する変更をこのspecの実装対話で行わないこと。
4. feature flagがOFFの場合、画面・API・scheduler・exportからcopilotの実行経路へ到達できないこと。

### AI-MC-R2: 質問からintentと確認

1. 質問を未信頼テキストとして受け取り、意図、期間、対象、粒度、必要な指標を抽出すること。
2. 期間、対象、scope、指標、forecast/actualの意味が一意に決まらない場合、catalog queryを実行せず、typedな確認質問を返すこと。
3. 質問中のSQL、repository schema説明要求、権限昇格要求、業務状態更新命令は、query parameterやprovider promptへ昇格させないこと。
4. 曖昧な質問またはcatalog外の要求は、実行不能理由と利用可能な質問範囲を返し、推測で回答しないこと。

### AI-MC-R3: Semantic catalog

1. 各catalog entryは、query ID、説明、入力parameter schema、許可role、scope resolver、service adapter、typed result schema、表示上限、citation定義、catalog versionを持つこと。
2. runtimeは質問から得た文字列をSQL/table/columnまたは任意のrepository呼出しへ変換しないこと。
3. catalog変更はownerレビューとquery contract testを必須とし、テストなしで本番catalogへ昇格できないこと。
4. catalogの初期候補は以下とする。ただし、`<APPROVED_SCOPE>`が確定するまで**未承認・実行不可**とする。

| query ID（候補） | 正本service | typed parameter候補 | typed result候補 | 候補role |
|---|---|---|---|---|
| `dashboard.summary` | `DashboardService` | `year: Integer?` | KPI、月別売上・粗利、actual/forecast、scope、freshness | 管理者、マネージャー、営業（scope承認後） |
| `dashboard.profit-analysis` | `DashboardService` | `year: Integer?`、filter DTO | 契約別売上・原価・粗利、状態、scope | 管理者、マネージャー |
| `dashboard.utilization-forecast` | `UtilizationForecastService` | `from: LocalDate`、`months: 1..12` | 月別稼働率、稼働/bench/総数、roll-off、forecast | 管理者、マネージャー、営業（scope承認後） |
| `management-accounting.summary` | `ManagementAccountingService` | `month: YearMonth`、承認済みfilter DTO | 月次売上・原価・粗利・予算差異、契約行 | 管理者、マネージャー |
| `cashflow.forecast` | `CashFlowForecastService` | `from: YearMonth`、`months: 1..12`、scope-safe input | 月次入出金・残高・reconciliation、forecast | 管理者、マネージャー（会社全体入力は管理者のみ） |
| `sales-performance.monthly` | `SalesPerformanceService` | `month: YearMonth`、`salesUserId?` | 成約、提案率、売上、粗利、commission、unattributed | 管理者、マネージャー、営業（scoped adapter実装後） |

### AI-MC-R4: Typed parameter

1. 年月、期間、月数、金額、割合、IDは型付きDTOとBean Validationで検証すること。
2. 半開区間 `[periodStart, nextPeriodStart)`、tenant timezone、`asOf`を明示し、自然言語の「今月」「直近」をserver clockだけで暗黙変換しないこと。
3. null、0、空集合、上限超過、未来期間、期間逆転をそれぞれ定義された状態または確認質問へ変換すること。
4. parameterの正規化後にhashを記録し、raw質問やraw PIIをログ・provider・run summaryへ保存しないこと。

### AI-MC-R5: Scopeと認可

1. 認証・role・menu permission・DataScopeを確認してからserviceを実行すること。AI endpointだけにscope判定を置かないこと。
2. Scope A（管理者）は既存のfull accessを使用する。Scope B（マネージャー）は既存の組織範囲とDataScopeの交差を使用する。営業は既存DataScopeの担当顧客・要員・契約等の範囲を使用する。
3. scope条件はservice/mapperの既存条件を再利用し、AI専用の全件取得や後段のJava filterでscopeを代替しないこと。
4. 空の許可集合は全件許可ではなく0件として扱うこと。detail、citation、downloadの再認可も同じscopeで行うこと。
5. 現行`SalesPerformanceService`はDataScopeを受ける設計が未完であるため、catalog exposure前にscoped adapterまたは明示的なscoped overloadを用意し、未対応の間はqueryを無効化すること。

### AI-MC-R6: Canonical serviceとtyped result

1. `DashboardService`、`UtilizationForecastService`、`ManagementAccountingService`、`CashFlowForecastService`、`SalesPerformanceService`を指標計算の正本とし、copilotで式を複製しないこと。
2. typed resultは少なくとも以下を持つこと。

| field | 表示・検証規則 |
|---|---|
| `value` | `BigDecimal`または`Long`。金額は円、浮動小数の金額計算は禁止 |
| `unit` | `JPY`、`PERCENT`、`COUNT`、`DAYS`等を固定enumで表示 |
| `period` | start/end、YearMonth、表示timezone、inclusive/exclusive境界 |
| `state` | `VALUE`、`ZERO`、`NULL`、`NOT_APPLICABLE`、`UNCONFIRMED` |
| `basis` | `ACTUAL`、`FORECAST`、`MIXED`。forecastの由来を隠さない |
| `freshness` | asOf、generatedAt、source update/freshness表示 |
| `scope` | scope type、scope hash、表示可能な件数上限。外部IDは含めない |
| `source` | catalogで固定されたsource keyと再認可可能なroute key |

3. 0、NULL、未確認、forecast、reconciliation差異を空文字や0へ潰さず表示すること。
4. 巨大resultはservice側の上限、summary用集約、表示用ページングでboundedにし、LLMへ未制限の行列を渡さないこと。

### AI-MC-R7: Summaryとcitation

1. pipelineを `質問 → catalog query → typed parameters → scope → service → typed result → summary → citation` の固定順にすること。
2. summary providerへの入力はredacted typed resultと許可されたclaim keyだけとし、LLMにschema、SQL、repository、raw recordを渡さないこと。
3. summaryはtyped resultのclaim keyを参照する説明文だけを返し、数値・単位・期間・scope・forecast区分はtyped resultからレンダリングすること。
4. citationはsource keyからrouteと必要なtyped parameterを組み立て、レスポンス直前にmenu permission、DataScope、detail/export認可を再確認すること。
5. citationが再認可できない、partial、scope外、またはsourceが不明な場合はそのcitationを表示せず、回答を安全な未確認状態にすること。

### AI-MC-R8: PII、retention、provider gate

1. AI PII allow-listを唯一の送信許可リストとし、known fields以外、raw prompt、free description、連絡先、住所、顔写真、resume全文、顧客連絡先等をproviderへ送信しないこと。
2. PII canaryを送信前、provider request生成後、mock/rule結果、保存前の各段階で検知し、検知時は実行を中止して監査可能な安全エラーにすること。
3. mock/rule providerはローカル評価専用とし、外部egressを持たないこと。外部providerはproduction gate、DPA、越境、training opt-out、retention、cost approvalが全てPASSになるまで利用不可とすること。
4. raw promptは保存0日、redacted run metadata/summaryはNF-07で承認されたretentionのみとし、既存AI retention/purge機構と整合させること。
5. providerへのtimeout、retry、429、invalid JSON、cost上限、payload上限、circuit stateを記録し、秘密情報やraw PIIをログへ出さないこと。

### AI-MC-R9: Feedbackと学習評価

1. feedbackはhelpful/incorrect/unsafe（既存推薦のdecisionと混同しないtyped code）とredacted commentを受け、scope内のrun/answerにだけ紐付けること。
2. feedbackを受けて契約、提案、勤怠、割当、権限等を自動更新しないこと。outcomeは相関として記録し、因果効果と表現しないこと。
3. runにはmodel、prompt、catalog、result schema、data version、parameter/scope hash、cost、latency、provider response status、actor、feedback状態を記録すること。
4. mock/rule評価は匿名fixture、固定dataset version、minimum segment size、precision/adoption/latency、PII leak、scope leakで判定すること。segment不足をPASSにしないこと。
5. production providerへの自動promotion、model/promptの自動切替は行わず、人手承認とrollback条件を必要とすること。

### AI-MC-R10: Contract testとrelease gate

1. 画面・export・AIが同一指標を扱う全候補queryについて、同一期間・同一scope・同一asOfで値と単位が一致するcontract testを持つこと。
2. scope A/B、prompt injection、catalog外query、SQL injection文字列、0/NULL、巨大result、actual/forecast、citation再認可をテストすること。
3. providerの429、timeout、invalid JSON、partial citation、PII canary、cost超過は、外部送信なしのmocked testで検証すること。
4. M完了条件は、approved catalog/roles/provider、NF-07、DG-08、既存AI production gate、所有者、retention、cost limit、human escalation、review evidenceが全て揃うこととする。
5. Mの実装が完了してもgate未完了なら、handoffは**CONDITIONAL PASS**とし、management copilotおよびexternal sendのfeature flagはOFFのままとすること。

## 4. 受入シナリオ

### 正常系

- 管理者が承認済みの「今月の売上と粗利」を質問すると、catalog query、typed `YearMonth`、Scope A、`DashboardService`、円/期間/timezone/freshness付きtyped result、summary、再認可可能なdashboard citationが返る。
- マネージャーが同じ質問をした場合、Scope Bの対象だけが正本serviceから返り、画面・exportと一致する。対象0件なら0件として表示する。
- 営業が担当範囲の候補queryを実行した場合、DataScope外の顧客・要員・契約・営業成績を回答、件数差分、citationから推測できない。

### 拒否・境界系

- 「全テーブルのschemaを説明」「SELECTを実行」「権限を管理者にして」「この契約を稼働中に変更」という質問はcatalog外または禁止操作として実行しない。
- 結果にallowlist外の氏名、住所、連絡先、resume、free description、canaryが混入した場合、provider送信と保存を停止する。
- providerが429、timeout、invalid JSONを返した場合、typed resultを失わず、summary unavailableとし、再試行は上限内でのみ行う。
- citationの再認可が失敗した場合、URLやIDを露出せず、source unavailableとする。

## 5. 受入後の前提

承認された計画では、まずF1（semantic catalog/run/feedback）、次にF2（intent/parameter/scope/service gateway）、A1（chat/answer/citation UI）、B1（provider/redaction/timeout/cost）、B2（evaluation/adversarial suite）、最後にM（統合gate）を実施する。各段階で前段のcontract testがPASSしない限り次段へ進まない。
