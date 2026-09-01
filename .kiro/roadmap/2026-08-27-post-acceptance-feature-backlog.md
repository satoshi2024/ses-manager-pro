# 受入後プロダクト拡張バックログ 2026

- 作成日: 2026-08-27
- 対象: SES Manager Pro の受入完了後に検討する追加機能
- 状態: **候補整理済み・未承認・未着手**
- 目的: 既存機能を重複実装せず、次の投資判断、spec化、実装、独立Reviewへ安全に引き渡す
- 実装対話: `2026-08-27-post-acceptance-start-conversations.md`
- 独立Review対話: `2026-08-27-post-acceptance-review-conversations.md`
- 要件・設計基線: `2026-08-27-post-acceptance-requirements-design.md`
- 対応表・判断記録: `2026-08-27-post-acceptance-traceability.md`

> 本書は候補ロードマップであり、Java/HTML/JS/SQLの実装許可ではない。採用する候補だけを
> `.kiro/specs/<feature-name>/requirements.md`、`design.md`、`tasks.md`へ展開し、開始条件を満たした後に実装する。
> Migration番号は本書で予約しない。実装開始時にmerge済みの全Flyway locationを確認し、当時の`latest + 1`を採番する。

## 1. 結論

現行システムは、一般的なSES管理の中核に加え、契約更新カレンダー、将来稼働率予測、承認、法定文書台帳、
BPコンプライアンス、顧客/BPポータル、勤怠、会計連携、AI評価まで既に持つ。したがって次の開発は、同じ機能を
別画面で作り直すのではなく、次の3つの空白を埋めるべきである。

1. **利用定着**: 入退社、顧客フォロー、教育、モバイル利用を業務フローとして閉じる。
2. **企業連携**: 汎用Import、公開API、Webhook、定期レポートで手作業を減らす。
3. **データ統治**: 個人情報の保存期限、本人請求、削除/匿名化、AI利用境界を証跡付きで管理する。

最初の投資候補は次の3件とする。

| 順位 | 候補 | 理由 | 最小MVP |
|---|---|---|---|
| 1 | NF-01 入社・配属・退社ワークフロー | アカウント、端末、契約、教育、担当変更の漏れを横断的に防ぐ | テンプレート、案件、Task、期限、証跡、退社時アクセス遮断確認 |
| 2 | NF-02 カスタマーサクセス・問い合わせ/SLA | 更新率、顧客満足、クレーム、未解決課題を契約更新へ接続できる | 問い合わせ、重要度、SLA、担当、顧客ヘルス、更新カレンダー連携 |
| 3 | NF-06 データ移行・一括取込センター | 新規導入と既存顧客移行の工数を直接削減する | 顧客・案件・契約のpreview/validate/apply/rollback/error CSV |

## 2. 既存能力との重複確認

次は「これから追加する案」ではなく、既にspecと実装が存在する。新規候補はこれらを再利用または拡張し、
別の並行基盤を作らない。

| 能力 | 既存spec/主な実装 | 現状判断 | 新候補での扱い |
|---|---|---|---|
| 契約更新と期限エスカレーション | `contract-renewal-calendar`、`RenewalCalendarService`、`RenewalEscalationService` | tasks全完了 | NF-02の顧客ヘルス・問い合わせを更新判断材料としてリンクするだけ |
| 将来稼働率/Bench予測 | `utilization-forecast`、`UtilizationForecastService` | tasks全完了 | NF-03の研修計画、NF-08の自然言語分析がread-only参照 |
| 統一承認 | `approval-workflow-internal-control`、`ApprovalEngineServiceImpl` | tasks全完了 | 新しい承認対象は既存`ApprovalTargetAdapter`へ追加し、第二エンジンを作らない |
| 法定文書台帳 | `legal-document-ledger-archive`、`DocumentService`、`DocumentStorage` | tasks全完了 | NF-01/NF-02/NF-07/NF-09の証憑を既存文書へlinkする |
| BP/フリーランス管理 | `bp-company-master-procurement-compliance` | tasks全完了 | 新しいBP masterや法令自動判定は作らない |
| 顧客/BPポータル | `external-customer-bp-portal` | tasks全完了 | NF-02/NF-04は既存portal security chain/DTO境界を拡張する |
| 勤怠・休暇・時間外 | `attendance-leave-overtime-compliance` | tasks全完了 | NF-04は入力UXと安全な再送だけを担当し、計算ロジックを複製しない |
| 会計・支払連携 | `accounting-payment-integration`、`payment-reconciliation` | tasks全完了 | NF-05は既存canonical DTO/job/idempotencyを公開連携へ再利用 |
| AI推薦評価 | `ai-feedback-learning`、`AiExecutionGateway` | tasks全完了。ただし本番gateは別管理 | NF-08は同じPII allow-list、version、run、feedback/outcomeを使用 |
| CSV | `EngineerCsvService`、予算CSV等 | 部分的に存在 | NF-06は既存個別Importを壊さず、複数entity移行を統括する |

