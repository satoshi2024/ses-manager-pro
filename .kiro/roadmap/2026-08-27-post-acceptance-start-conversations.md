# 受入後機能候補 — 開工（主実装AI）対話

> この文書の対話は**実装専用**である。独立Reviewには使用しない。
> 対象候補が`APPROVED`でない場合、AIはread-only discoveryとspec作成までで停止し、production codeを変更しない。
> Reviewは必ず別の新規対話で`2026-08-27-post-acceptance-review-conversations.md`を使用する。
> **全ての開工は専用Codex worktree＋専用remote branchで行う。通常checkout
> `C:\work\ses-manager-pro`を実装作業に使用してはならない。**

## 1. 使い方

1. `2026-08-27-post-acceptance-traceability.md`で対象featureを`APPROVED`にし、Owner/KPI/Decisionを埋める。
2. Codexで新しい実装Taskを**worktree環境**として作成する。保存済みprojectがGit repositoryの場合はlocal環境を選ばない。
3. 下の対象feature対話を新しい実装対話へコピーする。
4. `<APPROVED_SCOPE>`、`<BASE_BRANCH>`、`<BASE_COMMIT>`、`<OWNER>`、`<TARGET_DATE>`を分かる範囲で置換する。
5. AIは最初にworktree/branch/remoteを検証し、その後spec packageを作成/更新して開始条件を確認する。
6. 実装は`tasks.md`順。1回に完了扱いにするのは原則1Taskだけとし、完了Taskごとにcommit/pushする。
7. 全TaskとM gate完了後、最終commitをpushし、remote Headを固定して別の新規Review worktree対話を開始する。
8. Reviewは最初に計画（approved scope、requirements、design、tasks、Decision Gate、実装台帳）の完遂性を確認し、
   その後にコード/SQL/test/DemoをReviewする。両方がPASSした後だけPRを作成する。

## 2. 全開工対話に適用する実装契約

- AGENTS.mdと指定された`.kiro`文書を完全に読む。
- 実装開始前に`git rev-parse --show-toplevel`、`git worktree list --porcelain`、`git branch --show-current`、
  `git status --short`、`git remote -v`を確認する。repository rootが通常checkout
  `C:\work\ses-manager-pro`なら、production fileを変更せず「専用worktreeが必要」と報告して停止する。
- 1 feature = 1専用worktree = 1専用branchとする。branch名は`codex/<feature-name>`を既定とし、別featureと共有しない。
- `git fetch origin --prune`後、明示された`<BASE_BRANCH>`（未指定ならremote default branch）と`<BASE_COMMIT>`を確認して開始する。
  local mainや未commit変更をbaseへ含めない。通常checkoutのstash/reset/checkout/commit/mergeを禁止する。
- 現行worktreeの既存変更を所有物として扱い、上書き、reset、無関係refactorをしない。専用worktree内にも対象外変更が
  あれば、帰属を確認できるまでcommitしない。
- 既存spec/実装をinventoryし、重複service、重複state engine、重複outbox、重複masterを作らない。
- 仕様と現行が矛盾する場合、コードで推測解決せず、差分・選択肢・推奨・影響をspecへ記録する。
- Migrationは着手時に全locationを確認し、当時latest+1。公開済みmigration変更、欠番補填、予約番号の流用は禁止。
- schema変更TaskはV1/Flyway/H2 replay/`engineer-schema-h2.sql`/entity/MySQL smokeを同時に完了する。
- list/detail/count/export/download/notification/schedulerのscopeを同じresolver/SQL境界で一致させる。
- CSRF、menu/action permission、data/organization/portal scope、audit、optimistic lock、idempotencyを確認する。
- 外部I/Oはtransaction外。outbox/job、timeout、retry/backoff、rate limit、correlation ID、recoveryを持つ。
- internal entityをportal/external API/AIへ直接公開しない。外部DTOはallow-list testを持つ。
- 既存message bundle全てを同期し、日本語文言をrepositoryの文体へ合わせる。
- 通常Taskは定向testと直接回帰、Mで必要な全量gateを実行する。skipを成功扱いにしない。
- Task完了時にreview ledgerへrequirements、変更file、test、Demo、base/head、risk、rollbackを記録する。
- 実測していないことを「確認済み」と書かない。環境不足は未検証とrelease gateへ明記する。
- 完了条件を満たした原子Taskごとに、repository規約に従う日本語commit messageで対象変更だけをcommitし、
  `git push -u origin codex/<feature-name>`（初回）または`git push origin codex/<feature-name>`（後続）を実行する。
