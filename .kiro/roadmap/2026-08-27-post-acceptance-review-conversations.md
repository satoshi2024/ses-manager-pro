# 受入後機能候補 — 独立Review対話

> この文書は**独立Review専用**である。実装対話と必ず分離した新規対話で使用する。
> Review AIは原則read-onlyであり、コード、spec、checkbox、ledgerを変更しない。
> 指摘修正は元の開工対話へ返し、同じReview対話で修正diffを再Reviewする。
> **Reviewも通常checkoutおよび実装worktreeと分離した専用Codex worktreeで行う。Review PASS後、
> Review済みremote HeadをheadとしてPRを自動作成または更新する。PRの自動mergeは行わない。**

## 0. Worktree・remote branch・PRフロー

1. 実装対話が`codex/<feature-name>`を`origin`へpushし、base branch/base SHA/remote Head SHA/test証拠を引き渡す。
2. 新しいReview Taskを**別のCodex worktree**で作成する。通常checkout`C:\work\ses-manager-pro`、実装worktree、
   他feature worktreeをReviewに使用しない。
3. Review worktreeはremote feature branchの固定SHAから開始し、branchを作る必要がある場合は
   `codex/review-<feature-name>`とする。このReview branchはpushしない。
4. `git fetch origin --prune`後、`origin/codex/<feature-name>`が引き渡された`<HEAD_COMMIT>`と一致することを確認する。
   一致しなければ新Headを勝手にReviewせず、対象SHAの再確定を要求する。
5. FAIL/P0/P1の場合はPRを作らず、findingを元の実装対話へ返す。実装対話は同じfeature branchで修正commitをpushする。
6. 再Reviewでは新しいremote Headを明示する。同じReview worktreeを再利用する場合、review branchは
   `git merge --ff-only origin/codex/<feature-name>`でのみ前進させる。非fast-forwardなら履歴改変として停止する。
7. PASS時はPR作成直前に再度fetchし、review済みHeadとremote Headが一致し、worktreeにsource変更がないことを確認する。
8. `gh auth status`で認証を確認し、remote default branchまたは明示`<BASE_BRANCH>`をbase、
   `codex/<feature-name>`をheadとしてPRを自動作成する。既存open PRがあれば重複作成せず本文/タイトルを更新する。
9. PR本文にはsummary、requirements/tasks、migration、test結果、Review判定、未検証/release gate、risk、rollbackを記載する。
10. ReviewはPR URL/numberを報告して完了する。PRのmerge、auto-merge設定、branch削除は明示依頼がない限り行わない。

## 0.1 Review順序 — Plan Review Gateを先に行う

Reviewは必ず次の順で行い、Stage AがPASSする前にPR作成へ進まない。

### Stage A — Plan Review

1. `APPROVED`時点のscope、Decision Gate、KPI、owner、base branch/base SHAを特定する。
2. requirements/design/tasks/review ledger/decision log間の矛盾、未決定事項、scope creepを確認する。
3. 全TaskについてObjective、requirements ID、実装、test、Demo、rollback、完了checkboxの根拠を対応付ける。
4. 実装中のplan変更が正式にspecへ反映・承認されているか確認する。口頭説明やcommitだけでplan変更を認めない。
5. 未完Task、未解決Decision Gate、trace欠落、未承認scope追加があれば`PLAN FAIL`とし、PRを作らず実装対話へ返す。
6. 計画が完遂され、差分が説明可能なら`PLAN PASS`としてStage Bへ進む。

### Stage B — Implementation Review

1. Stage Aで確定したrequirements/design/tasksを基準に、実diff、test assertion、Demo、migration、運用証拠をReviewする。
2. P0/P1がなく、必要gateがskip 0、remote Head一致なら`IMPLEMENTATION PASS`とする。
3. `PLAN PASS`かつ`IMPLEMENTATION PASS`の場合だけ総合`PASS`とし、PRを自動作成/更新する。
4. どちらかがFAIL、またはCONDITIONAL PASSの場合はPRを作成しない。

## 1. 共通Review契約

1. `<BASE_COMMIT>`と`<HEAD_COMMIT>`を指定する。分離不能なら推測してReviewせず、必要情報を報告する。
2. Review開始前に`git rev-parse --show-toplevel`、`git worktree list --porcelain`、`git branch --show-current`、
   `git status --short`、`git remote -v`、`git rev-parse origin/codex/<feature-name>`を確認する。