## 3. 候補一覧

### NF-01 入社・配属・退社ワークフロー

#### 顧客価値

- 入社前準備、初日、配属、待機、異動、休職、退社をチェックリストではなく追跡可能な業務案件として扱う。
- アカウント、端末、契約、担当営業、組織、権限、文書、教育の「誰が・いつまでに・何をしたか」を残す。
- 退社後のアカウント、portal session、外部連携token、貸与物の残存を検出する。

#### MVP

- ライフサイクルテンプレート: `入社`、`配属`、`異動`、`休職`、`復職`、`退社`。
- テンプレートから案件とTaskを生成し、担当者、期限、必須証跡、依存Taskを管理する。
- Task状態は`未着手→進行中→完了`、例外は`保留/取消`。完了後の直接編集を禁止し、訂正eventを追記する。
- 退社案件はユーザー無効化、active session失効、担当引継ぎ、貸与物返却、未精算、文書保存を確認する。
- `Engineer`、`SysUser`、`Organization`、`EngineerSales`、`DocumentLink`へ参照linkを持つ。
- 期限超過通知と管理者/HR向け未完了一覧を提供する。

#### 非目標

- 給与計算エンジンの再実装。
- IdP/端末管理製品を直接代替すること。
- Task完了だけで外部システムの実際の無効化を保証すること。連携がない場合は証跡と二者確認を要求する。

#### KPI

- 入社初日の未完了必須Task件数。
- 退社時刻から全アクセス遮断確認までの中央値/95 percentile。
- 期限超過率、差戻し率、証跡欠落率。

### NF-02 カスタマーサクセス・問い合わせ/SLA・顧客ヘルス

#### 顧客価値

- 契約更新の直前だけでなく、日常の問い合わせ、クレーム、定例会、満足度、未解決課題を蓄積する。
- 顧客ヘルスを「担当者の感覚」ではなく、問い合わせ遅延、稼働問題、請求遅延、満足度、更新意向から説明可能にする。

#### MVP

- 問い合わせ/課題: 顧客、契約、案件、要員、起票経路、重要度、担当、期限、status、カテゴリ。
- status: `受付→対応中→顧客確認待ち→解決→終了`。再openは履歴を残す。
- SLA: 初回応答期限と解決目標。営業時間カレンダーは既存calendarを再利用し、単純な24時間加算にしない。
- 顧客portalから起票・返信・添付。内部メモと外部公開コメントをDTOで明確に分ける。
- CSAT（解決後）と定例会/QBR記録。匿名公開URLではなくportal認証済み利用者だけが回答する。
- 顧客ヘルスは各要因を表示するrule-based score。法的/契約的判断や自動解約判定はしない。
- 更新カレンダーに未解決P0/P1、直近CSAT、ヘルス変化を表示する。

#### 非目標

- 汎用ITSM製品の全機能。
- チャットボットが自動で問い合わせを解決済みにすること。
- AIの感情分析だけで顧客を危険判定すること。

#### KPI

- 初回応答SLA達成率、解決SLA達成率。
- 再open率、未解決滞留日数、CSAT回答率/平均。
- 更新率とヘルス要因の相関。ただし因果と断定しない。

### NF-03 資格・研修・スキルギャップ計画

#### 顧客価値

- 現在スキルだけでなく、案件需要に対して不足するスキル、資格期限、学習計画、育成成果を管理する。
- Bench期間を単なる待機ではなく、次案件へ向けた計画期間へ変える。

#### MVP