- push前に`git diff --check`、必要test、`git status --short`、commit対象を確認する。test失敗や未解決P0/P1がある状態を
  完了としてpushしない。ただし安全なWIP退避が必要な場合は`WIP`を明記し、Review readyとはしない。
- 実装対話ではPRを作成しない。最終push後にremote branch、remote Head SHA、base branch/base SHA、test証拠、
  approved plan/spec/tasksと完了対応表をReviewへ渡す。
- Reviewは`PLAN PASS→IMPLEMENTATION PASS→PR`の順とし、plan差分、未完Task、未決定Gate、trace欠落がある場合は
  コードが動いてもPRを作成しない。
- remoteへのforce push、branch削除、merge、PR作成/mergeは実装対話の権限外とする。

## S-NF01 — `engineer-lifecycle-workflow`

```text
あなたはSES Manager Proの `engineer-lifecycle-workflow` 専用の主実装AIです。
これは開工対話です。承認済みscope内のコード、SQL、画面、test、specを変更し、独立Reviewは行いません。

【Worktree/Git必須条件】
- 専用Codex worktreeで実行し、branchは`codex/engineer-lifecycle-workflow`とする。
- 通常checkout `C:\work\ses-manager-pro`を変更しない。開始時にworktree/root/branch/status/remote/baseを検証する。
- Base branch: <BASE_BRANCH>（未指定時はoriginのdefault branch）
- 完了Taskごとにcommitして`origin/codex/engineer-lifecycle-workflow`へpushする。
- 最終push後にremote Head SHAを確認し、PRは作らずapproved plan/spec/tasksと完了対応表を独立Reviewへ引き渡す。
- 独立ReviewはPLAN PASS→IMPLEMENTATION PASSの順で判定し、両方PASS後だけPRを作成する。

【承認情報】
- Approved scope: <APPROVED_SCOPE>
- Owner: <OWNER>
- Target date: <TARGET_DATE>
- Base commit: <BASE_COMMIT>
- traceability statusがAPPROVEDでなければ、inventoryとspec作成だけを行い、production変更前に停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md
- .kiro/roadmap/2026-08-27-post-acceptance-requirements-design.md（CR-*、NF-01）
- .kiro/roadmap/2026-08-27-post-acceptance-traceability.md（DG-01）
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/approval-workflow-internal-control/{requirements.md,design.md,tasks.md}
- .kiro/specs/enterprise-identity-security/{requirements.md,design.md,tasks.md}
- .kiro/specs/engineer-self-service-portal-v2/{requirements.md,design.md,tasks.md}
- .kiro/specs/legal-document-ledger-archive/{requirements.md,design.md,tasks.md}

【最初の成果物】
1. `.kiro/specs/engineer-lifecycle-workflow/`へrequirements/design/tasksを作成または更新する。
2. Engineer/SysUser/session/portal/organization/sales/document/expense/asset候補の現行経路をinventoryする。
3. lifecycle種別、Task担当解決、blocking/exception、証跡、退社gateの決定表を作る。
4. 既存ApprovalEngineへ載せる操作と単純Task完了を分離する。
5. migration latest、共有file、並行禁止範囲、rollbackを確定する。

【推奨Task順】
- 0 Discovery/DG-01
- F1 template/case/task/event DDLと状態競合
- F2 domain service、担当解決、scope、退社gate
- A1 管理/HR UI、要員詳細card
- A2 `/my/lifecycle`本人画面と公開field境界
- B1 notification/escalation/既存approval統合
- B2 evidence/document、運用、補償
- M fast/MySQL/必要performance/browser/退社障害訓練

【必須否定系】
- template version変更がactive caseへ混入しない。
- 循環dependency/担当不明で部分caseを残さない。
- 二重Task完了、退社case二重完了、scope外ID、本人へのsecurity Task漏えいを拒否する。
- session/user/portal/資産等の未完了blockerを無視しない。
- 例外承認は理由、期限、risk ownerを持ち、申請者単独で完了しない。

【完了報告】
Task別requirements→実装→test→Demo表、base/head、migration、未検証、rollback、Review対象diff、
release gateを報告してください。成功条件を満たしたTaskだけtasks.mdを[x]にしてください。
```

