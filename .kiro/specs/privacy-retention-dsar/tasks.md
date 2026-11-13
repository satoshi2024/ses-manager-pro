# privacy-retention-dsar tasks

読み方（2026-11-14、[`status-alignment.md`](status-alignment.md) §1）: 本書の「Astra PASS」「受理」表記は実装側の転記であり、正式なReview verdictではない（review-ledger.md:22、C-17）。production bridge の段階計画は末尾「F1〜M」節と [`production-bridge-phased-plan.md`](production-bridge-phased-plan.md)。

## 2026-10-01 SYNTHETIC_FOUNDATION 作業列（未完了）

旧DEV-0/D0とFull Feature/Productionのtask状態は変更しない。新技術委任に基づく隔離合成incrementを別管理する。
Base: `1443fb68943849a3260d7ebe005259f3eb609d60`。計画固定Head `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b`はAstra技術Plan PASS。SF-W0完了。SF-W1固定Head `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`はAstra限定CODE PASS、対象3 test class 18/0/0/0。これはSF-W1 foundationのみで、SF-W2以降とFull Feature/Productionは未完了。
技術Plan PASSは実Privacy/Legal/production承認を代行しない。

| Wave | 実装と試験 | Demo・完了条件 |
|---|---|---|
| [x] SF-W0 | requirements/design/正本の整合、全source inventory差分、型/拒否/署名interfaceとfixture設計 | Astraが固定Head `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b`の技術PlanをPASS。policy UNKNOWNは未解決のまま |
| [x] SF-W1 | pure state machine、envelope verifier、test-only authority、隔離harness。active/default prod、未知schema、偽署名/失効/SoD/実URL注入拒否 | Astraは固定Head `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`を限定CODE PASS。generated fixtureのみ、実provider call=0、3 class 18/0/0/0。SF-W1のDemo/コード範囲だけ完了 |
| [ ] SF-RO-QUERY | NF03/NF08向け読み取り専用Policy照会契約（evaluate/catalog/source/CURRENT_CANDIDATE必須照合・三点照合/Policy-only型付け検証/説明専用DTO）。コード修正完了。AI-1による対象限定テスト42件（3クラス 42/0/0/0）およびFastスイート全4,292件の合格はコミット `c8b78dc3` における歴史的固定証拠であり（`verification-evidence/20261025-nf07-targeted-01`, `fast-01`）、本ラウンドでの新規テスト実行はなく全体のCOMPLETE状態は引き上げない | 42件の対象限定単体テスト合格（`PolicyReadonlyQueryServiceTest` 29件含む、c8b78dc3実績）。実行認可Capability不保持、本番配線/外部通信なし、Astra/総括独立CODE Review待ち。2026-11-14: 他文書の「Batch 3 独立CODE PASS済み」はrepo内に受理記録が無く、独立CODE verdictは UNKNOWN（C-11） |
| [x] SF-PROD-WIRING | NF07本番接線・意思決定契約設計（[`prod-wiring-decision-contract.md`](prod-wiring-decision-contract.md)）。Astra/総括P1指摘を受けR3改定。OidcLoginUser認証バインディング優先テナント解決・クレーム一致性検査・拒絶条件の厳格化、ログ証跡表現是正（アプリDB書き込みなし）、純粋検証カーネル分離と独立本番入口、完全0 DB書込、厳格呼び出し元マッピング、歴史スキャン分離、機能フラグ未実装明記を策定 | 設計策定完了。Astraおよび総括より限定 PLAN PASS 受理（コミット `aad2afeec71c2cb2eaa846bf02b2da0344990180`）。本番削除・外送・書込なし、`SyntheticEnvironmentGuard`維持、説明専用DTO維持 |
| [x] SF-KERNEL-EXTRACT-PLAN | 純粋Policy検証カーネル抽出・実施設計（[`pure-policy-verification-kernel-plan.md`](pure-policy-verification-kernel-plan.md)）。ApprovalEnvelopeVerifierから純粋計算を分段階ヘルパー群（Schema/Anchor比対/Scope照合/時間窓/失効判定/Ed25519/SoD/バインディング一致）として抽出。合成ラッパーによる順序オーケストレーション（Policy完備検証後にCase実行、先行バッチ照会禁止）、ラッパー末端でのみVerifiedApproval構築（カーネルは構築・返却しない）、入口ごとの時刻・例外契約（Policy-only SoDはPolicySecurityException、Case SoDはSecurityException）、および受け入れテストを定義 | 実施設計策定完了。Astraより限定 PLAN PASS 受理（コミット `93b40985`）。Java改修なし、DB書込なし |
| [ ] SF-KERNEL-EXTRACT-IMPL | 純粋Policy検証カーネル実装および単体テスト（Batch 2）。PurePolicyVerificationKernelの実装、ApprovalEnvelopeVerifierの委任化、オーケストレーション維持（Policy完備先行、先行バッチ照会禁止、ラッパー末端でのVerifiedApproval構築）、行動等価性・負向受け入れ単体テスト（PurePolicyVerificationKernelTest新規、ApprovalEnvelopeVerifierTest 7項目追加）。Astraおよび総括の独立CODE Review待ち（CODE PASS未取得） | コード改修完了、単体テスト追加完了。本番wrapper未実装、状態機械/環境防護/プロファイル改変なし、独立CODE Review待ち。2026-11-14: 2026-11-04 の受理はテスト履歴（57件・4,307件）であり CODE PASS ではないため、独立CODE verdictは UNKNOWN（C-10） |
| [ ] SF-W2 | [`sf-w2-protocol.md`](sf-w2-protocol.md)のAstra追加Plan PASS後に、event単位1 revisionのjournal CAS/compound authorization event、独立projection、最新watermark witnessを実装 | MySQL実並行でholdと認可順序、transaction中断時event/intent=0、journal ACK前call=0、old valid watermark拒否、profile/capability境界を検証。2026-11-14: W2a/W2b/D2b2 は合成隔離層として部分実装済み（下の注記、C-05）。本行の完了条件の全件証明は無いため `[ ]` のまま |
| [ ] SF-W3 | outbox worker、dispatchごとの期限/失効再検証、current/historical receipt verifier分離、lease/idempotency、UNKNOWN/partial/emergency stop | 認可commit〜HTTP間hold、送信前後crash、期限後result fact、rotation/compromise/backdate、authoritative query、wrong binding、二worker、stale fenceを検証。取消receiptなしでCANCELLEDにせず、in-flight/UNKNOWNを保持 |
| [ ] SF-W4 | restore quarantine、journal replay、DSAR合成export/redaction、NF03/NF08拒否integration | old snapshotへ新hold/intentを再適用、gap/改竄/遅延で公開不可、第三者情報0、反復hash一致 |
| [ ] SF-M | 全synthetic waveを固定RCで試験し独立Review | synthetic foundationの達成範囲と未完人工gateを記録。全NF07完成へ読み替えない |

