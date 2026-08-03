# Implementation Plan — AI推薦フィードバック・評価ループ

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T109〜T114はL0〜L3の定向test・直接回帰、T115でL4全量を実行する。
> provider/PII/evaluationの対象fixtureをTask単位で行い、全adapter・全量安全性回帰はMへ集約する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> AI固有のPII規約は `CLAUDE.md`「AI機能開発時の注意事項（A8-01/A8-02関連）」も併せて正とする。
> 時間/scope/状態/PII境界の判断は `design.md` §5「決定表」を正とする。
>
> **Migration**: 本specの予約番号は **V88**。Wave 2完了後に着手する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [ ] 0. G10/use case/PII/metric確定
  - **Objective**: 外部AIへ送ってよいfieldのallowlist・mask規則・保存期間・成功metric・
    matchingに使ってはならない属性が確定する。以降のgateway実装が送信可否を推測せずに済む状態にする。
  - **成果物**: provider DPA、field allowlist、mask規則、保存期間、成功metric、禁止属性。
  - **Demo**: security/HR/product owner承認。
  - **実装ガイダンス**: production codeを変更しない。
    **G10/DPA/PII方針が確定するまで実データを外部AIへ送らない**（前提節）。mock/rule providerを既定として維持する。
    **禁止属性リストは`design.md`§5.2で確定済み。本taskで決め直さない。**
    本taskが決めるのは、その禁止リストと突き合わせる**送信field allowlist**と、
    provider別のDPA・保存期間・region・opt-outである。
  - **テスト要件**: L0。allowlistの全fieldに送信可否と根拠が付いていること、
    **allowlistが`design.md`§5.2の禁止属性と1件も交差しないこと**、
    保存期間がprovider別に定義されていること、`git diff --check` exit 0。

- [ ] F1. version/run/item/feedback/outcome/evaluation DDL
  - **Objective**: AIのmodel/prompt/rule versionが登録でき、推薦の実行・候補・採否・成果が
    同一traceで追跡できる。use case×tenantでactive versionが常に1つに保たれる。
  - **実装ガイダンス**: **V88**/V1/H2(`sql/schema-ai-feedback-h2.sql`)/MySQL smoke、legacy移行方針。
    **active versionは部分UNIQUE制約で1つに保証**（design §5.3）。
    アプリ側の「既存ACTIVEをRETIREDにしてから新規をACTIVE」は競合で2件ACTIVEになる。同一transaction＋CASで行う。
    `UNIQUE(item_id, outcome_type, source_type, source_id)`でoutcomeを冪等化。
    既存`t_ai_log`はlegacy raw logとして段階移行し、**新規のraw prompt保存を止める**（design §1）。
  - **テスト要件**: L1〜L3。**active一意（同時promotionで1つだけ成功）**、trace貫通、
    tenant分離、保存期限超過データのpurge、outcome重複登録の拒否。
  - **Demo**: 2つのversionを同時にACTIVEへ昇格させて片方が失敗することを確認。

- [ ] F2. AiExecutionGateway/PII mask
  - **Objective**: すべてのAI呼出がgateway経由になり、controller/serviceが直接providerを呼ばない。
    送信payloadに氏名・連絡先・住所・口座・自由記述PIIが含まれない。
    取込原文の中の指示文がAIへの命令として実行されない。
  - **実装ガイダンス**: 全AI呼出をgatewayへ、schema validation、raw prompt停止。
    **取込原文はuntrusted dataとして分離**し、tool/action権限を与えない（R4.3、design §5.4）。
    model responseはJSON schema検証し、**HTMLとしてrenderしない**（design §2）。
    `@ConditionalOnExpression`でprovider切替時もAI系Beanが一意に解決されること（CLAUDE.md）。
  - **テスト要件**: L2〜L3。**PII canary（canary文字列がprovider request・ログ・DB summaryのいずれにも出ないこと）**、
    **prompt injection fixture（原文中の命令が実行されないこと）**、provider error、
    log capture、gatewayを経由しないAI呼出が存在しないこと。
  - **Demo**: 送信payload inspectionでPII 0。命令文を含む取込原文を投入して指示が実行されないことを確認。

- [ ] B1. feedback/outcome連携
  - **Objective**: 推薦ごとに採用/却下/保留と理由を登録でき、
    提案作成→面談→成約/失注が同一traceで追跡できる。同じeventが再送されても二重登録されない。
  - **実装ガイダンス**: matching画面の採否、proposal/contract event、冪等trace。
    trace IDを提案draft/提案へ保存し、後続state eventからoutcome handlerが冪等登録する（design §2）。
    **AIが業務状態を自動変更しない**（前提節）。feedback/outcome登録以外の経路を作らない。
    `feedback IS NULL`（未判断）を却下として集計しない（design §5.1）。
  - **テスト要件**: L2〜L3。採用/却下/面談/成約/失注の各outcome、
    **重複eventで1件のみ登録**、未判断が却下として集計されないこと、
    AIから提案/契約/メール送信を変更する経路が存在しないこと。
  - **Demo**: 推薦から成約までtimeline。同じstate eventを2回流してoutcomeが1件のみを確認。

- [ ] B2. offline evaluation/version promotion
  - **Objective**: 固定の匿名datasetで新versionと現行versionを比較でき、
    基準未達のversionは有効化が拒否される。rollback後は新規実行だけが旧versionを使い、過去記録は変わらない。
  - **実装ガイダンス**: dataset version、baseline比較、threshold、shadow/rollback。
    **自動promotion禁止**（R3.2、design §3）。metric閾値合格は必要条件で、管理者承認を必須とする。
    **shadowは結果保存可だがユーザー表示/業務作成に使わない**（design §3）。
    anonymized datasetはrepoにPIIなしfixture、実データ由来はaccess restricted storage。
  - **テスト要件**: L2〜L3。metric計算、**基準未達versionのpromotion拒否**、
    rollbackの即時反映、**過去のrun記録がversion切替後も不変であること**、
    shadow versionの結果が業務作成へ流れないこと。
  - **Demo**: 基準未達version拒否→ruleへrollback。rollback後に過去recordのversion参照が変わらないことを確認。

- [ ] A1. evaluation dashboard
  - **Objective**: version別の採用率・面談率・成約率・理由分布・latency・costが見え、2versionを比較できる。
    少数のsegmentは非表示になり、個人が特定されない。
  - **実装ガイダンス**: funnel/reason/latency/cost/segment privacy。
    **low-volume segmentは最低件数configで非表示**（design §4/§5.2）。
    sample inspectionはfield maskを適用し、生のprompt/PIIを画面へ出さない。
    cost/token metricsは管理者のみ（design §5.2）。
  - **テスト要件**: L2〜L3。scope、**最低件数未満のsegmentが非表示**、金額単位、
    sample inspectionでPIIが出ないこと、機微属性がsegment軸に現れないこと。
  - **Demo**: 2version比較。1件しか回答のないsegmentが表示されないことを確認。

- [ ] M. 回帰/安全性
  - **Objective**: mock既定のまま既存のAI機能が動き、実providerはopt-inでのみ有効になる。
    PII canaryがどこにも出ない。既存のmatching/proposal draft機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    mock/rule/Gemini各adapterでBeanが一意に解決されること、**PII scan全経路**、
    既存`GeminiMatchingServiceImpl`/`ProposalDraftService`の回帰、
    Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: mock既定の既存機能回帰と実provider opt-in。全provider設定でアプリが起動することを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    G10/DPA未確定のまま実データを外部providerへ送らない。**本番releaseのgate**として別管理する。
