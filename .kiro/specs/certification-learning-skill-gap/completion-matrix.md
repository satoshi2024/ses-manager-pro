# 完了対応表（NF-03 approved）

## Status

| 項目 | 値 |
|---|---|
| traceability | `APPROVED` |
| Start Head | `c29001e3` |
| F1 Head | `2f7bbac0`（独立 F1 Implementation Review PASS） |
| F1 判定 | **PASS**（P1-F1-01/02 VERIFIED_CLOSED。P2-F1-01〜17 は F2 で扱う） |
| 承認 Base | `76e45340` |
| migration | V115〜V124（F2-1〜F2-5。V124はF1 training relation tableの監査timestamp補正） |
| F2 | **完了**（F2-1〜F2-5実装・単体回帰済み。独立Review未実施） |

## F1 Task対応

| Task | commit | migration | 主要変更 | test | Demo | status |
|---|---|---|---|---|---|---|
| F1-1 | `ccaa77cc` | V115 | master/record、AES-256-GCM CNF1、crypto service | CryptoServiceTest、EngineerCertificationServiceTest、FlywayCertificationF11SchemaSmokeTest | DRAFT 申請＋暗号化保存 | [x] |
| F1-2 | `391f0907` | V116 | t_certification_event、CERTIFICATION_EVIDENCE FileScope | FileScopeValidationServiceTest（13件） | typed link/CLEAN/version/hash 否定系 | [x] |
| F1-3 | `5dd9d8c7` | V117 | course/plan/enrollment DDL+entity | MigrationScriptIntegrityTest | DDL shape（MySQL smoke は F1-5 統合） | [x] |
| F1-4 | `24577fd4`〜`3784a6a0` | V118 | skill/position events、service フック、`EffectiveIntervalSupport` | EngineerSkill/ProjectSkill/Position interval tests | replaceSkills で interval 閉鎖・supersedes | [x] |
| F1-5 | `e7d3b36d` | V119 | assessment/decision event DDL | FlywayCertificationLearningSkillGapSchemaSmokeTest | V115-V119 MySQL smoke | [x] |
| F2-1 | `ff8165ea` | V120 | 資格record lifecycle、event冪等、期限90/60/30、証憑pin/legal hold、PII permission seed | CertificationExpiryServiceTest、EngineerCertificationLifecycleServiceTest、CertificationEvidenceValidatorTest、FileScopeValidationServiceTest、AppConfigClockTest、EngineerCertificationServiceTest | 固定Clockで当日境界、訂正・取消・renew・CAS・証憑否定系を確認 | [x] |
| F2-2 | `d74ae5af` | V121 | training plan/enrollment lifecycle、既存ExpenseRequest/Approval正本、締めガード、plan event監査 | TrainingPlanServiceTest、ExpenseRequestFlowIntegrationTest、RouteResolverServiceTest | NULL/0/threshold±1、自己承認拒否、CAS、実費差額、締め済み月の経費正本更新拒否、承認後completionを確認 | [x] |
| F2-3 | `f5e69182` | V122 | event-only as-of supply/demand、taxonomy alias、unknown、PROJECT/POSITION/COMBINED precedence、snapshot replay、BP write-path集約 | SkillGapServiceImplTest、SkillGapTaxonomyResolverTest、EngineerSkillServiceImplTest、ProjectSkillServiceImplTest、FlywayCertificationLearningSkillGapSchemaSmokeTest | feature開始日前のunavailable、期間inclusive、DELETE当日/翌日、同義/未知、0件、replay、V122 MySQL schema、共通event writer経由を確認 | [x] |
| F2-4 | `8c8997cb` | —（既存V4/V79通知正本） | lifecycle-aware expiry scheduler、通知母集団、復職semantic key、multi-node unique/claim | CertificationNotificationPopulationResolverTest、CertificationExpiryNotificationSchedulerTest、NotificationServiceImplTest、NotificationOutboxServiceTest | 退職/休職/復職/account未link/manager変更、二重実行、DB/outbox DuplicateKey収束、Tokyo Clockを確認 | [x] |
| F2-5 | `78bbfcef`＋`9972dfc9` | V123〜V124 | SELF/MANAGER/HR_FINAL、公式projection境界、AI candidate-only、run/allowlist/期限、human accept/reject監査、training relation監査timestamp補正 | SkillAssessmentServiceImplTest、AiLearningCandidateServiceImplTest、MigrationScriptIntegrityTest、MessageBundleConsistencyTest、AllMappersSchemaSweepTest、FlywayCertificationLearningSkillGapSchemaSmokeTest | AI停止/error/timeoutでもrule gap維持、allowlist/run ID、期限内human accept/reject、期限切れ/AI-only拒否、公式skill/配置/採否非変更、MyBatis生成列とMySQL/H2 schema一致を確認 | [x] |

