# 17 Spec 派工对话（中文版）

> **本文件是日文正本的翻译版，不是独立的真相。**
> 日文正本：`spec-start-conversations.md` / `spec-review-conversations.md` / `copyable-conversations/*.txt`。
> 两版的 Migration 预约号由 `src/test/java/com/ses/migration/SpecDispatchConsistencyTest` 校验，
> 与 `design.md` 的 `予約V##` 不一致时测试失败。**改号码时两版一起改。**
>
> 每个代码块可直接整块复制到新对话。
>
> **当前状态**（正本：`spec-execution-ledger.md`）：
> - S01/R01 **不要发送**。T001 已完成，T002–T007 处于延期（G0 已定为「每个客户独立数据库」）。
> - S02 已 PASS。S03 进行中（`FIX/REVIEW`）。
> - **S04 现在还不能开工**——要等 S03 独立 Review 判定 P0=0/P1=0/PASS 并反映到中央台账。

## Migration 预约号（唯一的正）

V59 是**永久欠番**，任何情况下不得补写。实际已应用到 V63。

| Spec | 预约号 | Spec | 预约号 |
|---|---|---|---|
| S01 tenant | 未定（重启时按当时 latest+1） | S10 dispatch | **V70** |
| S02 organization | V60（已应用） | S11 attendance | **V71** |
| S03 identity | V63（已应用） | S12 staffing | **V72** |
| S04 archive | **V64** | S13 external portal | **V73** |
| S05 productivity | **V65** | S14 engineer portal | **V74** |
| S06 BP | **V66** | S15 accounting | **V75** |
| S07 approval | **V68** | S16 JP PINT | **V76** |
| S08 CRM | **V67** | S17 AI feedback | **V77** |
| S09 order | **V69** | | |

合并顺序：**BP V66 → CRM V67 → approval V68**。开发可并行，DDL 合并必须按号码顺序。
着手时重新确认已合并的 `db/migration` 最新号；冲突时把**后来者往上移**，不要填之前的欠番
（已应用更高版本的 DB 会以 `FlywayValidateException` 拒绝更低的版本）。

---

## 1. Multi-company Tenant

### S01 开工对话（当前延期，不要发送）

```
你是 multi-company-tenant-isolation Spec 的主实现AI。

T001已经完成，不得重新执行或Review。你的范围仅包含：
T002 F1、T003 F2、T004 F3、T005 F4、T006 F5、T007 M。

完整阅读以下文件：
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md
- .kiro/specs/multi-company-tenant-isolation/tenant-inventory.md

当前G0正式决定为「每个客户独立数据库」，因此T002～T007全部延期。除非发注者重新批准共享数据库SaaS、
重新完成G0、按当时latest+1重新分配Migration，并准备Docker/MySQL smoke环境，
否则不得修改代码、SQL、Flyway或配置。V59是永久欠番，重启时也不得补写。

重新批准后执行顺序：F1→F2→F3→F4→F5→M。该Spec禁止并行修改production文件，
子Agent只能进行只读调查、测试矩阵和diff Review。

每次只完成并勾选一个Task。DDL必须同步V1、增量Flyway、H2 replay、engineer-schema-h2、Entity和MySQL smoke。
更新review-ledger.md，记录Requirements、文件、测试、Demo、Commit、风险和回滚。

如果当前条件未满足，只报告阻塞原因和重新启动条件，不得实施。
```

### R01 Review 对话（未来使用）

```
这是multi-company-tenant-isolation的独立Review对话，仅Review T002～T007，T001完全排除。

读取AGENTS.md、全局README/decision-log/gate-decisions-g1-g6/execution-review-handbook/shared-standards/
dependency-matrix/parallel-execution-plan/spec-execution-ledger、本Spec requirements/design/tasks、
tenant-inventory和review-ledger。

Base Commit：<填写>
Head Commit：<填写>

自行检查实际diff，不相信实现说明，不修改文件。确认共享数据库已重新获批、
Migration使用重新启动时的latest+1，而不是补写已经越过的V59。

逐项验证T002～T007的Requirements、实现、自动测试和Demo；重点检查跨Tenant的
list/detail/count/export/download/notification/scheduler、线程复用、Cache、Async、文件、
备份恢复、复合UNIQUE/FK和登录边界。

指摘格式按execution-review-handbook.md §10：issue ID、severity、violated requirement、file:line、
复现条件（data/role/time）、expected/actual、影响、证据、最小修复范围、直接回归范围、discovered in。
再Review按§11只处理OPEN issue、修复diff、直接回归和新引入的P0/P1。

输出P0/P1/P2问题、Task对应表、未验证环境，以及PASS/CONDITIONAL PASS/FAIL/NOT REVIEWABLE。
未PASS不得进入下一个Spec。
```

---

## 2. Organization Management Accounting（已 PASS）

### S02 开工对话

```
你是organization-management-accounting Spec的主实现AI，负责T008～T013。

开始条件：
1. T001及独立数据库Gate已经记录完成。
2. G1～G6的发注者方针已经记录。
3. 明确V59不创建，V60部署后不得补写V59。
4. 当前没有其他AI修改组织、用户、sidebar或Migration共享文件。

完整阅读AGENTS.md、全局README/decision-log/gate-decisions-g1-g6/execution-review-handbook/
shared-standards/dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

执行顺序：
T008 F1 →（T009 F2与T010 A1可并行）→ T011 B1 → T012 B2 → T013 M。

Migration为V60。F1由主AI独占并同步V1、Flyway、H2两套Schema、Entity和MySQL smoke。
并行时必须声明子Agent的允许文件和禁止共享文件。

每次只完成一个Task，满足Objective、测试要求和Demo后才勾选。确认organization scope在查询边界生效，
不能通过页面后过滤代替。同步CSRF、审计、乐观锁、4语言i18n和权限菜单。

维护本Spec的review-ledger.md。M由主AI单独完成。最终报告Task对应表、测试、Demo、未验证事项、
Base/Head Commit和回滚方案，不得开始下一个Spec。
```

### R02 Review 对话

```
这是organization-management-accounting的独立Review对话，范围为T008～T013。

读取AGENTS.md、全局规划文档、execution-review-handbook、本Spec requirements/design/tasks及review-ledger。
自行检查Base Commit <填写> 到Head Commit <填写> 的实际diff，不修改文件。

逐项建立Requirements→实现→测试→Demo表。重点检查：
- V60/V61/V62、V1、H2、Entity、MySQL smoke是否同步
- 组织树循环、历史所属、月次Snapshot和预算口径
- OrganizationScope是否覆盖list/detail/count/export/notification
- 上长、Cost Center、离职和组织移动边界
- 权限、CSRF、审计、乐观锁和4语言i18n
- 管理会计Dashboard是否使用正确月份及金额单位

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、file:line、复现条件和最小修复范围。给出PASS/CONDITIONAL PASS/FAIL，
并明确能否进入enterprise-identity-security。
```

---

## 3. Enterprise Identity Security（进行中）

### S03 开工对话