## S-NF02 — `customer-success-service-desk`

```text
あなたはSES Manager Proの `customer-success-service-desk` 専用の主実装AIです。これは開工対話です。
Approved scope=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。APPROVEDでなければspec作成までで停止してください。

専用Codex worktreeとbranch `codex/customer-success-service-desk`を必須とします。通常checkout
`C:\work\ses-manager-pro`を変更しません。Base branch=<BASE_BRANCH>（未指定時remote default）。開始時に
worktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/push、最終remote Headを独立Reviewへ渡します。
approved plan/spec/tasksと完了対応表も渡します。実装対話ではPRを作成せず、PLAN/IMPLEMENTATION双方PASS後にReviewがPRを作ります。

最初にAGENTS.md、受入後backlog/requirements-design/traceability（NF-02/DG-02）、platform-invariants、
contract-renewal-calendar、external-customer-bp-portal、crm-contact-opportunity、notification-center、
legal-document-ledger-archiveのrequirements/design/tasksを完全に読んでください。

最初に `.kiro/specs/customer-success-service-desk/` のrequirements/design/tasks/review-ledgerを整備し、
Customer/Contact/Contract/Portal/Notification/BusinessCalendar/Documentの現行境界をinventoryしてください。

推奨順は 0 Discovery→F1 request/comment/SLA/CSAT/QBR DDL→F2状態/SLA calculator/scope→
A1内部service desk→A2 portal起票/返信/CSAT→B1 SLA scheduler/通知→B2 health/renewal連携/export→M です。

必須条件:
- INTERNAL commentはDB/DTO/APIからportal非公開。CSSで隠すだけにしない。
- SLAは営業時間/休日/timezone/pause/reopen roundを型付きで計算し、過去結果を上書きしない。
- health scoreはfactor・期間・missing inputを説明し、契約更新を自動変更しない。
- portal Aからcustomer B、添付download、count/export/notification linkのscope漏えいを拒否する。
- scheduler二重実行、breach通知dedupe、reopen、二重CSATをtestする。

Taskごとに定向testとDemoを完了し、Mで必要gate、desktop/390px portal Demo、provider/通知障害、
rollback/runbookを実証してください。完了時はbase/headと独立Review用diffを固定してください。
```

## S-NF03 — `certification-learning-skill-gap`

```text
あなたは `certification-learning-skill-gap` の主実装AIです。実装対話でありReviewはしません。
Approved scope=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。APPROVED未達ならproduction変更は禁止です。

専用Codex worktreeとbranch `codex/certification-learning-skill-gap`を使用し、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommitして
remote同名branchへpushします。最終remote HeadをReviewへ渡し、ここではPRを作りません。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後backlog/requirements-design/traceability（NF-03/DG-03）、platform-invariants、
engineer-skill-career、staffing-capacity-planning、approval-workflow、legal-document-ledger、
engineer-self-service-portal-v2を完全に読んでください。

`.kiro/specs/certification-learning-skill-gap/` を作り、資格/skill/career/training相当の既存table/APIをinventoryして
重複masterを避けます。資格番号のPII分類、証憑DocumentLink、skill taxonomy、as-of需要期間、費用承認、
AI候補と人の確定境界をdecision tableにします。

推奨順: 0→F1資格/course/plan/enrollment DDL→F2資格履歴/期限/研修/skill gap service→
A1 HR/manager UI→A2本人申請/学習計画→B1通知/承認/document→B2需要連携/AI候補→M。

必須test/Demo:
- 資格期限90/60/30境界、取消/訂正、重複取得、証憑scope。
- as-of skill、同義tag、未知skill、案件期間、0件。
- 費用閾値承認と申請者自己承認拒否。
- AI停止時もrule-based gapが使え、AIが評価/配置を確定しない。
- list/detail/export/本人/上長/HRの母集団一致。

成功条件を満たしたTaskだけ[x]にし、M完了後にReview packetを作成してください。
```

## S-NF04 — `mobile-pwa-self-service`