3. repository rootが通常checkout`C:\work\ses-manager-pro`または実装worktreeなら、Reviewせず専用Review worktreeを要求する。
4. AGENTS.md、requirements、design、tasks、review ledger、実diffを読む。実装者の説明や`[x]`だけを証拠にしない。
5. Review中はsource/spec/checkbox/ledgerを変更しない。test実行などのread-only検証と、PASS後のPR作成/更新だけが許可される。
6. 自動testは名前ではなくassertion、fixture、失敗条件、対象scope、実行結果を確認する。
7. 既存worktreeの対象外変更をfindingへ混ぜず、対象diffとの帰属を明記する。
8. 同じHeadで有効な全量証拠がある場合、理由なく再実行しない。共有境界変更、証拠Head不一致、失敗疑義があれば必要gateを実行する。
9. 指摘はP0/P1/P2で、finding ID、requirement、`file:line`、再現条件、影響、最小修正、必要回帰を記載する。
10. 修正提案は最小範囲とし、無関係refactor、新機能、好みの書き換えを要求しない。
11. PASS前にrequirements→implementation→test→Demoのtrace、migration、scope、競合、復旧、未検証を確認する。
12. `PLAN PASS`前、`CONDITIONAL PASS`または`FAIL`ではPRを自動作成しない。PLAN/IMPLEMENTATION双方PASS、
   P0/P1=0、必要gate skip 0、remote Head一致の総合`PASS`だけPR作成条件とする。
13. Review結論は`PASS / CONDITIONAL PASS / FAIL`、PR作成結果、release/次Wave開始可否を分けて示す。

### PR自動作成の安全条件

- `git rev-parse HEAD == git rev-parse origin/codex/<feature-name> == <HEAD_COMMIT>`。
- Review worktreeの`git status --short`にReviewが作ったsource/spec変更がない。
- Plan Review GateがPASSし、approved scope/requirements/design/tasks/decision/ledgerのtraceが完結している。
- P0/P1が0、required testにfailure/error/skipが0、Review判定がPASS。
- `gh auth status`成功、`origin`のrepository owner/nameとbase branchが確認済み。
- `gh pr list --state open --head codex/<feature-name>`で既存PRを検索済み。
- 新規なら`gh pr create --base <BASE_BRANCH> --head codex/<feature-name> ...`、既存なら`gh pr edit`。
- PR titleはrepositoryの日本語commit/文書方針に合わせる。bodyに秘密、token、顧客PII、未redact logを含めない。

## 2. 共通横断観点

- API/page/CSRF/menu/action permission/data/organization/portal/external client scope。
- list/detail/count/export/download/notification/schedulerの同一母集団。
- 状態遷移、terminal state、CAS/optimistic lock、DB unique、lock order、二重click/再送。
- V1/Flyway/H2 replay/engineer-schema/entity/MySQL、既存data migration、rollback/compensation。
- transaction中の外部I/O、timeout、retry/backoff、rate、correlation ID、outbox、DLQ、manual replay。
- PII/secret/log、file content/path/scan、external DTO allow-list、CSV/formula injection。
- UTC/business timezone、月末、NULL/0、円、丸め、forecast/confirmed。
- N+1、pagination、最大件数、scheduler multi-node、cache invalidation。
- i18n bundle、390px、keyboard/accessibility、loading/empty/error/403/session expiry。
- monitor、alert、runbook、feature flag、backup/restore、release gate。

## R-NF01 — `engineer-lifecycle-workflow` Review

