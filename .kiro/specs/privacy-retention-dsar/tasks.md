# privacy-retention-dsar tasks

## 二段階Gateと停止条件

`NF07-DEV-GATE-20260828` により、`Task 0`、`D0`、`0.3`、`0.5`だけが `APPROVED_DEV_ONLY` である。Owner roleは `Project Maintainer（development-only）`、正式Privacy Ownerは `UNASSIGNED_UNTIL_PRE_PRODUCTION`。許可されるdataはsynthetic/redacted only、external I/Oは禁止、providerCall/writeは0、destructive operationは禁止する。

Full Feature / Production Gateは `BLOCKED` のままであり、F1-M、実PII接続、DSAR実配布、delete/anonymize/restrict writer、外部provider、production flag、PR/releaseを開始しない。0.4 coverage closureはpolicy承認が必要なため未完了のまま維持する。placeholder、口頭説明、wildcard/group表記は承認/coverage証跡とみなさない。DEV-0/D0 scopeに限る独立Implementation Review以外のImplementation ReviewはPLAN PASS後まで依頼しない。

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

## F1〜M（DG-07完了まで実装禁止）

- [ ] **F1 catalog/policy/hold/request/job DDL** — DG-07、owner、approved scope、policy version、二者承認、migration方針がPASSになるまで着手しない。
- [ ] **F2 provider/search/dry-runの本番接続** — table/file/AI/backup/replica providerのscope審査、redaction、unknown隔離がPASSになるまで着手しない。
- [ ] **A1 privacy dashboard/hold/approval** — hold start/release authority、SoD、監査、emergency stopがPASSになるまで着手しない。
- [ ] **A2 DSAR case/export/redaction** — identity verification、同姓同名のhuman resolution、第三者redaction、deadline/appeal/reopenがPASSになるまで着手しない。
- [ ] **B1 disposition batch（flag OFF）** — approved policyだけを対象に、retry/partial failure/wrong-target cancelを含むitem CASとevidenceがPASSになるまで着手しない。
- [ ] **B2 recovery/evidence** — DB+binary同時点backup、restore後hash/scope/hold再検証、監査証跡がPASSになるまで着手しない。
- [ ] **M release** — production flag OFF、approved policy allow-list、外部/社内責任者gate、independent ReviewのPLAN/IMPLEMENTATION PASS、remote Head固定後のみrelease候補とする。