- 資格master、取得記録、有効期限、証憑文書link、更新通知。
- 研修コース、受講計画、実績、費用、承認、修了証。
- `staffing-capacity-planning`の需要skillと`EngineerSkill`を比較したskill gap候補。
- 本人/上長が目標skill、期限、到達基準を合意し、変更履歴を保持する。
- AI推薦は候補提示のみ。本人の不利益処分や評価確定には使用しない。

#### KPI

- 期限切れ資格数、更新期限内完了率。
- Bench期間中の研修計画設定率。
- 重点skillの供給不足数、研修後の案件提案/成約率。

### NF-04 モバイル/PWA・オフライン安全入力

#### 顧客価値

- 要員がスマートフォンで勤怠、経費、通知、変更申請を完結できる。
- 通信不安定時も入力を失わず、復帰時の二重登録を防ぐ。

#### MVP

- 既存`/my/**`をresponsive PWA shellへ適合し、install manifest、service worker、更新通知を提供する。
- offlineで保存するのは入力途中の最小データだけ。給与、銀行、マイナンバー、文書本文、他人のPIIはcacheしない。
- 更新要求ごとにclient request IDを発行し、再送時もserver側で冪等にする。
- offline queueはユーザー、画面、対象月、payload hash、作成時刻を保持し、別ユーザーlogin時に送信しない。
- 競合時はserver値とdraft差分を表示し、人が再適用する。last-write-winsにしない。
- push通知は別gate。MVPはアプリ内通知とbadgeでよい。

#### KPI

- モバイルで完了した勤怠/経費/変更申請の割合。
- draft消失件数、二重登録件数、同期競合件数。
- 390px viewportでの主要フロー完了率。

### NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

#### 顧客価値

- 顧客ごとの個別SQL/CSV改修を減らし、周辺システムと安全に接続する。
- 連携失敗を画面で追跡・再送できる。

#### MVP

- 最初はread-only API（要員availability、案件、契約status、請求status）と限定command APIから始める。
- OAuth2 client credentialsまたは署名付きservice account。平文API keyの恒久運用を既定にしない。
- client単位scope、組織/法人/data scope、rate limit、IP allow-list（任意）、鍵rotation、失効。
- OpenAPI契約、versioning、cursor pagination、Idempotency-Key、Correlation-ID。
- Outbound webhookは既存outboxを再利用。署名、timestamp、replay防止、retry/backoff、dead-letter、手動再送。
- Inbound webhookはprovider event IDのunique制約、署名検証、raw body hash、処理状態を保持する。

#### 非目標

- 内部entityのそのまま公開。
- 管理画面と同等の万能更新API。
- 同期HTTP transaction内で外部APIを呼ぶこと。

#### KPI

- 個別CSV作業時間、API成功率、p95 latency。
- webhook重複適用件数、dead-letter滞留時間、再送成功率。

### NF-06 データ移行・一括取込センター

#### 顧客価値

- 新規導入時の顧客、案件、契約、提案、担当、過去実績の移行を再現可能にする。
- 「一部だけ登録され、どこまで入ったか分からない」を防ぐ。

#### MVP

- Import job状態: `UPLOADED→MAPPED→VALIDATED→READY→APPLYING→COMPLETED/FAILED/ROLLED_BACK`。
- entity別schema version、template download、列mapping、encoding/date/amount preview。
- validateはDBを書き換えず、行エラーとcross-rowエラーを返す。
- applyはjob idempotency、chunk、checkpointを持ち、同じjobの二重applyを拒否する。
- 顧客→案件→契約などの自然キー参照を一時IDで解決する。
- rollback可能範囲をjob開始前に明示する。後続業務で参照されたrowは自動削除せず補償計画へ回す。
- エラーCSV、reconciliation report、件数/金額/hash、実行者、base snapshotを保存する。

#### KPI

- 1,000/10,000行のvalidate/apply時間。
- 手動修正回数、再実行回数、reconciliation差異件数。
- 部分適用の未検知件数を0にする。

### NF-07 個人情報・保存期限・本人請求ガバナンス

#### 顧客価値

- 応募者、要員、portal利用者、AI入力、文書に散在する個人情報を把握し、保存期限と削除/匿名化を統制する。
- 本人からの開示、訂正、利用停止等の依頼を案件として追跡する。