```
你是enterprise-identity-security Spec的主实现AI，负责T014～T020。

开始条件：organization-management-accounting已经Review PASS；
G1已经正式确定IdP、MFA范围、break-glass责任人和恢复流程。

完整阅读AGENTS.md、全局规划文档、execution-review-handbook、shared-standards、
test-execution-policy-s03-s17、spec-execution-ledger，以及本Spec requirements/design/tasks。

执行顺序：
T014 0 → T015 F1 → 三条实现线：
1. T016 A1 → T017 A2
2. T018 B1
3. T019 B2
最后T020 M。

Migration为V63（V61/V62已被organization-management-accounting使用，不得重用）。
SecurityConfig、Session、Authority Model和共通认证文件只能由主AI修改。
T018权限迁移和T019文件扫描可在Interface固定后交给不同子Agent。

测试范围按test-execution-policy-s03-s17.md：T014～T019只做L0～L3的定向测试与直接回归，
T020执行一次L4全量。不得给普通Task加无条件的全量测试。

逐Task验证OIDC provision/logout、MFA恢复、Session失效、Action Permission、文件隔离与扫描fail-closed。
同步CSRF、审计、4语言i18n、V1/Flyway/H2/Entity/smoke。

维护review-ledger.md；每次只勾选一个完成Task；M由主AI执行完整安全回归。
最终报告测试、Demo、威胁模型剩余风险和回滚。
```

### R03 Review 对话

```
这是enterprise-identity-security的独立Review，范围T014～T020。

读取AGENTS.md、decision-log中G1、gate-decisions-g1-g6、execution-review-handbook、shared-standards、
test-execution-policy-s03-s17、本Spec requirements/design/tasks、review-ledger，
并检查Base <填写> 到Head <填写> 的实际diff。不要修改文件。

重点验证OIDC state/nonce、账号绑定冲突、自动Provision、退出、MFA恢复、break-glass、Session撤销、
权限绕过、菜单与API权限一致性、文件扫描异常时fail-closed、上传绕过和未知文件。

检查SecurityConfig所有Chain和Matcher顺序，确认内部、要员和未来Portal边界没有放宽。
验证CSRF、审计、密码Profile、Cookie、并发Session和测试环境。

按test-execution-policy §8判断是否需要重跑L4：M的L4证据commit与merge后Head一致且差分只是文档时，
不重复执行，改为证据核对＋独立L1/L2。判断依据写进Review Ledger。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、未测试威胁和PASS/CONDITIONAL PASS/FAIL；
明确能否进入legal-document-ledger-archive。
```

---

## 4. Legal Document Ledger Archive

### S04 开工对话

```
你是legal-document-ledger-archive Spec主实现AI，负责T021～T027。

开始条件：enterprise-identity-security（S03）独立Review判定P0=0/P1=0/PASS并已反映到中央台账；
G2已经确定法务监督者、保存期间、订正删除规则和责任人（G2的外部专家承认是M/本番gate，不阻塞开工）。

完整阅读以下文件：
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

既定解与决定表：
- platform-invariants.md 是「既定解的表」而不是检查清单。时间/历史/明示NULL的区别、授权母集合的
  结合规则、cache与事务顺序、期间代数、Migration五形态、金额与CSV的默认答案都在那里。
- 本Spec design.md §6「決定表」3表（时间·asOf / 主体×操作×可见母集合 / 状态机与竞合）已经填好，
  是本Spec的正。实装中不得重新决定。
- 只有偏离既定解时才写「逸脱と根拠」。没写就按既定解实装。

顺序：
T021 0 → T022 F1 → T023 F2 →（T024 A1、T025 B1、T026 B2可并行）→ T027 M。

Migration为V64。主AI拥有文档Canonical Model、DocumentService、Storage Adapter和下载Scope。
F2合并后，台账UI、CloudSign/既存帐票、Export/Retention可以分派。

测试范围按test-execution-policy-s03-s17.md：T021～T026只做L0～L3，T027执行一次L4全量。

必须实现Streaming下载、Hash、Version、原本与派生文件区分、订正删除记录、保留/销毁、恢复和
未知文件fail-closed。保存文件时必须同时注册FileReferenceProvider和FileScopeValidationService
（两者遗漏时的默认值都倒向危险侧，见platform-invariants §2.5）。
同步权限、审计、4语言i18n、V1/Flyway/H2/Entity/smoke。

逐Task更新review-ledger并执行Demo；M必须验证迁移、恢复、Hash和既存电子合同回归。
```

### R04 Review 对话

```
这是legal-document-ledger-archive独立Review，范围T021～T027。

读取G2决定、gate-decisions-g1-g6、execution-review-handbook、shared-standards、
platform-invariants、test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

重点检查文档原本、版本、Hash、相手先/日期/金额搜索、订正删除履历、保存期间、销毁审批、
CloudSign同步、ZIP/税务Export、Streaming、Path Traversal、跨Scope下载和未知文件fail-closed。

按design.md §6決定表逐项核对：
- retention_until IS NULL 是否被当成「已过期」（应为起算日未确定，不进销毁候选）
- scan_status NULL 是否被当成clean（应为未扫描＝不可阅览）
- 文档母集合是否从t_document_link先的业务entity scope导出，且多link时取和集合
- 生成的冪等键是否为(source_type, business_key, version_discriminator)
- storage put成功但DB commit失败时是否留作orphan而非立即删除

确认所有文档入口使用同一DocumentService和授权母集合，V64/V1/H2/Entity/smoke同步，恢复Demo可重现。
确认FileReferenceProvider和FileScopeValidationService都已注册。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Requirements追踪表、法务未确认项和PASS/CONDITIONAL PASS/FAIL。
PASS后才允许进入productivity-search-saved-view。
```

---

## 5. Productivity Search Saved View

### S05 开工对话

```
你是productivity-search-saved-view Spec主实现AI，负责T028～T033。

开始条件：archive Spec Review PASS，共通Document和Scope接口已经固定。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §6「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。只有偏离时才写「逸脱と根拠」。

顺序：
T028 F1 →（T029 A1、T030 A2、T031 B1、T032 B2可并行）→ T033 M。

Migration为V65。F1由主AI完成。之后最多同时使用3个子Agent，第四条任务在任一子Agent完成后顺序执行。
每个子Agent必须有独立文件范围。

测试范围按test-execution-policy-s03-s17.md：T028～T032只做L1～L3，T033执行一次L4全量。

实现横断搜索、真正的ToDo、保存View/列设置和安全批量操作。
横断搜索的每个Provider必须直接调用既存mapper的带scope查询，不得为搜索另写SQL
（否则母集合变成双重定义，正是S02的复发模式）。
确认搜索、Count、分页、Scope、批处理部分失败、幂等、CSRF、审计和大数据量限制。
m_saved_view.owner_user_id IS NULL 表示共享View，不是「未设定」。
t_task.due_date IS NULL 表示无期限，必须从期限超过的判定中显式排除。

逐Task更新review-ledger，M验证权限矩阵、性能、批处理和既存Notification不回归。
```

