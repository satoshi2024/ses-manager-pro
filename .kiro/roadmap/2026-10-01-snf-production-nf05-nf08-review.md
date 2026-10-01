# NF05 / NF08 Production Readiness 独立Review

- Reviewer: `astra_review_baseline` / gpt-6-astra
- 固定Head: `8ad0c34eacbb04a031667934f313a2b6965583f1`（初期base `1443fb68` と同一tree）
- 方法: read-only静的Review。試験実行なし。以下は総括による日本語転記。

| 対象 | PLAN | IMPLEMENTATION | PRODUCTION |
|---|---|---|---|
| NF05 | 承認済み開発scope PASS | CONDITIONAL PASS、新規P2 1件 | BLOCKED |
| NF08 | CONDITIONAL PASS維持 | CONDITIONAL PASS、新規P2 3件 | BLOCKED |

新規P0/P1は確認していない。既存の未承認provider・Privacy・production gateは継続する。

## NF05-R-P2-01 総deadline

`LoopbackIntegrationHubWebhookTransport.java:50,106,120,141` と
`IntegrationHubExternalApiProperties.java:70`。SO_TIMEOUTは各readの待ち時間のみで、
少量のheader/bodyを継続送信するとconnectTimeout + readTimeoutやleaseを超過できる。
monotonic総deadlineと残予算をconnect/read/write/response処理へ適用し、不要なbodyを無制限にdrainしない。
lease generation/token CASを維持する。許可portのslow-drip fixtureと期限切れ旧workerのcommit拒否を検証する。
これは開発LOOPBACKのfindingであり、実production transportが存在するという判定ではない。

## NF08-R-P2-01 自由文の数値

`CopilotSummaryValidator.java:12,27` は連続ASCII数字2桁以上のみ拒否し、単桁・小数・Unicode数字を許す。
typed result以外で数値を生成しない契約に合わせ、単数字、小数、負数、指数、Unicode数字、通貨を拒否する。
合法claim keyと組み合わせた拒否、typed result維持、数値を含まない正常説明の受理を検証する。

## NF08-R-P2-02 deadline/cost実行契約

`CopilotSummaryService.java:74-85,110-136` / `SummaryRequest.java:13-14`。
15秒deadline/budgetを作るがgatewayへ渡さず、実行時にも消費しない。
`AppConfig.java:89` の別HTTP read timeout 60秒では契約を満たさない。
実際のdeadline/cancellationと予算不足時の呼出拒否を実装する。mock/ruleの明示0円と実providerの未知costを区別する。
自発的にtimeoutを投げないblocking fakeで有限時間内のunavailableとtyped result維持を確認する。

## NF08-R-P2-03 runのsummary結果・固定identity

`CopilotQueryService.java:104-114` / `CopilotRunService.java:115-117` /
`CopilotSummaryService.java:65-93,131`。
query直後SUCCEEDED/latency=0を保存し、summaryはpersistRun(false)で結果を書き戻さない。
ACTIVE artifactの再取得によりqueryとsummaryのmodelがずれる可能性もある。
query/summary結果を分離し、artifact/provider/model/promptを実行開始時に固定する。
trace/run IDとCASでbounded outcome、latency、attempt、実costまたはUNKNOWNを保存する。
raw prompt/provider bodyは保存しない。成功/429/timeout/invalid JSON/reject/artifact切替を検証する。

## 非findingと残る照合

- NF05 snapshotの固定membership/公開値はrequirementsの明示契約であり、翻頁時の再照会不足とは扱わない。
  productionで短期の履歴可視性を許す正式policyは別gateである。
- Gemini旧model fallbackやapiUrlとartifactの不一致は実provider有効化前のpin/検証課題。
  現行mock scopeの新規P1とはしない。新modelの適合性・契約を本Reviewで承認していない。
- AI learning既存5件のP2はledger OPEN。gateway transaction除去、segment描画、evaluation admin制限に
  修復の兆候があるが、ambient transaction、90日窓、人的差分、旧ログ、全入口を未照合。
  VERIFIED_CLOSEDにも重複した新findingにも変更しない。
- NF05実transport、NF08 disabled catalog、DPA/NF07/retention/費用/production署名は未完了。
  過去試験のPASSは今回固定Headの動的検証結果ではない。