## F1 Implementation Review受領・F2持越し契約

| 項目 | 受領値 |
|---|---|
| Plan Review | **PASS**（R7） |
| F1 Implementation Review | **PASS** |
| F1本体 Head | `2f7bbac0` |
| 現worktree local/remote Head | `f73fcbc23852daa75f8224f8cc411418db4938f1` |
| F2〜M | `F2完了`（A1/A2/B1/B2/Mは未着手） |
| 現行migration | V124 |
| F2 migration | V120〜V124 |
| PR/merge/branch削除 | 禁止。M＋独立Implementation Review PASS後のみPR対象 |

## Review持越し項目（F2契約へ接続）

| 持越し | 対応Task | 必須契約・証拠 | status |
|---|---|---|---|
| `TYPE_DELETE`／DELETE当日as-of | F2-3、M | delete前にcancel/close eventを記録し、DELETE当日をeffective intervalに含める。削除後のcurrent rowを過去補完に使わない。DELETE当日・前日・翌日のas-of testを残す | [ ] |
| feature開始日前のposition update | F2-3、M | feature開始日前に有効なposition historyがない場合は`historical_data_unavailable`。現行positionを過去へ遡及適用しない。開始日前update fixtureをMySQLで確認 | [ ] |
| legal hold | F2-1、M | `CERTIFICATION_EVIDENCE`のdownload/export/disposalをlegal hold中はfail closed。DocumentServiceとFileScopeValidationServiceの両方でholdを再検証 | [ ] |
| 証憑version pin | F2-1、M | certification eventの`document_version_id`・hashと要求版を完全一致させ、CLEAN以外/version mismatch/hash mismatchを拒否 | [ ] |
| production `certification.pii.view` permission seed | F2-1、M | production migration/seedでpermission group/actionを登録し、role別full/masked/omitを実APIで確認。未seed時はfull revealをfail closed | [ ] |
| BP/別write pathのevent insert迂回防止 | F2-3、M | `EngineerSkillServiceImpl`、`ProjectSkillServiceImpl`、`PositionServiceImpl`の全create/update/status/delete経路を共通event writerへ集約し、直接mapper更新を回帰検出 | [ ] |
| PR前の最新`origin/main`取り込み・migration衝突再確認 | M／PR前gate | PR作成前に`git fetch origin`後の最新baseを専用worktreeへ取り込み、V120+とbase側migrationの衝突、schema/H2同期、Base..Head差分を再確認。F2中は未実施 | [ ] |

## 未検証（F2 以降）

- 資格 API/UI、90/60/30 scheduler E2E
- 証憑 upload E2E（DocumentService 連携）
- 複数 JVM 通知 dedupe
- production `certification.pii.view` 権限 seedの実DB/API確認
- NF-07 `CERTIFICATION_PII` 保持年数
- F2-5 AI artifact/runの実MySQL accept/reject E2E、A1/A2/B1/B2の画面・母集団接続
- P2-F1-14〜16: DELETE 当日 as-of、開始日前 position update、main 再取り込み（上表へ接続済み）

## Rollback

各 migration V124〜V115 を逆順で管理されたdown手順（未本番適用前提）。本番適用後はDROPせず、DB backupとリリースrollback計画に従う。コード/seedは各commitを逆順revertする。