SF-W3a preview persistence sliceは別途履歴として記録する。ledgerによれば固定Head `92d91d58172288406f6048e69ad38aa85880eb04` の限定CODE PASS（26/0/0/0、Astra受理）後、9 commitがcontrol `b6e2992b`へ統合済みで、対象はsynthetic preview persistence/as-of再検証/ACK_UNKNOWN/CASのみ。これはSF-W3全体またはSF-Mの完了ではない。`providerRead`/`crossScopeAccess`は動的fixture counter、provider write/dispatch/dispositionは`SyntheticPreviewPort`/service依存の構造上の能力不在をassertする契約であり、runtime回数0や全システムの外送不能性を意味しない。本追記は新たなruntime結果ではない。

W2以降のworker/処分/journal recoveryは追加protocolの技術Plan PASS前に開始しない。Journal、hold/fence、署名/SoD、recovery（SF-W2b Slice D）、preview（SF-W3a）は合成隔離層として部分実装・固定検証が存在しており、tasks旧記述を理由にこれら全体が未存在であると誤認してはならない。SF-W1 CODE PASSは独立journal、worker、restore recovery、NF07全featureの完成を意味しない。
migration採番は総括が管理し、旧migrationとV1を変更しない。実adapter、正式policy、交付/処分は別人工gate。
試験でDBを扱う場合もharness生成の隔離container/dataだけとし、既存DBやユーザーdataを再利用しない。

## 二段階Gateと停止条件

`NF07-DEV-GATE-20260828` により、`Task 0`、`D0`、`0.3`、`0.5`だけが `APPROVED_DEV_ONLY` である。Owner roleは `Project Maintainer（development-only）`、正式Privacy Ownerは `UNASSIGNED_UNTIL_PRE_PRODUCTION`。許可されるdataはsynthetic/redacted only、external I/Oは禁止、providerCall/writeは0、destructive operationは禁止する。