```text
これは `engineer-lifecycle-workflow` 専用の独立Review対話です。Review中はfileを変更しません。
Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。不明またはdiff分離不能ならReviewを停止して必要情報を報告してください。

専用Review worktreeを使用し、実装remote branchは`origin/codex/engineer-lifecycle-workflow`、
base branchは<BASE_BRANCH>です。通常checkout/実装worktreeでReviewしません。PASS時だけremote Head一致を再確認し、
`codex/engineer-lifecycle-workflow`からbaseへのPRを`gh`で自動作成または更新してください。mergeはしません。
最初にapproved scope/Decision Gate/requirements/design/tasks/ledgerのPlan Reviewを行い、PLAN PASS後だけコードReviewへ進みます。
PLAN PASSとIMPLEMENTATION PASSの両方が揃った総合PASSの場合だけPRを作成します。

最初にAGENTS.md、受入後backlog/requirements-design/traceability（NF-01/DG-01）、対象specの
requirements/design/tasks/review-ledger、approval/identity/self-service/documentの関連spec、Base..Head diffを完全に読んでください。

重点Review:
1. template versionがactive caseへ混入せず、循環dependency/担当不明時に部分生成されないか。
2. case/task状態遷移、完了訂正、CAS、二重complete、notification dedupeが原子的か。
3. 退社gateがuser/session/portal/担当引継ぎ/未精算/資産等の採用scopeを漏らさないか。
4. 例外承認が既存ApprovalEngineと職務分離を使用し、申請者単独完了や期限なし例外を許さないか。
5. 本人/HR/担当/管理者のlist/detail/count/export/notification母集団と内部Task非公開。
6. evidence file/document scope、PII、監査、履歴の改変耐性。
7. 退社途中の障害、再実行、rollback/compensation、復旧runbook。

独立再現候補:
- 同一engineer退社case並行作成、Task二重完了、case完了直前のblocker追加。
- template更新後に旧case閲覧、担当user無効化、organization変更。
- 本人が内部security Task IDを直接指定、scope外HRが取得。

出力:
- P0/P1/P2 findings。
- Requirements→実装→test→Demo→判定表。
- 退社gate/security/state/migration/recovery判定。
- 未検証とrelease blocker。
- PASS/CONDITIONAL PASS/FAIL、次Wave開始可否、ledger転記用短文。
```

## R-NF02 — `customer-success-service-desk` Review

```text
これは `customer-success-service-desk` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、受入後NF-02/DG-02、対象spec/ledger、portal/renewal/notification/document/calendar関連spec、diffを読んでください。

専用Review worktreeで`origin/codex/customer-success-service-desk`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけ同branchからbaseへのPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- INTERNAL/PORTAL_VISIBLEがDB query、DTO、serialization、search、export、notification、添付で分離されているか。
- customer/contract/project/request membershipがportal A/BでSQL境界から強制されるか。
- SLAがbusiness calendar/timezone/pause/resume/reopen roundを正しく扱い、過去結果を上書きしないか。
- scheduler multi-node、breach前/発生/継続のdedupe、stale claim、失敗再送。
- CSAT一意性、回答者membership、QBR action期限、health factorの説明性。
- healthが契約更新や顧客statusを自動変更せず、missing inputを安全に扱うか。
- 更新カレンダー連携にN+1、scope差、古いcacheがないか。

独立test候補:
- 休日/営業時間境界、pause中期限、reopen round 2、同一通知並行。
- portal AがBのrequest/comment/attachment/count/exportへアクセス。
- 内部comment文言をsearch/API/error/log/notificationで探索。

P0/P1/P2、trace表、security/SLA/state/performance/migration判定、未検証、総合判定、release可否を出してください。
```

## R-NF03 — `certification-learning-skill-gap` Review

```text
これは `certification-learning-skill-gap` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、受入後NF-03/DG-03、対象spec/ledger、engineer-skill-career、staffing、approval、document、diffを読んでください。

専用Review worktreeで`origin/codex/certification-learning-skill-gap`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- 資格masterと既存skill taxonomyの重複、資格履歴/取消/訂正、有効期限境界。
- 証憑DocumentLinkのscope/file安全性、資格番号のlist/export/log/AI漏えい。
- 研修費用承認、自己承認、締め/会計連携、円単位/NULL/0。
- skill gapがas-ofの要員skillと需要期間を比較し、未知/同義skillを説明するか。
- AI候補と人の確定が分離され、AI停止時もrule-based機能が残るか。
- 本人/上長/HR/managerの母集団、本人評価の不利益利用リスク、監査。
- 期限schedulerのdedupe、timezone、退職/休職者除外。

資格期限90/60/30、同時更新、証憑scope、費用閾値±1、as-of境界、AI timeoutを独立確認してください。
findings、trace、横断判定、未検証、PASS/CONDITIONAL PASS/FAILを出してください。
```

## R-NF04 — `mobile-pwa-self-service` Review