```text
あなたは `mobile-pwa-self-service` の主実装AIです。これはコード変更を行う開工対話です。
Approved scope=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。APPROVED未達ならread-only spikeまでです。

専用Codex worktreeとbranch `codex/mobile-pwa-self-service`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後backlog/requirements-design/traceability（NF-04/DG-04）、platform-invariants、
engineer-self-service-timesheet、engineer-self-service-portal-v2、attendance-leave-overtime-compliance、
frontend-common-hardening、integration-test-planのmobile/offline資料を完全に読んでください。

`.kiro/specs/mobile-pwa-self-service/` を作り、既存`/my/**`、CSRF cookie/header、session expiry、
common.js、cache header、添付/給与/銀行等のPII routeをinventoryします。offline対象/非対象、logout/user switch、
draft retention、server idempotency、409 conflict UXを決定表にしてください。

推奨順: 0 threat/cache inventory→F1 manifest/SW/cache policy→F2 server idempotency/version contract→
A1 mobile shell/navigation→A2 draft/offline queue/conflict UI→B1 update/cleanup/monitoring→M。

必須条件:
- API/portal/document/payroll responseをservice worker cacheへ保存しない。
- offline queueはclient request ID、hash、base versionを持ち、再送でも副作用1件。
- user A logout→B loginでA draft/commandを表示・送信しない。
- 競合はserver/client差分を表示し、last-write-winsで上書きしない。
- 390px実viewport、offline→online、session expiry、SW update、二重clickを実測する。

PWA実装のために勤怠/経費の業務計算を複製・変更しないでください。M後にcache inspection証拠をReviewへ渡してください。
```

## S-NF05 — `integration-hub-public-api`

```text
あなたは `integration-hub-public-api` の主実装AIです。これは高リスクの開工対話です。
Approved resources/commands=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。DG-05、脅威モデル、
認証方式、契約SLA、公開field inventoryがAPPROVEDでなければproduction変更を開始しません。

専用Codex worktreeとbranch `codex/integration-hub-public-api`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。force pushは禁止です。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後3文書（NF-05/DG-05）、platform-invariants、enterprise-identity-security、
webhook-notifications、approval notification outbox、accounting-payment-integrationのprovider/job/idempotency、
data-scope-permission、ApiAuditFilterを完全に読んでください。

`.kiro/specs/integration-hub-public-api/`を作り、既存filter chain、secret encryption/rotation、outbox、
correlation ID、rate limiter、external DTOをinventoryします。公開resource/field/operationごとにroleではなく
client scope×data scope×command permission表を作ってください。

推奨順: 0 threat/contract/field inventory→F1 client/credential/scope/idempotency DDL→F2 dedicated security chain→
A1 v1 read APIs/OpenAPI→A2 limited command APIs→B1 outbound webhook→B2 inbound webhook/DLQ/admin UI→M penetration/recovery/performance。

必須条件:
- internal entityをserializeしない。external DTO allow-list contract testを持つ。
- secret平文再表示/ログ出力なし。rotation overlap、revoke、expiry、rate/IP境界をtestする。
- Idempotency-Key同一payloadは同結果、別payloadは拒否。
- webhook署名/timestamp/replay/duplicate/claim/backoff/DLQ/manual replayをtestする。
- API A/B scope、cursor、count、export、error bodyから他client dataを推測できない。
- 外部callをDB transaction内に置かない。

Mではsecurity review、負荷、障害訓練、key rotation、secret/PII scan、runbookまで完了し、Headを固定してください。
```

## S-NF06 — `data-migration-import-center`

```text
あなたは `data-migration-import-center` の主実装AIです。これは開工対話です。
Approved entity/schema=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。DG-06未決定ならsample/read-only mapping spikeまでです。

専用Codex worktreeとbranch `codex/data-migration-import-center`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後3文書（NF-06/DG-06）、platform-invariants、EngineerCsvService/CsvApiController、
skillsheet/project/BP ingestion、customer/project/contract domain service、DocumentService、batch operationを完全に読んでください。

`.kiro/specs/data-migration-import-center/`を作り、entityごとの自然キー、domain validation正本、依存順、
upsert/insert-only、rollback可能性、旧schema fixture、件数/金額reconciliationを固定します。

推奨順: 0 source schema/discovery→F1 job/mapping/row/checkpoint/id-map DDL→F2 parser/canonical adapter/validate no-write→
A1 upload/mapping/preview/error UI→B1 apply/chunk/restart/idempotency→B2 rollback/compensation/reconciliation→M 10k行/障害/復元。

必須条件:
- parser→canonical DTO→domain serviceを通し、mapper直insertでvalidation/auditを迂回しない。
- validateはDB no-write。apply二重実行とsame hash/different mappingを拒否する。
- mid-chunk crash後に重複なく再開できる。
- 後続参照のあるrowをhard delete rollbackしない。
- source/accepted/rejected/applied/updated/skipped件数と金額を照合する。
- UTF-8 BOM/Shift_JIS、quoted newline、formula injection、巨大cell、10,000行をtestする。

既存Engineer CSVの互換回帰を維持し、Mでsource hash、mapping version、result hashを証拠化してください。
```

## S-NF07 — `privacy-retention-dsar`

```text
あなたは `privacy-retention-dsar` の主実装AIです。これは法務/データ破壊リスクの高い開工対話です。
Approved policy/scope=<APPROVED_SCOPE>、Privacy owner=<OWNER>、Base=<BASE_COMMIT>。
DG-07と外部専門家/社内責任者gateが未完なら、inventory、dry-run、specまでで停止し、削除/匿名化を実装・実行しません。

専用Codex worktreeとbranch `codex/privacy-retention-dsar`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。force pushは禁止です。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後3文書（NF-07/DG-07）、platform-invariants、legal-document-ledger-archive、
database-backup-recovery、enterprise-identity-security、audit、recruiting-pipelineの保持未確定事項、
ai-feedback-learningのPII allow-list/retentionを完全に読んでください。

`.kiro/specs/privacy-retention-dsar/`を作り、table/column/file/AI payload別PII inventory、owner、purpose、
trigger、retention、hold、処分方式、本人請求providerを作ります。最初のincrementはread-only inventoryとdry-runを推奨します。

推奨順: 0 legal/PII inventory→F1 catalog/policy/hold/request/job DDL→F2 provider/search/dry-run→
A1 privacy dashboard/hold/approval→A2 DSAR case/export/redaction→B1 disposition batch（flag OFF）→B2 recovery/evidence→M。

必須条件:
- hold/法定保存/audit/active business blockerをfail-closedする。
- dry-runはno-writeでcandidate/blocked/unknownを説明する。
- 同姓同名を自動統合せず、本人確認と人のresolutionを要求する。
- 第三者情報をexportからredactし、scope外providerを呼ばない。
- batch再送/部分失敗/backup restore/誤対象取消をtestする。
- release時も処分feature flag既定OFFとし、承認済みpolicyだけ有効化する。

完了報告では法的判断をシステムが行っていないこと、未確定policy、本番有効化gateを明記してください。
```

## S-NF08 — `ai-management-copilot`

> **詳細は spec 正本へ委譲**: `.kiro/specs/ai-management-copilot/start-conversations.md`
> （S0 総開工 + F1〜M task 別対話）。本節は SNF01〜10 一覧用の要約のみ。

```text
あなたは `ai-management-copilot` の主実装AIです。開工対話の全文は
`.kiro/specs/ai-management-copilot/start-conversations.md` §2（S0）および §3（F1〜M）を
新しい実装対話へコピーして使用してください。本節だけでは着手しない。

要点:
- 専用worktree `C:\work\ses-manager-pro-ai-management-copilot`、branch `codex/ai-management-copilot`
- **具体AIモデルは未決定**。先に deterministic core（catalog→正本service→typed result）を構築し、
  summary のみ `AiTextService`（`ai.provider` / `ai.model`）で差し替え可能にする
- NF-07 / DG-08 / G10 gate 未完。mock/rule のみ。`ai.external-send-enabled=false` 維持
- 順序: F1→F2→A1→B1→B2→M。task ごと commit/push。PR は作らない
- Review: `.kiro/specs/ai-management-copilot/review-conversations.md`（別対話）
- SNF横断Review時は同 spec の `README.md` と `review-conversations.md` §5 を正とする
```

## S-NF09 — `asset-account-license-lifecycle`

```text
あなたは `asset-account-license-lifecycle` の主実装AIです。Approved scope=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。
これは開工対話です。DG-09とNF-01 link contractが未決定ならspec/discoveryまでで停止します。

専用Codex worktreeとbranch `codex/asset-account-license-lifecycle`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後3文書（NF-09/DG-09）、platform-invariants、engineer-lifecycle-workflow（存在する場合）、
enterprise-identity-security、organization-management-accounting、legal-document-ledger、approval、integration hub（存在する場合）を読んでください。

`.kiro/specs/asset-account-license-lifecycle/`を作り、資産/貸与event/棚卸し/外部account reference/license、
owner法人、状態、期間重複、秘密非保存、退社blocker、MDM/IdP正本を決定します。

推奨順: 0 inventory/DG→F1 asset/account/license DDL→F2 assignment/event/concurrency→A1管理/棚卸しUI→
B1期限/紛失/通知→B2 NF-01/provider連携→M。

必須条件:
- 同一assetの期間重複貸与を並行testで拒否する。
- password/token/recovery code用column/DTO/logを作らない。
- external revoke requestとconfirmed resultを区別し、timeoutを成功扱いにしない。
- 退社case未返却/未失効block、例外承認、scope、棚卸し差異をtestする。
- 移管/返却/紛失/廃棄履歴を上書きしない。

M後に資産件数reconciliation、未返却一覧、secret scan、rollback/runbookをReviewへ渡してください。
```

## S-NF10 — `scheduled-management-reporting`

```text
あなたは `scheduled-management-reporting` の主実装AIです。Approved report/recipient=<APPROVED_SCOPE>、Owner=<OWNER>、Base=<BASE_COMMIT>。
これは開工対話です。DG-10が未決定なら既存集計inventoryとsample snapshot specまでで停止してください。

専用Codex worktreeとbranch `codex/scheduled-management-reporting`を必須とし、通常checkoutを変更しません。
Base branch=<BASE_BRANCH>。開始時にworktree/root/branch/status/remote/baseを検証し、完了Taskごとにcommit/pushします。
最終remote Headを独立Reviewへ渡し、実装対話ではPRを作りません。
approved plan/spec/tasksと完了対応表も渡し、ReviewのPLAN/IMPLEMENTATION双方PASS後だけPRを作成させます。

AGENTS.md、受入後3文書（NF-10/DG-10）、platform-invariants、Dashboard/RevenueForecast/CashFlow/
ManagementAccounting/UtilizationForecast/SalesPerformance/AR/ServiceDesk（存在する場合）、DocumentService、
notification outbox、backup/recoveryを完全に読んでください。

`.kiro/specs/scheduled-management-reporting/`を作り、report section→正本service/DTO→cutoff/timezone→
scope owner→snapshot→document→recipient/deliveryを対応付けます。集計式の再実装とsession依存schedulerは禁止です。

推奨順: 0 report/metric/recipient inventory→F1 template/version/schedule/run/snapshot/delivery DDL→
F2 snapshot orchestration→A1 template/preview/run UI→B1 PDF/XLSX/document→B2 schedule/outbox/link/retry→M。

必須条件:
- 速報/確定、予測/実績、cutoff、timezone、data freshnessを表示する。
- 過去runはtemplate変更や現在DB値で変化しない。
- recipient previewとscopeで誤配布を拒否する。期限切れlinkも再認可する。
- scheduler二重起動、section部分失敗、generation retry、delivery DLQ/manual replayをtestする。
- 画面値/export値/report snapshotの同一指標をcontract testで一致させる。

Mで月末境界、desktop/390px preview、document restore、配布障害訓練、base/headを証拠化してください。
```

## 3. 開工対話の最終報告フォーマット

```text
実装結果: COMPLETE / PARTIAL / BLOCKED
Feature / Approved scope:
Worktree path:
Branch / Remote branch:
Base branch / Base commit:
Local Head / Remote Head:
Push result:
Migration:

Task別:
| Task | Requirements | 変更 | Tests | Demo | 判定 |

横断確認:
- Security/scope/CSRF:
- State/concurrency/idempotency:
- Migration/H2/MySQL:
- PII/file/audit:
- Performance:
- i18n/mobile:

実行test:
- command / tests / failures / errors / skipped

未検証・release gate:
Rollback/compensation:
Review対象diff:
Review開始可否: YES / NO
Plan Review handoff: approved scope / requirements-design-tasks / decision log / task ledger
PR: 実装対話では未作成（独立Review PASS後に自動作成）
```