#### MVP

- PII inventory: table/column/file/AI field、目的、法的/契約上の根拠、owner、保存期間、削除方法。
- retention policyは対象種別、起算event、期間、legal hold、処分方式をversion付きで管理する。
- 処分候補のdry-run、件数/対象期間、阻害理由を表示し、承認後に匿名化/削除する。
- 本人請求case: 本人確認、対象検索、export、訂正、制限、完了、期限、監査証跡。
- audit/security/法定文書の保存義務と競合する場合はfail-closedし、理由を表示する。
- AI送信allow-listとretentionを`ai-feedback-learning`の基線に統合する。

#### 非目標

- システムが法的判断を自動確定すること。
- audit logや法定保存文書を依頼だけで物理削除すること。

#### KPI

- owner/retention未設定PII項目数。
- 期限超過データ件数、legal hold誤削除件数。
- 本人請求の期限内完了率。

### NF-08 AI経営コパイロット・自然言語分析

#### 顧客価値

- 「来月BenchになるJava要員」「粗利低下の理由」などを自然言語で質問し、根拠データへ遷移できる。
- 定例報告の下書きを作るが、業務状態を自動更新しない。

#### MVP

- LLMに任意SQLを生成・実行させない。承認済みsemantic query catalogとparameter schemaだけを使用する。
- 回答は集計値、対象期間、timezone、scope、データ更新時刻、根拠画面linkを表示する。
- 金額は円、割合、件数の型をDTOで固定し、prompt文字列から再計算しない。
- 0件、NULL、締め未確定、予測値を明示的に区別する。
- prompt/outputのPII allow-list、redaction、model/version、latency、cost、feedbackを記録する。
- exportや個票表示は既存のauthorization serviceを再判定し、回答本文からscope外IDを推測できないようにする。

#### KPI

- 回答根拠link到達率、ユーザー評価、回答不能率。
- scope漏えい0件、金額不一致0件、幻覚指標の報告件数。
- 定例資料作成時間の短縮。

#### 実装・Review入口（SNF01〜10横断時の正本）

| 用途 | パス |
|---|---|
| spec package | `.kiro/specs/ai-management-copilot/` |
| 開工対話（F1〜M） | `.kiro/specs/ai-management-copilot/start-conversations.md` |
| 独立Review対話 | `.kiro/specs/ai-management-copilot/review-conversations.md` |
| 中央要約（実装） | `2026-08-27-post-acceptance-start-conversations.md` §S-NF08 |
| 中央要約（Review） | `2026-08-27-post-acceptance-review-conversations.md` §R-NF08 |

具体AIモデルは未決定。先に catalog→正本service→typed result の deterministic core を構築し、summary のみ `AiTextService` で差し替える。

### NF-09 貸与資産・アカウント・ライセンス管理

#### 顧客価値

- PC、スマートフォン、入館証、SIM、ソフトウェアライセンス、外部アカウントの貸与と返却を追跡する。
- 退社/異動時の残存アクセスと未返却資産をNF-01へ接続する。

#### MVP

- 資産master、serial/asset tag、種別、所有法人、状態、保管場所、保証期限。
- 貸与履歴は上書きせず、貸与/移管/返却/紛失/廃棄eventを保持する。
- account/licenseは秘密値を保存せず、system名、external ID、owner、権限区分、失効確認だけを保持する。
- 返却証跡、紛失incident、棚卸し、期限超過通知。
- NF-01退社caseを閉じる前に未返却/未失効をblockまたは承認例外にする。

#### KPI

- 未返却資産、退社後active account、棚卸し差異。
- 資産割当の追跡不能件数、保証期限超過件数。

### NF-10 定期レポート・QBR/取締役会パック

#### 顧客価値

- Dashboardを毎回手でExcelへ転記せず、同じ口径の月次/週次報告を自動生成する。
- 生成時点のsnapshotと根拠を残し、後日の再計算差異を説明できる。

#### MVP