```text
これは `mobile-pwa-self-service` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、受入後NF-04/DG-04、対象spec/ledger、self-service/attendance/frontend hardening、diffを読んでください。

専用Review worktreeで`origin/codex/mobile-pwa-self-service`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- service worker cache routeがAPI/portal/document/payroll/PIIをcacheしないか。Cache Storageを実測する。
- logout/session expiry/user switch時にdraft/queue/cacheが隔離・削除されるか。
- client request ID、payload hash、base version、server idempotencyがUIだけでなくDB/transactionで効くか。
- offline→online再送、二重click、browser再起動、SW更新、stale draft、409 conflictがデータを重複/上書きしないか。
- 既存勤怠/経費計算、CSRF、monthly close、approvalを迂回していないか。
- 390px、keyboard、focus、offline/error/session expiry表示、添付制限。
- IndexedDB/localStorageへsecret/給与/銀行/他人PIIが残らないか。

実BrowserでA logout→B login、offline入力→復帰、server側競合、SW version updateを再現してください。
P0/P1/P2、cache/data-flow図、trace、未検証端末、総合判定を出してください。
```

## R-NF05 — `integration-hub-public-api` Review

```text
これは `integration-hub-public-api` の独立セキュリティReviewです。fileは変更しません。
Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。AGENTS.md、NF-05/DG-05、対象spec/ledger、identity/outbox/accounting/data-scope/audit、
OpenAPI契約、external field inventory、diffを完全に読んでください。

専用Review worktreeで`origin/codex/integration-hub-public-api`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、merge/auto-mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にsecurity/code Review、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
1. dedicated principal/filter chain、credential encryption/hash/rotation/revoke/expiry、client scope/data scope。
2. external DTO allow-list。entity serialization、internal ID/原価/PII/error stackの露出。
3. cursor paginationの安定性、rate limitの分散整合、IP/proxy trust、correlation/audit。
4. Idempotency-Key同一/別payload、並行command、response replay、retention。
5. webhook HMAC/署名対象、timestamp tolerance、replay、event unique、raw body hash。
6. outbox claim、外部call transaction外、timeout、backoff、DLQ、manual replay、stale processing。
7. OpenAPIと実装/DTO/HTTP status/error codeのcontract一致、version互換。
8. secret/PII log、metrics label cardinality、payload retention。

独立攻撃/障害test:
- client A credentialでB scope、revoked key、rotation境界、rate並行突破、spoofed X-Forwarded-For。
- same idempotency key/different body、duplicate webhook、署名改ざん、古いtimestamp、worker crash after external success。
- error/search/count/cursorからscope外存在推測。

P0/P1/P2をfile:line付きで出し、脅威→control→test表、external field判定、migration/recovery/performance、
本番secret/provider未検証、PASS/CONDITIONAL PASS/FAILと公開可否を示してください。
```

## R-NF06 — `data-migration-import-center` Review

```text
これは `data-migration-import-center` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、NF-06/DG-06、対象spec/ledger、既存CSV/ingestion/domain service/document/batch、source fixtures、diffを読んでください。

専用Review worktreeで`origin/codex/data-migration-import-center`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- validateが本当にno-writeか。domain validationをmapper直insertで迂回していないか。
- source hash/mapping version/schema version/idempotencyと同時apply制御。
- chunk transaction、checkpoint、crash window、再開時の二重insert/update/audit/notification。
- 自然キー衝突、cross-row参照、顧客→案件→契約順、upsert/insert-onlyの承認境界。
- rollbackが後続参照、soft delete、audit、会計/契約副作用を壊さず、不可の場合に補償へ移るか。
- reconciliationの式: source=accepted+rejected、accepted=applied+updated+skipped等が全経路で成立するか。
- CSV/XLSX parser安全性、encoding、quoted newline、formula、zip bomb/size、error CSVのPII。
- 10,000行でmemory/N+1/transaction lock/timeoutが許容か。

workerをcheckpoint直前/直後に失敗させた独立test、二重apply、same file/different mapping、rollback blockを確認してください。
findings、row lifecycle/recovery表、trace、件数/金額照合、総合判定を出してください。
```

## R-NF07 — `privacy-retention-dsar` Review