Full Feature / Production Gateは `BLOCKED` のままであり、F1-M、実PII接続、DSAR実配布、delete/anonymize/restrict writer、外部provider、production flag、PR/releaseを開始しない。0.4 coverage closureはpolicy承認が必要なため未完了のまま維持する。placeholder、口頭説明、wildcard/group表記は承認/coverage証跡とみなさない。DEV-0/D0 scopeに限る独立Implementation Review以外のImplementation ReviewはPLAN PASS後まで依頼しない。

2026-11-14 変更（依頼者の決定 Q-8）: 上の「F1-M を開始しない」のうち、**F1-M の実装着手**（値UNKNOWN・flag OFF・未知は一律拒否の形での実装とマージ）は停止の対象から外す。Full Feature / Production Gate は本番承認の gate として `BLOCKED` のまま維持し、実PII接続、DSAR実配布、delete/anonymize/restrict writer、外部provider、production flag の有効化は引き続き禁止する。B1/B2 の着手は署名後（Q-1）。詳細は下の「F1〜M」節の変更記録。

## 0. legal / PII inventory

- [x] **0.1 根拠と既存実装のinventory**
  - **Objective**: DB table/column、file/object、AI payload、audit/security/backupの保持境界を一つのinventoryにする。
  - **Implementation**: `pii-inventory.md` にschema、provider、owner/purpose/trigger、技術上の保持設定、hold、disposition、DSAR provider、未確定事項、根拠を記録する。
  - **Test requirements**: 指定されたAGENTS.md、NF-07/DG-07、platform-invariants、legal-document-ledger-archive、database-backup-recovery、enterprise-identity-security、audit、recruiting-pipeline、ai-feedback-learningを参照し、既存コード/schemaとの差分がないことを確認する。
  - **Demo**: raw PIIを記載せず、unknown/provisionalを候補扱いしないinventoryをReview packetに提示する。

- [x] **0.2 gate判定と停止境界**
  - **Objective**: 未承認の法的判断を実装へ持ち込まない。
  - **Implementation**: requirements/designに未置換placeholder、NF-07 CANDIDATE、DG-07未完、外部/社内gate未完、処分flag OFFを固定する。
  - **Test requirements**: 通常checkoutが変更されていないこと、専用worktree/branch/base/remote/statusを記録すること。
  - **Demo**: F1〜Mが未完了であるcompletion mappingを示す。

- [x] **0.3 migration/entity/provider mechanical coverage**
  - **Objective**: wildcardで完了扱いせず、全migration table/column、entity、provider/gateway/file/AI egress・log・cache・index・export・backup・replica候補を再走査する。
  - **Implementation**: `tools/privacy-retention-dsar/inventory-coverage.ps1` を追加し、inventoryの明示table reference、source manifest hash、inventory hash、件数、未マップtableをstdoutへ出す。DB/file/AIの未確定はUNKNOWN/BLOCKEDとする。
  - **Test requirements**: scannerがwrite/provider call 0で、source coverageの欠落または余分な列/provider参照時はexit code 2、構造coverageが揃ってもpolicy unknownは`COVERAGE_EXPLICIT_POLICY_UNKNOWN`として処分候補化しないこと、全source hashと生成結果が再現できることを確認する。
  - **Demo**: migration 116 file / 180 table / 4,279 CREATE column / 153 ALTER column record、entity 176 table、provider候補424 file（filename/content semantic scan）、source unique column 2,652、privacy catalog unclassified 0・policy unknown 78を出力し、未承認policyを候補化しない。

- [ ] **0.4 coverage closure**
  - **Objective**: 全migration/entity/providerの明示inventory rowとresult evidenceを揃える。
  - **Implementation**: 各table/column/providerへ owner、purpose、trigger、policy version、legal hold、disposition、DSAR provider、result evidenceを付与する。unknownは人の確認までBLOCKEDとする。
  - **Test requirements**: source coverageのunmapped/entity/provider missing=0、privacy catalog unclassified=0、inventory/source hash固定、AI egress/log/cache/file/index/exportおよびbackup/replicaのcoverageを独立Reviewで確認する。
  - **Demo**: structural coverageのexit code 0を確認しても、policy unknown、承認証跡、DG-07/外部gateが残る間はF1以降を開始しない。

