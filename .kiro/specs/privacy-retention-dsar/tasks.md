# privacy-retention-dsar tasks

## 停止条件

DG-07、外部専門家gate、社内責任者gate、approved scope/owner/baseのいずれかが未確定なら、`0` と `D0` までで停止する。F1以降のチェックは、gate証跡が追加されたtaskでのみ更新する。

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