### R05 Review 对话

```
这是productivity-search-saved-view独立Review，范围T028～T033。

读取全局文档（含execution-review-handbook、platform-invariants、test-execution-policy-s03-s17）、
本Spec requirements/design/tasks/review-ledger，检查Base <填写> 到Head <填写> diff，不修改文件。

检查横断搜索是否泄露无权限对象，搜索Count与结果一致（无权限对象连件数都不能出现），
保存View不能注入任意字段，一括操作具有确认、CSRF、审计、部分失败和幂等保护，
ToDo与Notification语义没有混淆（已读与完成互相独立）。

按design.md §6決定表逐项核对：
- 各Provider是否复用既存mapper的scope查询，而不是新写SQL
- owner_user_id IS NULL 是否被当成「未设定」而让共享View变成全员的个人View
- due_date IS NULL 是否被算进期限超过
- task可见性是否被叠加了组织scope（不应叠加，否则异动者看不到自己的task）
- bulk apply的母集合是否固定在preview时的scope，而非apply时重新评价
- 201件是否整个请求被拒绝（不是部分执行）

验证大数据量分页、Query上限、N+1、空状态、4语言i18n和旧页面兼容。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、性能未验证事项、PASS/CONDITIONAL PASS/FAIL。PASS即Wave 0完成。
```

---

## 6. BP Company Master

### S06 开工对话

```
你是bp-company-master-procurement-compliance Spec主实现AI，负责T034～T040。

开始条件：Wave 0 Review PASS；G2法务决定完成。
该Spec可以与CRM并行开发，但Migration必须先合并本Spec的V66，再合并CRM的V67。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §5「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。只有偏离时才写「逸脱と根拠」。

顺序：
T034 0 → T035 F1 → T036 F2 →（T037 A1、T038 B1、T039 B2可并行）→ T040 M。

Migration为V66。主AI拥有V66、BP Canonical Master、历史迁移和共通Payment引用。
F2后可以分派管理UI、Compliance Rule、风险Dashboard/通知。

测试范围按test-execution-policy-s03-s17.md：T034～T039只做L0～L3，T040执行一次L4全量。

必须消除现役BP自由输入，保留历史可追踪性，保护银行信息和PII，支持价格协商、法定不足项、风险和通知。
compliance_applicability IS NULL 表示「未确认」而不是「非该当」，必须成为finding对象。
t_bp_terms的版本切换以effective_from为准，用支付确定日解析，不是画面显示时刻。
t_engineer_bp_affiliation按platform-invariants §1.2适用全部期间case（同日/未来/追溯/空白期间）。
仮BP生成用UNIQUE(tenant_id, normalized_name)保证冪等，重复候选只警告不自动merge。
同步Scope、审计、i18n和Schema。

维护review-ledger；M验证旧输入废止、迁移对账、付款和既存BP数据回归。
```

### R06 Review 对话

```
这是BP Company Master独立Review，范围T034～T040。

读取G2决定、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

重点检查V66迁移对账、自由输入映射、重复BP、联系人/银行信息权限、历史记录、价格协商、
法定不足项、风险通知以及BP Payment引用。

按design.md §5決定表逐项核对：
- compliance_applicability IS NULL 是否被当成「非该当」而跳过检查
- 支付条件的版本是否用支付确定日解析（不是画面时刻）
- 过去支付的会社名与条件是否为snapshot，主数据改名后不变
- 银行账号的解密值是否出现在任何API响应（一览/详细/CSV都只能末尾）
- status=取引停止 的BP是否在候选query的WHERE句里被排除（不是取得后过滤）
- BP乗换的同日/未来/追溯/空白期间是否都有fixture
- 仮BP再生成是否冪等

确认旧数据不会丢失，现役流程不再生成自由输入，Scope覆盖list/detail/export/notification，
敏感字段不进入日志。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、法务未决和PASS/CONDITIONAL PASS/FAIL。
PASS后等待CRM通过，再启动Approval。
```

---

## 7. Approval Workflow

### S07 开工对话

```
你是approval-workflow-internal-control Spec主实现AI，负责T041～T047。

开始条件：BP的V66和CRM的V67均Review PASS并已按号码顺序合并；
G7已决定或明确采用推荐默认值（G7的blocking=no，采用推荐默认值时必须记录这一事实）。
Migration使用V68。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表。
本Spec是「状态机 × 期间 × 金额 × 权限」的四重交叉，与S02同样的事故结构。
design.md §6「決定表」和§6.2「金额带的边界」已经确定，实装中不得读改或重新决定。
特别是：金额带min/max都是inclusive；多route命中时按「组织的具体性→金额带的窄度→version_no的新度」定1件；
无匹配route时拒绝受理并通知管理员，不得fallback到默认route；
amount_snapshot IS NULL 不得当成0元；负金额按绝对值判定。

顺序：
T041 0 → T042 F1 → T043 F2 →（T044 A1、T045 A2、T046 B1可并行）→ T047 M。

主AI拥有Approval Engine、Route、CAS、状态机和5个Target Adapter。
F2固定后，可分派Inbox/Diff、Route/代理管理和SLA通知。

测试范围按test-execution-policy-s03-s17.md：T041～T046只做L0～L3，T047执行一次L4全量。

必须禁止自我审批，支持差分Snapshot、代理、驳回、撤回、并发审批、SLA和Escalation。
route snapshot在申请时确定且以后不变（route改版不影响进行中的申请）。
代理在承认操作的执行时点评价，不是申请时点。
最终承认事务顺序：request行加锁 → target version再验证 → adapter.applyApproved → request=approved → outbox insert。
外部API与邮件在commit后由outbox执行，不在DB事务内。
target version冲突时作为conflict退回申请者，不得适用旧snapshot，也不得自动合并。
不能绕过原有业务状态机、月结和权限，不得创建*.approve.bypass权限。

逐Task更新review-ledger，M验证见积、合同、请款、BP付款和月结五条完整路径。
```

### R07 Review 对话

```
这是Approval Workflow独立Review，范围T041～T047。

读取G7、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

重点检查自我审批、职务分离、金额阈值、代理权限、并发CAS、重复Action、Snapshot Diff、
驳回后修改、撤回、SLA和通知。

按design.md §6/§6.2逐项核对（这些是本Spec最容易出事的点）：
- 金额带的边界fixture是否有min-1/min/max/max+1
- 无匹配route时是否拒绝受理（不是fallback到默认route）
- amount_snapshot IS NULL 是否被当成0元而落进最低金额带
- 负金额（取消/订正）是否按绝对值判定
- route改版后进行中申请的承认者是否不变（route_snapshot_json）
- 代理是否按承认执行时点评价，申请～承认之间代理期间开始/结束的两个case是否都有fixture
- 同一step上本人与代理都解析到时是否先到先得（承认者数不重复计）
- 申请者被解析为承认者时是否委让给下一候选，无候选时是否拒绝受理（不是自动approved）
- 最终承认事务的顺序，以及外部API/邮件是否在commit后
- target version冲突时是否退回而非适用旧snapshot
- diff_json的字段级权限（原价/工资/账号是否在承认画面素通）

验证五类Target Adapter不能绕过原业务校验，审批与最终确定在同一正确事务边界，
审计记录完整且不可篡改，对象侧有UNIQUE(approval_request_id)防二重适用。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表和PASS/CONDITIONAL PASS/FAIL。PASS后Wave 1完成并允许启动Order。
```