```text
これは `privacy-retention-dsar` の独立privacy/security Reviewです。Review中は一切fileを変更しません。
Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。AGENTS.md、NF-07/DG-07、対象spec/ledger、document retention、backup、audit、
identity、recruiting、AI PII allow-list、法務decision evidence、diffを完全に読んでください。

専用Review worktreeで`origin/codex/privacy-retention-dsar`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、merge/auto-mergeしません。
最初にPlan Review（approved policy/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にprivacy/code Review、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- PII inventoryの対象漏れ、owner/purpose/trigger/policy version、unknownのfail-closed。
- legal hold、法定保存、audit、active business、backup/replicaへの処分方針と優先順位。
- dry-run no-write、approval、二者分離、batch claim/idempotency、部分失敗、result evidence。
- delete/logic delete/anonymize/restrict/exportの意味が対象別に正しいか。
- DSAR本人確認、同姓同名resolution、第三者redaction、scope、delivery、期限、reopen。
- 処分後も法定文書/会計/契約の参照整合が保たれ、復元で削除済みPIIが無断復活しないか。
- AI egress/log/cache/file/index/export等の二次複製がinventory/retentionに含まれるか。
- production feature flag、法務owner、runbook、emergency stop。

独立test候補:
- hold作成と処分claimの競合、policy version境界、同一item再送、途中失敗。
- 同姓同名2人、第三者混在文書、scope外provider、backup restore後のtombstone再適用。

法的結論は出さず、実装が承認済みpolicyを正しく強制するかをReviewしてください。P0/P1/P2、
data lifecycle表、未確定法務gate、PASS/CONDITIONAL PASS/FAIL、本番処分有効化可否を出してください。
```

## R-NF08 — `ai-management-copilot` Review

```text
これは `ai-management-copilot` の独立AI safety/data scope Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
file変更は禁止です。AGENTS.md、NF-08/DG-08、対象spec/ledger、AI allow-list/gateway/evaluation、
semantic catalog、各正本集計service、data scope、provider契約gate、diffを読んでください。

専用Review worktreeで`origin/codex/ai-management-copilot`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved use case/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にAI/code Review、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- LLM生成SQL/table/column/service名の任意実行経路が存在しないか。
- intent→catalog→typed parameter→scope→service→typed result→summary→citationの各境界。
- 画面/export/AI値の口径、円/割合/期間/timezone/freshness/confirmed/forecast/NULL/0。
- source linkと個票再認可、回答文/error/log/run/feedbackからscope外ID/PIIの推測。
- prompt injection、DB本文のinstruction化、巨大result、token/cost limit、provider retention/越境gate。
- model/prompt/catalog/data version、latency/cost、feedback/outcome、回答再現性。
- AI回答が業務状態を自動更新せず、command候補が確認/承認境界を通るか。
- mock/rule/real providerのfeature flagと本番gate。

独立test:
- tenant/scope A/B、catalog外質問、SQL injection風質問、文書内prompt injection。
- 0/NULL/forecast、同じ指標の画面/export/AI contract一致。
- 429/timeout/invalid JSON/partial citation、PII canary egress/log scan。

P0/P1/P2、query catalog coverage、metric一致表、PII/provider gate、総合判定と本番AI有効化可否を出してください。
```

## R-NF09 — `asset-account-license-lifecycle` Review

```text
これは `asset-account-license-lifecycle` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、NF-09/DG-09、対象spec/ledger、NF-01、identity、organization、document、provider integration、diffを読んでください。

専用Review worktreeで`origin/codex/asset-account-license-lifecycle`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved scope/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- asset tag/serial/owner法人/state、貸与期間重複、event履歴、soft deleteの意味。
- 並行貸与/返却/移管/棚卸し時のlock order、unique/CAS、二重event。
- account reference/licenseにpassword/token/recovery codeが保存/返却/logされないこと。
- provider revoke requestとconfirmed result、timeout/unknown、retry/idempotency。
- NF-01退社blocker、例外承認、担当変更、退社後active account/未返却asset。
- 棚卸しexpected/observed/reconciliation、紛失incident、document evidence scope。
- owner法人/organization/data scope、list/detail/export/notification一致。

並行貸与、返却直後再貸与、license上限±1、退社中provider timeout、secret scanを独立確認してください。
P0/P1/P2、asset state/期間表、退社gate、provider/recovery、総合判定を出してください。
```

## R-NF10 — `scheduled-management-reporting` Review