- [x] **0.5 developer gate evidence validator**
  - **Objective**: 承認証跡・外部gate・policy coverageの欠落を、実装側が推測で埋めずに再現可能なhard stopへする。
  - **Implementation**: `gate-evidence-validator.ps1` は `DEV_0_D0` と `FULL_FEATURE_PRODUCTION` を分離し、development decisionの必須境界、78件を含むpolicy unknown、plan/task/Review provenance、専用worktreeとremote Headをread-onlyで検証する。承認値・法的結論・独立Review verdictは生成しない。
  - **Test requirements**: DEV decision fixtureでexit code 0、`DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW`、DEV scopeのみ許可、F1-M/本番処分/provider/PR許可falseを確認する。Full evidence欠落とDEV evidence欠落はexit code 2、`HARD_STOP`、providerCallCount=0、writeCount=0を確認する。
  - **Demo**: `pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator-test.ps1` が `gate-evidence-validator: DEV_ONLY_AUTHORIZED fixture PASS` を出力する。

## D0. read-only dry-run（今回の完了範囲）

- [x] **D0.1 offline no-write classifier**
  - **Objective**: candidate/blocked/unknownを理由付きで再現可能に分類する。
  - **Implementation**: `tools/privacy-retention-dsar/read-only-dry-run.ps1` はredacted JSONだけを読み、DB、filesystem、backup、replica、外部providerへ接続しない。raw PIIのkeyを拒否し、stdoutへsummaryを出す。
  - **Test requirements**: candidate、active hold、unknown policy、same-name ambiguity、scope外provider、audit保護、期限未到来をfixtureで確認する。入力sourceのhash/status以外を出力しない。
  - **Demo**: `dry-run-fixture.json` を `-InputPath` に渡し、exit code 0、candidate/blocked/unknownの件数、providerCallCount=0を確認する。

- [x] **D0.2 no-write safety checks**
  - **Objective**: dry-runから処分経路へ到達できないことを固定する。
  - **Implementation**: scriptにApply/DB connection/HTTP/File mutation parameterを持たせず、出力をstdoutだけに限定する。
  - **Test requirements**: forbidden raw PII key、missing asOf、missing records、out-of-scope providerを検証する。
  - **Demo**: fixture実行前後で対象ファイルのSHA-256が同一であり、HTTP/SQL invocationが0であることを確認する。

## F1〜M（2026-11-14 停止条件を変更: 署名前の実装着手可、本番開放は署名後）

### 変更記録

- **日付**: 2026-11-14。依頼者の「第3波決定」Q-1（段階化）と Q-8（署名前実装）。B-NF07-1 / B-NF07-2（2026-11-13）と Q-7（F1 DDL 承認）を前提とする。決定記録の所在は control repo `ses-claude-control-20261112-r1/reports/launch-decisions-20261113.md`（本repo外）。
- **変更前**: 見出し「F1〜M（DG-07完了まで実装禁止）」と、各行の「…がPASSになるまで着手しない」。
- **変更後**: 実装着手は署名を待たない。条件は (1) 法的な値はUNKNOWNのまま（コード・migration・設定の既定値に保持期間等を書かない）、(2) `privacy.retention.enabled` / `privacy.dsar.enabled` は既定OFF、(3) 未知（決定ID欠落、policy UNKNOWN、provider未登録、journal不達、trust anchor不明）は一律拒否。本番開放（flag ON、実PIIの処理、処分、DSAR成果物の交付、外部送信）の停止条件は変えず、各行の「本番開放条件」として残す。
- **範囲**: 第1期は F1・PERM・F2・A1（hold開始）・A2（半手動）。B1 自動処分と B2 recovery は着手も署名後（Q-1）。Q-8 は B1/B2 を前倒しする許可ではない。
- **理由**: production bridge の全接線（B-NF07-1）を外部署名の到着順に直列化しないため。署名は「コードをマージしてよいか」ではなく「本番で開いてよいか」の条件として扱い、値は署名後に設定と署名済み envelope で与える。
- **変えないもの**: `dev-gate-evidence.json`（2026-08-28 の決定記録）、`review-ledger.md`、0.4 coverage closure の未完了、plan.md の `FULL_FEATURE_PRODUCTION PLAN: BLOCKED`。`gate-evidence-validator.ps1` の FULL_FEATURE_PRODUCTION mode が HARD_STOP を返すことは本番承認が無い現状と一致するので、そのまま維持する。
- **F1 の定義の変更**: 旧 F1 の「job DDL」（`t_disposition_job` / `t_disposition_item`）は B1 へ移す（署名前に使わない表を作らないため）。
- **計画の正本**: [`production-bridge-phased-plan.md`](production-bridge-phased-plan.md)。batch ごとのファイル集合・DDL 草案・依存・対象限定テスト・完了条件・Demo・規模は同書 §3〜§4。