---

## 8. CRM Contact Opportunity

### S08 开工对话

```
你是crm-contact-opportunity Spec主实现AI，负责T048～T053。

开始条件：Wave 0 PASS。可与BP并行开发，但必须在BP的V66合并后再合并本Spec的V67。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §6「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。只有偏离时才写「逸脱と根拠」。

顺序：
T048 F1 → T049 F2 →（T050 A1、T051 A2、T052 B1可并行）→ T053 M。

Migration为V67。主AI拥有V67、Opportunity状态机、Lead转换和Forecast排他。
F2后分派Contact/Timeline、Lead/Opportunity UI和CRM KPI。

测试范围按test-execution-policy-s03-s17.md：T048～T052只做L1～L3，T053执行一次L4全量。

迁移既存Customer Contact时不得重复；失注理由、阶段历史、担当者Scope和Forecast口径必须一致。
primary_flag是「1顾客在有效期间内1件」，也允许0件（主担当未设定），不得暗默fallback到第一个担当者。
受注时的project/quotation变换用source_opportunity_id的UNIQUE保证冪等（CAS＋UNIQUE双重防御）。
Forecast母集合是converted_quotation_id IS NULL AND stage NOT IN (受注,失注)；
已变换的移到既存提案forecast，不得做把两个系列相加的画面。
帐票的宛先保存名称/email snapshot，以后担当者变更不改过去帐票。
contact的PII在export也要用同样的mask。

逐Task记录review-ledger，M验证转换、并发和KPI。
```

### R08 Review 对话

```
这是CRM独立Review，范围T048～T053。

读取全局文档（含execution-review-handbook、platform-invariants、test-execution-policy-s03-s17）、
本Spec requirements/design/tasks/review-ledger，检查Base <填写> 到Head <填写> diff，不修改文件。

检查V67迁移、重复Contact、Lead→Opportunity转换幂等、状态跳转、失注理由、Timeline权限、
Forecast排他、担当者Scope和KPI分母。

按design.md §6決定表逐项核对：
- primary_flag为0件时是否暗默fallback到第一个担当者
- 受注操作执行2次时project/quotation是否只有1件
- converted_quotation_id IS NULL 的明示NULL判定是否正确
- opportunity forecast与既存提案forecast是否在某个画面被相加（二重计上）
- 退职担当者是否从新规宛先候选排除但历史仍保留
- 过去帐票的宛先是否为snapshot
- contact的PII mask是否在CSV/Excel也生效（不是只在画面）
- 未割当lead是否对营业全员可见（避免母集合0件）

确认Customer既有功能未被破坏，list/detail/count/export使用相同数据范围。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表和PASS/CONDITIONAL PASS/FAIL。PASS后与BP结果一起解锁Approval。
```

---

## 9. Order Acceptance Workflow

### S09 开工对话

```
你是order-acceptance-workflow Spec主实现AI，负责T054～T059。

开始条件：Approval（V68）Review PASS并合并。Migration使用V69。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §5「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。只有偏离时才写「逸脱と根拠」。

顺序：
T054 F1 → T055 F2 →（T056 A1与T057 B1可并行）→ T058 B2 → T059 M。

Migration为V69。主AI拥有订单/验收状态机和报价→订单→合同转换。
F2后可分派订单/PDF和月次验收。B2必须等待B1完成。

测试范围按test-execution-policy-s03-s17.md：T054～T058只做L1～L3，T059执行一次L4全量。

实现订单号、明细、注文请、月次验收、差回、合同与请款闭环；保证重复转换、并发、月结和通知安全。
t_contract.acceptance_required 必须 NOT NULL DEFAULT TRUE
（允许NULL会变成「未设定＝不需要验收」，R3.3的未验收请款禁止就被突破）。
验收在提出时snapshot work record的version与金额，提出后工时变更不自动更新验收，用差回→再提出处理。
请款guard必须写进invoice生成query的WHERE句：
acceptance_required = FALSE OR EXISTS(已验收的acceptance)。不得用取得后的Java过滤。
顾客PO号重复只警告（仍可登记），同一原本hash的二重登记则拒绝——两者不要混同。
契约化用t_contract.order_line_id的UNIQUE＋状态CAS双重防御。

逐Task记录review-ledger，M执行全链路Demo。
```

### R09 Review 对话

```
这是Order Acceptance独立Review，范围T054～T059。

读取全局标准（含execution-review-handbook、platform-invariants、test-execution-policy-s03-s17）、
本Spec requirements/design/tasks/review-ledger，检查Base <填写> 到Head <填写> diff，不修改文件。

验证报价→订单→订单请→合同→月次验收→请款的ID链路，重复转换、状态跳转、PDF归档、月结限制、
验收差回和通知。

按design.md §5決定表逐项核对：
- acceptance_required 是否允许NULL（必须NOT NULL DEFAULT TRUE）
- 请款guard是否在SQL的WHERE句（不是取得后过滤）
- 提出后修改工时时验收金额是否被自动改写（应不变）
- PO号重复是「警告仍可登记」，原本hash重复是「拒绝」——是否被混同
- 契约化二重click时合同是否只有1件
- 顾客与内部代行同时验收时是否先到先得
- 月结checklist的未验收件数是否按阅览者的scope计数

检查金额、税、月份、订单明细与合同明细一致，权限和审计覆盖所有更新。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表和PASS/CONDITIONAL PASS/FAIL。PASS后允许Dispatch与Attendance并行。
```

---

## 10. Dispatch Compliance

### S10 开工对话

```
你是dispatch-outsourcing-compliance-ledger Spec主实现AI，负责T060～T066。

开始条件：Order（V69）Review PASS；G2正式确定官方格式、字段映射和法务负责人
（外部社労士/弁护士Review是M/本番gate，不阻塞开工）。Migration使用V70。
可与Attendance（V71）并行，但不得共享修改同一Contract方法。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §5「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。只有偏离时才写「逸脱と根拠」。

顺序：
T060 0 → T061 F1 → T062 F2 →（T063 A1、T064 B1、T065 B2可并行）→ T066 M。

Migration为V70。主AI拥有合规Canonical字段和Rule Service。
F2后分派Profile UI、法定帐票/Archive、Deadline/Risk。

测试范围按test-execution-policy-s03-s17.md：T060～T065只做L0～L3，T066执行一次L4全量。

系统只提示不足和要确认，不得自动作出法律结论。
limitation_date IS NULL 表示「未算定」而不是「无抵触日＝安全」，必须出MISSING_LIMITATION_DATE finding。
抵触日算定要考虑后续合同与组织单位变更，按design §5.2的合同chain全部case（连续更新/クーリング/
组织单位变更/并行合同）建fixture。クーリング天数放m_system_config，不写死在代码里。
既存4个compliance rule的code与行为必须维持，用golden fixture固定输出。
finding用(contract_id, code, condition_fingerprint)的UNIQUE做upsert，rule再执行不得重复insert，
也不得让ack过的finding回到OPEN。rule执行是read-only＋upsert，不改合同或考勤的业务状态。
待遇与个人信息的权限是字段级：HR/法务/管理者可见，マネージャー/营业mask，
且export与PDF适用同样的mask。

逐Task维护review-ledger，M由法务受入和系统回归共同完成。
```