- レポートtemplate、対象期間、timezone、受信者、format、schedule、scope owner。
- 生成時に売上、粗利、稼働、Bench、更新、AR、BP支払、問い合わせSLA等をsnapshot化する。
- PDF/XLSX/CSVのうち必要なformatだけを段階導入する。文書生成物は`DocumentService`へ登録する。
- schedule実行はsystem principalと明示的scopeを使用し、実行ユーザーsessionへ依存しない。
- メール添付を既定にせず、期限付きportal/document linkを通知する。
- 再生成、失敗再実行、version差分、recipient preview、配布監査を提供する。

#### KPI

- 月次報告作成時間、再計算差異件数、配布失敗率。
- レポート閲覧率、手動Excel転記回数。

## 4. 優先順位とWave

### Wave A — 運用漏れと導入障壁を減らす

1. NF-01 入社・配属・退社ワークフロー
2. NF-02 カスタマーサクセス・問い合わせ/SLA
3. NF-06 データ移行・一括取込センター
4. NF-07 個人情報・保存期限・本人請求

NF-01とNF-07は`SysUser`、`Engineer`、文書、監査へ触れるため、同時に共通fileを編集しない。NF-02とNF-06は
インターフェース固定後に並行可能だが、Migration採番と`m_menu`は中央担当が管理する。

### Wave B — 利用定着と人材価値を高める

1. NF-03 資格・研修・スキルギャップ
2. NF-04 モバイル/PWA
3. NF-10 定期レポート

NF-04は勤怠計算を変更しないためNF-03と並行可能。NF-10はNF-02のSLA指標を含める場合、NF-02 PASS後に開始する。

### Wave C — 外部接続

1. NF-05 Integration Hub
2. NF-09 資産・アカウント・ライセンス

NF-05はsecurity、outbox、外部DTO、rate limitへ広く触れるため単独Waveを推奨する。NF-09はNF-01とのlink契約を固定後に開始する。

### Wave D — AI差別化

1. NF-08 AI経営コパイロット

NF-08はNF-07のPII inventory/retention、既存AI本番gate、semantic query catalog、正本となる各集計serviceが揃うまで本番有効化しない。

## 5. 投資判断スコア

スコアは1（低）〜5（高）。Cost/Riskは高いほど負担が大きい。

| 候補 | Revenue/Retention | 工数削減 | Risk低減 | Cost | Risk | 推奨 |
|---|---:|---:|---:|---:|---:|---|
| NF-01 | 3 | 5 | 5 | 3 | 3 | 最優先 |
| NF-02 | 5 | 4 | 4 | 4 | 4 | 最優先 |
| NF-03 | 4 | 3 | 3 | 3 | 3 | 高 |
| NF-04 | 3 | 5 | 3 | 4 | 4 | 高 |
| NF-05 | 4 | 5 | 4 | 5 | 5 | 条件付き高 |
| NF-06 | 4 | 5 | 4 | 4 | 4 | 最優先 |
| NF-07 | 2 | 3 | 5 | 4 | 5 | 必須候補 |
| NF-08 | 4 | 4 | 2 | 5 | 5 | 基盤後 |
| NF-09 | 2 | 4 | 5 | 3 | 3 | 中 |
| NF-10 | 3 | 4 | 3 | 3 | 3 | 高 |

## 6. 全候補共通の非機能要件

1. **認可母集団**: list/detail/count/export/download/notification/schedulerで同じ母集団を使用する。
2. **API**: `ApiResult<T>`、validation、CSRF、監査、安定したerror codeを維持する。
3. **状態変更**: terminal state、許可遷移、CAS/楽観ロック、冪等key、transaction境界をdesignで固定する。
4. **DB**: V1、増分Flyway、H2 replay、`engineer-schema-h2.sql`、entity、MySQL smokeを同一taskで同期する。
5. **Migration**: 公開済みmigrationを変更しない。欠番を埋めない。開始時点のlatest+1を採番する。
6. **外部I/O**: 外部呼出しをDB transaction内で行わない。timeout、retry/backoff、rate limit、correlation ID、障害復旧を持つ。
7. **ファイル**: path traversal禁止、content検査、size上限、未知/scan失敗fail-closed、scope付きstream download。
8. **PII/秘密**: password、token、API key、銀行、マイナンバー、添付本文をlogへ出さない。
9. **i18n**: 現行bundle全てへ同じkeyを追加する。日本語文言を正とし、欠落fallbackをテストする。
10. **時刻**: 保存は`Instant`/UTC、業務日付は設定timezoneで導出し、日付境界をtestする。
11. **金額**: DB値は円。丸め、税込/税抜、NULL/0、確定/予測をDTOで区別する。
12. **性能**: 無制限全件取得禁止。pagination、batch fetch、必要なindex、最大件数時の性能証拠を持つ。
13. **監査**: actor、scope、target、before/afterまたはsnapshot hash、correlation ID、結果を記録する。
14. **Demo**: 権限成功だけでなく、403、競合、再送、外部失敗、0件、mobile、復旧を含める。