### batch

- [ ] **F1 catalog/policy/hold/request DDL（W3-14「F1 + PERM」）**
  - **Objective**: F1 表、flag、決定ID束縛、読み取り専用 policy source Bean、説明APIを値UNKNOWN・flag OFFでマージする。
  - **Implementation**: plan §3.1。migration は `V?__nf07_privacy_f1_core.sql`（暫定 V192）。
  - **Test requirements**: plan §3.1 の対象限定テスト（`FlywayNf07PrivacyF1MigrationTest` を含む）。
  - **Demo**: plan §3.1 Demo 1〜3。
  - **本番開放条件（旧停止条件）**: DG-07、owner、approved scope、policy version、二者承認、migration方針がPASS。
- [ ] **PERM privacy権限（W3-14「F1 + PERM」）**
  - **Objective**: `privacy.*` を `privacy-admin` 以外の全 group で deny し、`privacy-admin` だけに開く（B-NF07-2）。
  - **Implementation**: plan §6。migration は `V?__nf07_privacy_permission_seed.sql`（暫定 V193）。
  - **Test requirements**: `ActionPermissionMatrixTest` の変更（plan §6.5）、`PermissionGroupManagementServiceImplTest`、`BreakGlassServiceImplTest`、`SecurityContextCallerContextProviderTest`。
  - **Demo**: plan §3.1 Demo 2〜4。
  - **本番開放条件**: G-ID の割当規則、D-01。
- [ ] **F2 provider/search/dry-runの本番接続（NF07-F2、read-onlyのみ）**
  - **Objective**: 本番 read-only provider と registry、第三者 redaction 規則の枠。外部 provider は登録しない。
  - **Implementation / Test / Demo**: plan §3.2。
  - **本番開放条件（旧停止条件）**: table/file/AI/backup/replica providerのscope審査、redaction、unknown隔離がPASS。
- [ ] **A1 privacy dashboard/hold/approval（NF07-A1J + NF07-A1H、第1期は hold 開始のみ）**
  - **Objective**: 独立 Journal の本番 adapter と設定契約、hold 開始、既存削除経路の hold 照会。hold 解除は署名後の NF07-REL。
  - **Implementation / Test / Demo**: plan §3.3、§3.4、[`journal-witness-grant-design.md`](journal-witness-grant-design.md)。
  - **本番開放条件（旧停止条件）**: hold start/release authority、SoD、監査、emergency stopがPASS（D-05、G-DBA、G-BKP）。
- [ ] **A2 DSAR case/export/redaction（NF07-A2、半手動）**
  - **Objective**: 受付〜交付記録の追跡、read-only 検索、redaction 済み草案、二者承認。交付は system 外で人が行う。
  - **Implementation / Test / Demo**: plan §3.5。
  - **本番開放条件（旧停止条件）**: identity verification、同姓同名のhuman resolution、第三者redaction、deadline/appeal/reopenがPASS（D-06、G-AUD）。
- [ ] **B1 disposition batch（flag OFF）** — 着手は署名後（Q-1）。approved policyだけを対象に、retry/partial failure/wrong-target cancelを含むitem CASとevidenceがPASSになるまで着手しない。計画は plan §4。
- [ ] **B2 recovery/evidence** — 着手は署名後（Q-1）。DB+binary同時点backup、restore後hash/scope/hold再検証、監査証跡がPASSになるまで着手しない。計画は plan §4。
- [ ] **M release** — production flag OFF、approved policy allow-list、外部/社内責任者gate、independent ReviewのPLAN/IMPLEMENTATION PASS、remote Head固定後のみrelease候補とする。
- [ ] **開放は署名後・実装は前倒し可の batch（Q-8）**: NF07-REG（W-2）、NF07-TRUST（W-3）、NF07-REL（hold解除）、NF07-NF08-LINK。計画は plan §4、§8。