### R10 Review 对话

```
这是Dispatch Compliance独立Review，范围T060～T066。

读取G2正式资料、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

重点检查派遣/准委任/请负分类字段、指挥命令、抵触日、交付记录、官方帐票、Archive Hash、
期限通知和「要确认」处理。

按design.md §5決定表逐项核对：
- limitation_date IS NULL 是否被当成「安全」（应为未算定→finding）
- 抵触日是否辿了合同chain（不是单个合同算）
- クーリング天数是否写死在代码里
- 既存4 rule的code/severity/message是否被改动（golden fixture）
- finding是否每次rule执行都insert（应upsert），ack过的是否回到OPEN
- rule执行是否改了合同或考勤的业务状态（应read-only＋upsert）
- 待遇/个人信息的mask是否在export与PDF也生效
- profile snapshot是否随主数据变化（应不变）
- 帐票生成的冪等键是否为(contract_id, document_type, template_version, snapshot_hash)

确认系统没有自动断言法律结论，字段与法务批准资料一致，历史版本可追踪。
如果在其他画面也嵌入compliance findings，确认是否像MonthlyClosingServiceImpl.canViewCompliance()
那样重新检查menu权限（不能假定画面自身的menu权限就够）。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、法务差异和PASS/CONDITIONAL PASS/FAIL。
```

---

## 11. Attendance Leave Overtime

### S11 开工对话

```
你是attendance-leave-overtime-compliance Spec主实现AI，负责T067～T074。

开始条件：Order（V69）Review PASS；G6已确定雇佣考勤的权威系统为本系统。
时间外计算的数值以overtime-rules.md为准且**已经确定**——社労士确认与法人别36协定的突合是
本番release gate，不是开工条件，不得因此阻塞。Migration使用V71。可与Dispatch（V70）并行。

完整阅读以下文件：
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

时间外计算的唯一的正是 overtime-rules.md。数值、边界方向、休日劳动的算入可否、
优先顺序、变更手顺全在那里。**本Spec不得重新决定这些值。**
特别注意：只有月100小时是 >= 判定（法条是「100时间未満」），其余上限是「以内」所以刚好等于上限是合规。

顺序：
T067 0 → T068 F1 →
主线T069 F2→T070 A1→T073 B2；
并行线T071 A2；
并行线T072 B1；
最后T074 M。

Migration为V71。Calculator和时间口径由主AI拥有。休假和Provider Sync可以分派。

测试范围按test-execution-policy-s03-s17.md：T067～T073只做L0～L3，T074执行一次L4全量。

雇佣考勤必须与客户工时、请款工时分离。差异表示是read-only DTO，
不得接到WorkRecordServiceImpl的金额计算或请款逻辑。

实装必须守的3条结构约束（overtime-rules.md §4 / design §5.2）：
1. 判定式写在OvertimeComplianceCalculator，一方法一规则。
2. 阈值不写死在代码里。解析顺序是m_overtime_agreement（法人别）→ m_system_config（overtime.*）→ 代码常量。
   没有协定行的法人是「判定不能」出finding，不得用默认值判「合规」。
3. 休日劳动的算入可否收敛在「传给calculator的输入选哪个」这一处，不把条件带进规则内部。

T068 F1同时seed overtime.*的config（INSERT IGNORE，不破坏既存值），
并加上m_overtime_agreement.valid_from只允许月初的约束。

验证45小时、360小时、720小时、100小时、复数月平均80小时、45小时超月数、跨日、休息、休假、
締め、修正、Provider重试和差异通知。维护review-ledger，M包含法务受入。
```

### R11 Review 对话

```
这是Attendance独立Review，范围T067～T074。

读取G6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
以及 overtime-rules.md（时间外计算的唯一的正），
检查Base <填写> 到Head <填写> diff，不修改文件。

验证雇佣考勤权威来源、客户工时分离、时间区间、跨日、休息、月/年累计、复数月平均、休假余额、
締め后修改、审批和Provider同步。

按overtime-rules.md逐项核对（这是本Spec最容易出事的点）：
- §1.1的6行比较运算符：只有规则4（月100小时）是 >=，其余是 >。写成 > 会漏判100小时正好的情况
- 休日劳动的算入：月45h/年360h/年720h不含，月100h/复数月平均80h含
- 所定休日劳动是否算进「时间外劳动」（不是法定休日劳动）
- rolling平均是否判定n=2..6全部，且跨协定年度计算（不重置）
- n个月数据不足时是否skip该n的判定（不得把不足月当0小时拉低平均）
- 阈值是否写死在代码里（应为agreement→config→常量的解析顺序）
- 没有协定行的法人是否被判成「合规」（应为判定不能→finding）
- special_clause=false时规则3～6是否被跳过
- 月中入退职是否被按比例换算（不应按比例）
- 时间是否用分的整数（不得用浮动小数）
- 适用除外者（管理监督者）是否被排除在规则1～6之外
- 差异确认前后请款金额是否不变
- 締め済み月是否能被freee/import的sync覆盖（应拒绝并出finding，不得静默覆盖或静默忽略）
- 营业是否能到达考勤画面/API（design §5.3的明示逸脱：营业不给考勤scope）
- 医师面谈/健康对应是否只保存实施要否/联络日/完了日（不得保存诊疗内容）

检查Calculator边界值和法定警告是否真实Assert，不能只看测试名称。
确认boundary fixture是读config值生成limit±1，而边界方向明示写死。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、法务未验证项和PASS/CONDITIONAL PASS/FAIL。
```

---

## 12. Staffing Capacity Planning

### S12 开工对话

```
你是staffing-capacity-planning Spec主实现AI，负责T075～T080。

开始条件：Dispatch（V70）与Attendance（V71）均Review PASS。Migration使用V72。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §5「決定表」3表和
§5.2「期间代数与FTE换算」已经确定，实装中不得重新决定。

顺序：
T075 F1 → T076 F2 →（T077 A1、T078 B1、T079 B2可并行）→ T080 M。

Migration为V72。主AI拥有Position/Allocation/Scenario模型和Proposal/Contract/Availability整合。
F2后分派Board、Heatmap/KPI和Scenario Compare。

测试范围按test-execution-policy-s03-s17.md：T075～T079只做L1～L3，T080执行一次L4全量。

必须处理兼务、配赋率总和、时间重叠、未来可用、需求缺口和Scenario隔离。
过配赋判定按**日单位**，不按月平均（月平均判定会误拒「前半60%、后半50%不重叠」）。
区间两端都是inclusive，所以「前end_date＝次start_date」是重叠，「前end_date翌日＝次start_date」不重叠。
position_id IS NULL 表示社内/待机，是业务值，不是未割当。
稼働率必须用既存的UtilizationCalcService，不得另行定义「稼働中/待机」。
plan与actual的排他用source_contract_id在需给集计SQL的WHERE句实现（不是memory filter）。
Scenario操作只更新t_staffing_scenario_allocation，不得改t_allocation_plan、合同或提案。
共享Scenario也不得越过阅览者的scope显示要员。
planning horizon最大24个月，超过要拒绝。不得在Java memory里做全engineer×全day的直积。

逐Task维护review-ledger，M验证性能及跨模块一致性。
```