```text
これは `scheduled-management-reporting` の独立read-only Reviewです。Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。
AGENTS.md、NF-10/DG-10、対象spec/ledger、各正本集計service、document、notification outbox、backup、diffを読んでください。

専用Review worktreeで`origin/codex/scheduled-management-reporting`の固定HeadをReviewします。通常checkout/実装worktreeは禁止です。
Base branch=<BASE_BRANCH>。PASSかつremote Head一致時だけPRを`gh`で自動作成/更新し、mergeしません。
最初にPlan Review（approved report/Decision Gate/requirements/design/tasks/ledger）を行い、PLAN PASS後にコードReview、
PLAN/IMPLEMENTATION双方PASS後だけPR作成へ進んでください。

重点Review:
- report sectionが正本service/DTOを使い、独自SQL/式を複製していないか。
- cutoff/timezone/freshness、速報/確定、予測/実績、NULL/0、円/割合の表示。
- template versionとsnapshot不変性。現在DBやtemplate変更で過去runが変わらないか。
- scheduler system principal/scope、multi-node claim、再実行、section部分失敗、stale run。
- recipient preview、scope、期限付きlink、download再認可、誤配布取消。
- document hash/version/restore、delivery outbox/retry/DLQ/manual replay、監査。
- PDF/XLSX/CSV injection、PII、最大row/ファイルsize、memory。

月末/timezone、同時scheduler、過去run再表示、recipient scope変更、delivery失敗を独立再現してください。
画面/export/reportの指標一致表、P0/P1/P2、snapshot/delivery/recovery判定、総合判定を出してください。
```

## 3. Review最終出力フォーマット

```text
Review result: PASS / CONDITIONAL PASS / FAIL
Plan Review result: PLAN PASS / PLAN FAIL
Implementation Review result: IMPLEMENTATION PASS / IMPLEMENTATION FAIL / NOT STARTED
Feature:
Review worktree path / review branch:
Base branch / Base commit:
Feature remote branch / Reviewed remote Head:
Review scope:

Plan conformance:
| Approved item | Plan/spec evidence | Completion evidence | Verdict |

Findings:
1. [P0/P1/P2] <title>
   - Requirement:
   - Evidence: <file:line>
   - Reproduction:
   - Impact:
   - Minimum fix:
   - Required regression:

Traceability:
| Requirement | Implementation | Test assertion | Demo evidence | Verdict |

Cross-cutting verdict:
- Security/scope/CSRF:
- State/concurrency/idempotency:
- Migration/H2/MySQL:
- PII/file/audit:
- External failure/recovery:
- Performance:
- UI/i18n/mobile:

Tests independently executed:
- command / result / skipped / evidence Head

Unverified/release gates:
Next Wave allowed: YES / NO
PR action: CREATED / UPDATED / NOT CREATED
PR URL / number:
PR merge: NOT PERFORMED
Ledger short conclusion:
```

## 4. 修正後の再Review対話

```text
前回ReviewのOPEN findingsだけを、実装remote branch `origin/codex/<feature-name>`へpushされた修正commit
<FIX_COMMIT> と前回Head <OLD_HEAD>..新Head <NEW_HEAD> のdiffで再Reviewしてください。
Review中はfileを変更しません。

最初に`git fetch origin --prune`し、remote feature branchのHeadが<NEW_HEAD>と一致することを確認してください。
同じ専用Review worktreeを使う場合はreview branchをfast-forwardだけで前進させ、非fast-forwardなら停止してください。
通常checkoutまたは実装worktreeへ移動しないでください。

各findingについて:
1. 根本原因が修正されたか。
2. 最小修正範囲か、回帰/新しいscope漏れを作っていないか。
3. 指定regression testが失敗条件をassertしているか。
4. 新しいP0/P1/P2が修正diffにあるか。
5. 全量gate再実行が必要か。同じHeadの証拠を再利用できるか、その理由。

修正diffがrequirements/design/tasks/Decision Gate/approved scopeを変更している場合は、コードReviewの前にPlan Review Gateを
再実行してください。承認されていないplan変更または未完TaskがあればPLAN FAILとし、PRを作成しません。

全findingが閉じてPASSになった場合だけ、remote Head一致とworktree cleanを再確認し、`gh`で既存PRを検索した上で
feature branchからbase branchへのPRを自動作成または更新してください。merge/auto-mergeはしません。

出力は findingごとの VERIFIED_CLOSED / PARTIAL / OPEN / REGRESSED、新規finding、総合判定、release可否、
PR action/URL/number、ledger転記文としてください。
```