## 7. 「開工対話」と「Review対話」を混ぜない

### 開工対話の責務

- 通常checkoutから分離した専用Codex worktreeと`codex/<feature-name>` branchで作業する。
- 通常checkout、他feature worktree、他branchの未commit変更を触らない。
- specを読み、開始条件を確認し、必要なコード/SQL/画面/test/文書を変更する。
- `tasks.md`を順番に実行し、testとDemoを満たしたtaskだけ`[x]`にする。
- 完了Taskごとに対象変更だけをcommitし、remote feature branchへpushする。
- 変更範囲、base/head、実行test、未検証、rollbackをreview ledgerへ残す。
- blockerや仕様判断が必要なら実装を止め、選択肢と影響を報告する。
- 実装対話ではPRを作らず、最終remote Headを独立Reviewへ引き渡す。

### Review対話の責務

- 開工対話と別の新規対話、通常checkout/実装worktreeとも別の専用Review worktreeで、原則read-onlyで実施する。
- push済みremote feature branchの固定HeadだけをReviewし、Review途中にHeadが変わった場合は対象を再確定する。
- 最初にapproved scope、Decision Gate、requirements、design、tasks、ledgerのPlan Reviewを行い、計画の未完・矛盾・
  scope creepがないことを`PLAN PASS`として確定する。
- 実装者の説明やcheckboxを証拠として信用せず、requirements、diff、test assertion、Demo証拠を読む。
- 指摘はP0/P1/P2、`file:line`、再現条件、影響、最小修正範囲で返す。
- Review中に修正しない。修正は開工対話へ返し、同じReview対話でdeltaを再Reviewする。
- `PLAN PASS`後にImplementation Reviewを行い、双方PASS後だけ`gh`でfeature branchからbase branchへのPRを
  自動作成または更新し、次Waveを解放する。
- PRのmerge、auto-merge、remote branch削除は別の明示依頼がない限り行わない。

## 8. 採用時の手順

1. 本書の候補から1件を選ぶ。
2. `2026-08-27-post-acceptance-traceability.md`のDecision欄へ採用理由、owner、KPI、期限を記録する。
3. `.kiro/specs/<feature-name>/requirements.md`、`design.md`、`tasks.md`を作る。
4. 現行コードとmigrationを再inventoryし、既存との差分をspecへ反映する。
5. 法務/セキュリティ/外部契約のgateを解決する。
6. start conversationを新しい実装対話へコピーする。
7. 新しいCodex worktreeと`codex/<feature-name>` branchで実装し、Taskごとにcommit/pushする。
8. 実装完了後、別の新規Review worktree対話へreview conversationをコピーする。
9. Review PASS時にPRを自動作成/更新する。mergeは人または別の明示承認に委ねる。
10. PR mergeとrelease gate完了後に本番へ有効化する。

## 9. 完了の定義

候補機能は次を全て満たした場合だけ「完了」とする。

- 全requirements IDが実装、自動test、Demoへtraceされている。
- fast/MySQL/performanceの必要gateがskip 0である。
- security/data scope/CSRF/audit/競合/冪等/rollbackの否定系が実証されている。
- UIはdesktopと390pxで主要フローを完了できる。
- 運用runbook、監視指標、バックアップ/復元、feature flag/rollbackがある。
- 独立ReviewがPASSし、未検証事項がrelease blockerかpost-release follow-upか分類されている。
- 専用feature branchの全commitがremoteへpushされ、Review済みHeadとremote Headが一致している。
- Review PASS後のPR URL/numberが記録され、Review未完のbranchにPRが誤作成されていない。
- `.kiro/specs/README.md`と対象review ledgerが更新されている。