### R12 Review 对话

```
这是Staffing Capacity独立Review，范围T075～T080。

读取全局规划（含execution-review-handbook、platform-invariants、test-execution-policy-s03-s17）、
本Spec requirements/design/tasks/review-ledger，检查Base <填写> 到Head <填写> diff，不修改文件。

验证Position、Allocation、Proposal、Contract和Availability的时间边界；
兼务与配赋率不能超过规则；Scenario不能污染正式数据。

按design.md §5決定表/§5.2逐项核对：
- 50+50许可 / 60+50拒绝 / 60+50不重叠许可 / 60+50只重叠1天也拒绝——4个case是否都有fixture
- 「前end_date＝次start_date」是否算重叠（两端inclusive）
- 过配赋判定是日单位还是月平均
- position_id IS NULL 是否被当成未割当（待机要员从需给计算消失）
- 稼働率是否用UtilizationCalcService（与dashboard KPI一致）
- plan/actual的排他是否在SQL的WHERE句
- Scenario操作前后t_allocation_plan/合同/提案是否不变（可用hash对比）
- 共享Scenario内的要员一览是否按阅览者scope过滤
- bench cost/单价带是否对HR mask
- 24个月上限超过时是否拒绝
- 配置确定事务内是否锁了对象要员的期间行（读到写之间的竞合）

检查Heatmap/KPI母集合、未来月份、空缺、取消和性能（全社=内訳合计）。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表和PASS/CONDITIONAL PASS/FAIL。PASS后Wave 2完成。
```

---

## 13. External Customer/BP Portal

### S13 开工对话

```
你是external-customer-bp-portal Spec主实现AI，负责T081～T087。

开始条件：Wave 2 PASS；G3正式确定Domain、本人确认和利用条款
（利用条款的外部法务承认是M/本番gate，不阻塞开工）；
G8已决定或采用推荐默认值（blocking=no，采用默认值时记录这一事实）。Migration使用V73。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表，但**本Spec是唯一不适用§2（授权母集合）
既定解的Spec**——portal user不是sys_user，没有DataScope、组织scope和menu权限。
母集合从portal_org → customer_id / bp_company_id 独立导出，不得复用既存scope service。
详见本Spec design.md §6「決定表」，已填好，实装中不得重新决定。

顺序：
T081 0 → T082 F1 → T083 F2 →（T084 A1、T085 A2、T086 B1可并行）→ T087 M。

Migration为V73。主AI独占SecurityConfig、Portal Security Chain和公开DTO边界。
本Spec的SecurityConfig变更必须先合并，engineer portal（S14）在其之后。
F2合并后分派客户Portal、BP Portal、管理/通知。

测试范围按test-execution-policy-s03-s17.md：T081～T086只做L0～L3，T087执行一次L4全量。

禁止向外部用户直接公开内部Entity/API。不得创建把PortalLoginUser转换成内部LoginUser的路径。
文件、文书、组织、邀请、Token、同意记录必须Fail-closed并使用Allow-list。
邀请token的一回性用DB CAS保证（UPDATE ... WHERE used_at IS NULL），
不得用应用侧的「存在检查→更新」。used_at IS NULL 只表示未使用，
还要验期限、email一致、组织一致共4个条件。
顾客检收委让给AcceptanceService，复用order spec的UNIQUE(contract_id, work_month)＋状态CAS，
不得在portal侧另建检收表或另建状态机。
PortalAuthorizationService在query boundary验证target→customer_id/bp_company_id，不是取得后check。

逐Task维护review-ledger，M执行渗透、权限和运维回归。
```

### R13 Review 对话

```
这是External Portal独立Review，范围T081～T087。

读取G3/G8、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

重点检查Security Chain匹配顺序、内部API暴露、邀请Token Hash/期限/重放、组织隔离、
客户与BP角色混淆、公开DTO字段、文件下载和通知链接。

按design.md §6決定表逐项核对：
- 顾客A/顾客B/BP的3组织matrix是否在**全endpoint × 全HTTP method**上parameterized（代表endpoint不够）
- 是否存在把PortalLoginUser转成内部LoginUser的路径
- 邀请token的一回性是否用DB CAS（不是存在检查→更新）
- used_at IS NULL 是否被单独当成有效（还需期限/email/组织共4条件）
- 顾客检收是否委让给AcceptanceService（不是portal侧另建状态机）
- 顾客portal与内部代行同时检收时是否先到先得
- 公开DTO是否结构性地无法返回原价/粗利/营业memo/他社信息
- 认可是否在query boundary（不是取得后check）
- 内部contact退职/无效化时portal access是否失效
- return URL是否只允许相对路径（open redirect）
- 未scan文件是否对内部也不公开

验证利用条款/同意版本、Session、CSRF、Rate Limit、ID枚举、跨组织访问和未知文件fail-closed。
确认内部SecurityConfig没被破坏（内部登录仍可用）。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、渗透结果和PASS/CONDITIONAL PASS/FAIL。
```

---

## 14. Engineer Self-service Portal V2

### S14 开工对话

```
你是engineer-self-service-portal-v2 Spec主实现AI，负责T088～T093。

开始条件：External Portal（V73）的Security Chain已先合并；
G9已决定或采用默认值（blocking=no，采用默认值时记录这一事实）；
Attendance和Staffing接口固定。Migration使用V74。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表；本Spec design.md §6「決定表」3表已填好，
是本Spec的正，实装中不得重新决定。

顺序：
T088 F1 →（T089 A1、T090 A2、T091 B1、T092 B2可并行）→ T093 M。

Migration为V74。最多同时3个子Agent，第四项顺序执行。
SecurityConfig不得由本Spec或本Spec的子Agent修改（External Portal的边界不能被破坏）。

测试范围按test-execution-policy-s03-s17.md：T088～T092只做L1～L3，T093执行一次L4全量。

实现本人Dashboard、Profile/Skill变更申请、工资/考勤入口、费用申请、1on1和Survey。
所有数据必须本人Scope，且**从engineer-account link解析本人，不接受请求里的engineerId**。
工资明细用/api/my/payroll专用endpoint，不得复用管理API（FreeePayrollApiController）。
sensitive响应加Cache-Control: no-store，并验证再认证/MFA context。
本人也不得看到：原价、commission、他要员信息、营业memo、retention risk的内部分数。
变更申请用field allowlist，禁止任意JSON→entity反映；allowlist外的key要拒绝请求（不是静默忽略）。
承认前Engineer master完全不变；承认→反映时再验target version，冲突则要求再申请（不自动合并）。
费用的会计连携用accounting_job_id的UNIQUE保证冪等。
confidential相談的可见范围只有HR＋指定管理者；private_note_ref不得进普通的RetentionRisk DTO，
也不得把原文显示在营业画面。Survey集计对未满最低回答数的组织非表示，未回答不算0分。

逐Task维护review-ledger，M验证冒用和隐私。
```

### R14 Review 对话

```
这是Engineer Portal V2独立Review，范围T088～T093。

读取G9、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

检查本人Scope、账号与Engineer绑定、工资信息、考勤、Profile变更、费用附件、1on1和Survey隐私。
验证管理者权限不能过宽，ID替换不能访问他人数据。

按design.md §6決定表逐项核对：
- 本人解析是否来自engineer-account link（API是否还接受engineerId参数）
- /api/my/payroll是否复用了管理API
- sensitive响应是否有no-store，MFA未实施时是否拒绝
- 本人是否能看到原价/commission/retention risk内部分数（都不应可见）
- 变更申请的allowlist外key是否被静默忽略（应拒绝请求）
- 承认前Engineer master是否不变（用SQL确认）
- 承认→反映时target version冲突是否自动合并（应要求再申请）
- applied_at IS NULL 与「承认済」是否被混同
- 费用的二重会计连携是否可能
- 未scan/感染的领収书是否对本人也不显示
- confidential相談是否出现在营业/マネージャー的响应里
- Survey未回答是否被算成0分，最低回答数未满的segment是否非表示

确认SecurityConfig没有破坏External Portal边界，审批、Archive、审计和通知正确。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表和PASS/CONDITIONAL PASS/FAIL。
```

---

## 15. Accounting Payment Integration

### S15 开工对话

```
你是accounting-payment-integration Spec主实现AI，负责T094～T101。

开始条件：Portal（V73/V74）、Order（V69）、BP（V66）、Archive（V64）均PASS；
G4正式确定freee Plan/API/会计权威来源（实freee plan与会社ID的未确认项是本番gate，不阻塞开工）；
G9费用方针已记录。Migration使用V75。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表（特别是§7外部连携）；
本Spec design.md §6「決定表」3表和error分类表已填好，是本Spec的正，实装中不得重新决定。

顺序：
T094 0 → T095 F1 → T096 F2 →
（T097 A1、T098 B1、T099 B2可并行）→ T100 B3 → T101 M。

Migration为V75。主AI拥有Canonical Accounting Model、Provider Interface、Job、Idempotency和状态机。
F2后可分派UI、销售、BP/费用。B3必须等待B1/B2。

测试范围按test-execution-policy-s03-s17.md：T094～T100只做L0～L3，T101执行一次L4全量。

本系统是SES业务明细的正，freee是会计帐簿/支付确定的正，**不得自建总账**。
外部API不得在DB Transaction中同步调用。business transaction只到job insert为止。
error分类按design §6.3：400/422=failed不retry、401=refresh 1次、403 plan=failed、
429/5xx/timeout=retryable。job claim用DB lock/CAS（WHERE status='pending'）。
idempotency_key加UNIQUE，同key不同payload_hash的再送要拒绝。
送信后要把canonical金额/税合计与provider response照合，不一致不得标succeeded。
token refresh在connection行的锁下只做1次（多job同时401会互相失效对方的token）。
verified_at IS NULL 的mapping表示未验证，送信前validation要停下（不得当成「不需要mapping」）。
外部only的取引不得自动生成内部记录，link/ignore理由由人确定。
token与秘密信息不得出现在任何API响应或日志（要写log capture test）。

逐Task维护review-ledger，M执行故障演练。
```

### R15 Review 对话

```
这是Accounting Integration独立Review，范围T094～T101。

读取G4/G9、Provider资料、gate-decisions-g1-g6、execution-review-handbook、shared-standards、
platform-invariants、test-execution-policy-s03-s17、本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

验证Canonical Mapping、法人/连接Scope、Idempotency、外部ID、重复发送、取消、Retry、Timeout、
Rate Limit、Correlation ID和CSV Fallback。

按design.md §6決定表/§6.3逐项核对：
- 400/422是否被retry（不应retry，应等人手修正）
- 401是否无限refresh（应只1次）
- 多job同时401时token refresh是否有竞合（应在connection行的锁下1次）
- idempotency_key的UNIQUE，以及同key不同payload_hash是否被拒绝
- 10次再送后外部是否只有1件
- 送信后金额/税合计与response不一致时是否仍标succeeded（不应）
- verified_at IS NULL 是否被当成「不需要mapping」
- 外部only的取引是否被自动生成内部记录
- 内部paid更新是否经过外部ID＋金额＋日期的照合
- 未承认的口座变更是否反映到振込先
- job worker是否调用了request scope的service，tenant context是否finally解除
- token/secret是否出现在响应或日志（log capture test是否存在）

检查销售、BP、费用、支付和月结对账，金额/税/日期/状态不能重复或遗漏。
外部调用不得位于长事务中。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、Sandbox/本番未验证项和PASS/CONDITIONAL PASS/FAIL。
```

---

## 16. JP PINT Digital Invoice

### S16 开工对话

```
你是jp-pint-digital-invoice Spec主实现AI，负责T102～T108。

开始条件：Accounting（V75）Review PASS；G5确定Certified Service Provider、Sandbox、认证方式、
文书种类和送信法人。**Provider契约/Sandbox未取得时，B1/B2/M不得判PASS**（本番gate）。
Migration使用V76。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger、Provider官方资料，
以及本Spec requirements.md、design.md、tasks.md。

既定解与决定表：platform-invariants.md 是既定解的表（特别是§7外部连携）。
HTTP/job/error/idempotency的基盤复用accounting spec，不重新实装。
本Spec design.md §5「決定表」3表和§5.2「金额的处理」已填好，是本Spec的正，实装中不得重新决定。

顺序：
T102 0 → T103 F1 → T104 F2 →（T105 B1与T106 A1可并行）→ T107 B2 → T108 M。

Migration为V76。主AI拥有CanonicalInvoice、Renderer和Validator。F2后分派Provider送信和UI。
受信Review在Provider Contract固定后执行。

测试范围按test-execution-policy-s03-s17.md：T102～T107只做L0～L3，T108执行一次L4全量。

**既存Invoice/InvoiceItem/税snapshot是唯一的正，JP PINT侧不得重新计算或覆盖。**
canonical变换只做映射。检算 line合计+税+rounding=total 不成立时**拒绝送信**，不得凑整对齐。
实装开始时重新确认数字厅最新的JP PINT version，把使用version保存到送信run，禁止无验证的自动upgrade。
verified_at IS NULL 的participant不得送信。
t_digital_invoice.invoice_id IS NULL 表示受信invoice（不是送信未紐付），要与direction一起判定。
webhook的署名验证对raw body进行（不是parse后的对象）；署名不正时记signature_valid=false且不迁移状态。
provider_event_id加UNIQUE；比当前state更旧的event只记录，不得回卷终端status。
受信invoice不得自动支付确定，必须经过review queue由人确定。
XML parse要禁用XXE/external entity/DTD。

逐Task维护review-ledger，M执行Provider受入。
```

### R16 Review 对话

```
这是JP PINT独立Review，范围T102～T108。

读取G5、当前JP PINT及Provider官方资料、gate-decisions-g1-g6、execution-review-handbook、
shared-standards、platform-invariants、test-execution-policy-s03-s17、
本Spec requirements/design/tasks/review-ledger，检查Base <填写> 到Head <填写> diff，不修改文件。

验证CanonicalInvoice映射、Schema/Business Rule、税、币种、Participant ID、送信状态、
重复Webhook、重试、受信Review和Archive。

按design.md §5決定表/§5.2逐项核对：
- 金额是否在JP PINT侧被重新计算或凑整（既存invoice是唯一的正）
- 检算不成立时是否仍送信（应拒绝）
- verified_at IS NULL 的participant是否被送信
- invoice_id IS NULL 是否被当成「送信未紐付」而让受信分出现在送信一览
- 署名验证是否对raw body（不是parse后对象）
- 署名不正时是否迁移了状态（应只记录signature_valid=false）
- 比当前state更旧的event是否回卷了delivered等终端status
- 同一invoice再送时message是否只有1件
- 受信的重复检知是否有message_id/supplier invoice number/payload hash三系统
- 受信invoice是否被自动支付确定（应经review queue）
- XML parse是否禁用XXE/external entity/DTD（要有fixture）
- 营业是否能看到XML本文（应只看送信済/未送信的别）

确认Provider版本固定且升级策略明确，Sandbox结果与Golden Fixture一致。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、Provider未验证项和PASS/CONDITIONAL PASS/FAIL。PASS后Wave 3完成。
```

---

## 17. AI Feedback Learning

### S17 开工对话

```
你是ai-feedback-learning Spec主实现AI，负责T109～T115。

开始条件：CRM（V67）、Proposal、Staffing（V72）和Outcome Source均完成；
G10明确采用mock/rule，或正式批准实Provider、DPA和PII发送
（G10/DPA未确定时不得把实数据发给外部provider，这是本番gate）。Migration使用V77。

完整阅读AGENTS.md、.kiro/specs/README.md、全局README/decision-log/gate-decisions-g1-g6/
execution-review-handbook/shared-standards/platform-invariants/test-execution-policy-s03-s17/
dependency-matrix/parallel-execution-plan/spec-execution-ledger，
以及本Spec requirements.md、design.md、tasks.md。
AI固有的PII规约以CLAUDE.md「AI機能開発時の注意事項（A8-01/A8-02関連）」为正。

既定解与决定表：platform-invariants.md 是既定解的表；
本Spec design.md §5「決定表」3表、§5.2的**禁止属性列表**和§5.4的PII边界已经确定，
实装中不得重新决定。禁止属性采用allowlist方式结构性防止，禁止列表作为leak检知的assertion使用
（denylist不会自动跟上新增字段）。

顺序：
T109 0 → T110 F1 → T111 F2 →（T112 B1与T113 B2可并行）→ T114 A1 → T115 M。

Migration为V77。主AI拥有AiExecutionGateway、PII Mask、Model/Prompt Version和Promotion规则。
F2后分派Feedback/Outcome与Offline Evaluation，Dashboard等待Evaluation API固定。

测试范围按test-execution-policy-s03-s17.md：T109～T114只做L0～L3，T115执行一次L4全量。

AI不得自动改变业务状态。除feedback/outcome登记之外，不得有修改提案、合同、邮件发送、人事判断的路径。
active version用use case×tenant的部分UNIQUE保证只有1个（应用侧的「先RETIRED再ACTIVE」会竞合出2个）。
shadow可以保存结果但不得用于用户显示和业务生成，要用test固定该路径不存在。
自动promotion禁止；metric阈值合格是必要条件不是充分条件，管理者承认必须。
outcome登记用UNIQUE(item_id, outcome_type, source_type, source_id)保证冪等。
feedback IS NULL（未判断）不得算成却下；outcome未发生不得算成失败。
过去的run记录在version切换后不变，rollback后只有新执行使用旧version。
raw prompt默认不保存，只保留redacted_summary_json与input_hash。
取込原文作为untrusted data分离，不作为命令解释，不给tool/action权限。
model response做JSON schema验证，不作为HTML render。
低件数segment非表示；机微属性不得作为matching特征量或segment轴。

逐Task维护review-ledger，M执行安全和回归验证。
```

### R17 Review 对话

```
这是AI Feedback Learning独立Review，范围T109～T115。

读取G10、gate-decisions-g1-g6、execution-review-handbook、shared-standards、platform-invariants、
test-execution-policy-s03-s17、CLAUDE.md的AI/PII规约、
本Spec requirements/design/tasks/review-ledger，
检查Base <填写> 到Head <填写> diff，不修改文件。

验证PII Mask、发送Allow-list、Model/Prompt Version、Run/Item/Feedback/Outcome关联、
指标母集合、Promotion权限和Rollback。

按design.md §5決定表/§5.2/§5.4逐项核对：
- 送信field allowlist是否与§5.2的禁止属性有交集
- 禁止属性出现在特征量或segment时是否停止执行（应fail-closed，不是警告后继续）
- 年龄是否被当特征量（应禁止），经验年数是否与年龄同时进特征量（不应）
- PII canary是否在provider request、日志、DB summary的任一处出现
- prompt injection fixture：取込原文中的命令是否被执行
- 是否所有AI调用都过AiExecutionGateway（有无绕过路径）
- active version是否可能同时存在2个（部分UNIQUE是否存在）
- shadow的结果是否流入用户显示或业务生成
- 是否存在自动promotion（应需管理者承认）
- outcome重复event是否二重登记
- feedback IS NULL 是否被算成却下；outcome未发生是否算成失败
- version切换/rollback后过去run记录是否被改写
- raw prompt是否仍在保存（应只有redacted summary与hash）
- model response是否作为HTML render
- 低件数segment是否非表示；HR是否可见（design §5.2：HR不可见，结构性防止流用于雇佣判断）

确认Mock/Rule默认安全，AI不能自动修改Proposal、Contract、Candidate等业务状态；
日志不泄露PII或凭证。检查Offline Evaluation可重复、Dashboard指标不混用版本和时间范围。
确认各provider设定下AI系Bean都唯一解析（@ConditionalOnExpression）。

指摘格式与再Review范围按execution-review-handbook.md §10/§11。
输出P0/P1/P2、Task追踪表、隐私/Provider未验证项和PASS/CONDITIONAL PASS/FAIL。
PASS后整个17 Spec Roadmap完成。
```
