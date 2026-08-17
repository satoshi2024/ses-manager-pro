# 中央実行 ledger

## 1. 現在状態

- 計画基点: `99fbed8294dd1a6c320b4413b832f7c7b9292da1`
- 計画 branch: `codex/half-finished-readiness-specs`（PR #72 で main へ merge 済み）
- main 最新: **`8eea3fb4`（origin/main と同期済み・push 待ち）**。三 spec すべて merge 済み: HFP-01/HFP-02 は coordinator が 2026-08-16 に merge（HFP-01 merge commit → HFP-02 merge commit `3af17e38`）、HFP-03 は別 session が merge（`e892c5bd`、`.gitignore` fix `4dca8a46`、最終 PASS 記録 `5c639d79`→`e462779c`）、Review 記録 commit `8eea3fb4`。
- 最終更新日: 2026-08-17（横断統合Review Round 1 を実測して更新。推測による CLOSED なし）
- **横断Review verdict（2026-08-16）: NOT REVIEWABLE** → **欠落 field 解消（2026-08-17）**（HFP-01 merge delta PASS、HFP-02 G6 CONDITIONAL PASS）。
- **横断統合Review Round 1 verdict（2026-08-17・main `8eea3fb4`）: FAIL**。HFP 三 spec の merge delta・個別 Review に OPEN P0/P1 はないが、main 上の full suite（`verify-like-ci` 2206 run / 20 failure / 14 error / 0 skip）が BUILD FAILURE。原因は S14 起因の P1 finding **`HFP-CROSS-R1-P1-01`**（`engineer-schema-h2.sql` の `t_document_link` 列欠落で HFP-02 `CloudSignArtifactIntegrationTest` 含む 7 class 影響、leave 系 2 class 一意制約違反）。S14 owner の修正と main 回帰 green 達成後に横断 Round 2 を実施する。
- **欠落 field の解消（2026-08-17）**: ① は `HFP-01-REVIEW-20260817-merge-delta`（**PASS（merge delta）**・新規 P0/P1=0・REV-010 NOTE 1 件・独立再実行 2308/0/0/0 skip 0）で解消。② は `HFP-02-G6-REVIEW-20260817`（**CONDITIONAL PASS（merge delta）**・新規 P0/P1/P2=0・REV-015 NOTE 1 件・REV-009/014 VERIFIED_CLOSED・独立再実行 2246/0/0/0 skip 0）で解消。③（G01/G02 sandbox）は外部 credential 未提供のため従来どおり OPEN。**個別最終 PASS は HFP-01/HFP-02 とも未付与**（merge delta は合格、残 gate は外部依存のみ）。

| ID | spec | spec状態 | implementation状態 | 開始 gate | base/head | Review | 次 action |
|---|---|---|---|---|---|---|---|
| HFP-01 | payroll-management | READY | 001〜010実装済・011 BLOCKED。**main merge 済み**（`V102_4` 採番） | freee test company/API spike 未達（HFP-G01 OPEN） | branch head `6d3c2f10`（Round 4 まで Review 済み）→ merge-prep `28ccd99c`。main merge commit は `3af17e38` の祖先 | branch head Round 1〜4: REV-001〜009 全 CLOSED。**merge delta Review `HFP-01-REVIEW-20260817-merge-delta`: PASS（merge delta）**・新規 P0/P1=0・REV-010 NOTE（history assert 欠落の指摘。非 blocker） | ① REV-010 の NOTE 対応（owner: HFP-01 実装担当、次回 sandbox 対応時に）② `FREEE_*` credential 提供 → HFP-01-011（sandbox E2E＋AC13）→ 最終 PASS |
| HFP-02 | contract-document-esign | READY | 00〜08実装済・09/10 BLOCKED。**main merge 済み**（`V103_1` 再採番） | CloudSign正式API/sandbox spike 未達（HFP-G02 OPEN） | branch head `d958a813`（Round 3 まで Review 済み）→ merge-prep `292bfbbc`。main merge commit `3af17e38` | branch head Round 1〜3: REV-001〜014 全 CLOSED。**G6 merge delta Review `HFP-02-G6-REVIEW-20260817`: CONDITIONAL PASS（merge delta）**・新規 P0/P1/P2=0・REV-015 NOTE（3 message key の終止符差分。非 blocker）・REV-009/014 VERIFIED_CLOSED | ① REV-015 の NOTE 対応（owner: HFP-02 実装担当、次回 sandbox 対応時に）② BLK-01〜06（sandbox・運用承認・**BLK-06 `ADOPT/NOT_ADOPT` 業務決定**）→ HFP-02-09/10 → 最終 PASS |
| HFP-03 | database-backup-recovery | READY | 001〜012 全実装・全 task `[x]`。**main merge 済み**（`e892c5bd`） | 隔離 PITR 実証済み。**production-ready は HFP-03-PROD-001〜008 確定待ち（HFP-G03 OPEN）** | base `841e10aa` / branch head `1e34f47e`（merge 後 fix 含め main `4dca8a46`/`5c639d79`） | **最終 PASS**（merge 済み head `5c639d79` の独立 Review、2026-08-16T10:00Z。OPEN finding 0・RF-P1-01 VERIFIED_CLOSED。drill rpo=60s・RTO segment 実測・mid_dml_replayed=1・secret scan 0） | PROD-001〜008（特に PROD-004 deployment/cutover provider・PROD-006 承認 verifier・PROD-007 代表 profile）を発注者が確定 → HFP-G03 CLOSED → production 接続・復元・cutover 開始 |

`spec状態` は本計画 branch の独立文書 Review 後に `READY` へ変更する。production 実装の開始 gate が未達なら、spec が READY でも implementation は NOT READY のままとする。

証跡:
- merge 済み main（`e462779c`）の migration 整合: `V101, V102, V102_1〜V102_4(HFP-01), V103(S12), V103_1(HFP-02), V104, V104_1〜V104_3(S13)`。重複・conflict marker なし（`.gitignore` の RF-P1-01 は修正済み）。
- main 上の統合回帰（実測）: HFP-01 merge 後 2246/0/0/0 skip 0（coordinator）→ HFP-02 merge 後 2246/0/0/0 skip 0（coordinator、log `%TEMP%\opencode\main-hfp01-02-verify.log`）→ HFP-03 merge 後の main で S13 セッションが **L4 全量 2308/0/0/0・0 skip**（`3c908d61`）を記録。HFP-03 の merge-prep 検証も `mvn -B clean test` 2030/0/0/0 skip 0。**これらの統合回帰は自動 gate の証拠であり、merge delta の独立 Review の代替にはならない（handbook §7/§11）。**
- `verify-spec-package.ps1` は main `e462779c` 上で PASS（必須file/AC trace/task契約/local link の不整合 0）。
- 三 spec の Review verdict は各 spec の `review-ledger.md`（main merge 済み版）を正とする。中央 ledger には要約のみ載せる。

## 2. 固定 decision

| decision ID | 決定 | 根拠 | 変更条件 |
|---|---|---|---|
| HFP-D001 | 対象は freee給与、CloudSign、backup/PITR の3件だけ | 2026-08-12 source/spec監査 | 新しい再現証拠と発注者承認 |
| HFP-D002 | S01〜S17は対象外 | 発注者明示指示 | 発注者の明示変更 |
| HFP-D003 | freee給与一期は読み取り専用で金額を永続化しない | 既存要件とfreee API能力 | privacy/retentionを定めた別spec |
| HFP-D004 | provider adapterは公式契約とsandbox spikeの後に実装 | 誤endpoint/誤文書送信防止 | 変更不可 |
| HFP-D005 | 三specは別branch/worktree/主担当、独立Review | shared diffと判定の分離 | coordinatorが理由を記録 |
| HFP-D006 | Reviewは有限差分方式、P2/NOTEは要件違反でなければ次工程を永久blockしない | 指摘の重複と後付け要件防止 | 変更不可 |
| HFP-D007 | PITRのproduction applyは本実装/ReviewのDemo対象外。隔離環境のみで破壊的操作を行う | 本番data保護 | 正式change ticketと二者承認 |
| HFP-D008 | HFP-01 の `V102_2__freee_company_boundary.sql` は main 側 R23-P1-01 の `V102_2__compliance_gate_menu.sql` と version 衝突するため、merge-prep で **`V102_3` へ採番訂正**する（決定は coordinator、変更作業は HFP-01 主担当） | 2026-08-14 実測: origin/main に `V102_1__reviewer_verification_events.sql` と `V102_2__compliance_gate_menu.sql` が存在し、HFP-01 branch の同名 V102_2 と重複。Flyway 順序 102 < 102_1 < 102_2 < 102_3 < 103 を維持し、V103〜V108（S12〜S17 予約）と V109（HFP-02）を回避 | **2026-08-16 実施時に訂正（→ HFP-D012）**: main 側に `V102_3__compliance_gate_dynamic_policy_repair.sql` が追加され V102_3 も使用済みになったため |
| HFP-D009 | HFP-02 の `V109` は現 main と非衝突。branch に含まれる S12〜S17 予約の V110〜V115 繰上げ（docs-only 20 file）は origin/main が該当 file を変更していないため clean merge 見込みだが、merge-prep で `SpecDispatchConsistencyTest` を再実行して整合を実証する | 2026-08-14 実測: origin/main の 17 commit は `customer-product-expansion-2026` 配下を 1 file も変更していない | **2026-08-16 実施時に訂正（→ HFP-D013）**: main 側で S12 の V103 が実在化し、docs 繰上げが main の実態と衝突したため |
| HFP-D010 | merge 順は HFP-01 → HFP-02 → HFP-03 を維持。HFP-03 は `ops/backup/**` と文書のみなので dirty worktree の commit 完了後に単独先行 merge 可 | dependency-and-ownership §5 | 順序変更は本 ledger へ理由と競合確認を記録 |
| HFP-D011 | 各 spec の merge-prep は必ず main `ec2c5cee` を取り込んだ後に実施し、shared file の競合と role matrix を merge ごとに再検証する | 2026-08-14 実測の競合一覧: `SecurityConfig`（main+11/HFP-01+5）、`application.yml`（main+10/HFP-01+12/HFP-02+29）、`messages*`×4（main+29/HFP-01+15/HFP-02+6）、`ApiAuditFilter`（HFP-01+5 と HFP-02+1 の cross-branch）、`engineer-schema-h2.sql`（main+91/HFP-01） | 変更不可（merge 後の `verify-like-ci` skip 0 が必須 gate のため） |
| HFP-D012 | HFP-01 の migration は **`V102_4`** を正とする | 2026-08-16 merge-prep 実測: main 側に V102_1/V102_2/V102_3 が実在し V102_3 も使用済み。V102_4 は順序 102 < 102_1 < 102_2 < 102_3 < 102_4 < 103 を満たし、S13〜S17 予約（V104〜V108）と非衝突。branch 上で `git mv`＋smoke test 改名＋doc 同期済み（branch head `28ccd99c`） | 適用済み migration 化した後は変更不可 |
| HFP-D013 | HFP-02 の migration は **`V103_1`** を正とし、S12〜S17 予約文書の繰上げ（旧 V110〜V115 案）は撤回して main 版へ復旧する | 2026-08-16 merge-prep 実測: main 側に S12 の V103 が実在。V103_1 なら順序 V103 < V103.1 < V104 で全予約文書の変更が不要（`SpecDispatchConsistencyTest` green を実証済み）。branch 上で `git mv`＋history 検証（`version='103.1'`）＋`customer-product-expansion-2026`/S12〜S17 spec 文書 20+ file を main 版へ復旧済み（branch head `292bfbbc`） | 適用済み migration 化した後は変更不可 |

## 3. Release gate register

| gate ID | spec | 条件 | owner | 期限 | 証拠 | block範囲 | 状態 |
|---|---|---|---|---|---|---|---|
| HFP-G01 | HFP-01 | freee test companyで users/me・employee・salary・bonus を取得 | 未設定 | 未設定 | 実測なし。branch ledger `HFP-01-RUN-ISSUE-01`: `FREEE_*` 環境変数未提供のため HFP-01-011 BLOCKED | provider adapter/最終PASS | OPEN |
| HFP-G02 | HFP-02 | CloudSign正式API文書、credential、sandbox送受信を確認 | 未設定 | 未設定 | 実測なし。branch ledger: HFP-02-BLK-01（sandbox申請依頼済み 2026-08-14）と BLK-02〜06 が OPEN | provider adapter/最終PASS | OPEN |
| HFP-G03 | HFP-03 | 隔離MySQL 8 + repository + uploads fixtureでPITR可能 | 発注者（PROD-001〜008 確定） | 未設定 | 隔離実証は完了（branch ledger: integration SUCCESS・drill rpo=60s・mid_dml_replayed=1・secret scan 0・GATE-01〜05/07/08 PASS）。**残りは production 固有値 HFP-03-PROD-001〜008（特に PROD-004 deployment/cutover provider・PROD-006 承認 verifier・PROD-007 代表 profile）の発注者確定のみ**。確定後は `baseline.md` §4 の再実行手順に従う | 最終PASS（production-ready 判定） | OPEN |
| HFP-G04 | ALL | merge後 `verify-like-ci` zero failure/error/skip | coordinator | 2026-08-17 | 過去 head（e462779c）では 2308/0/0/0 skip 0 達成。現 head `8eea3fb4` では S14 起因 P1（HFP-CROSS-R1-P1-01）により **2206 run / 20 failure / 14 error / 0 skip（BUILD FAILURE）** となり不成立。S14 修正後に再実証 | 全体release | **OPEN** |

## 4. spec完了 packet

各主担当は実装完了時に次を一行ずつ追記し、既存行を書き換えて履歴を消さない。

```text
### <date> <HFP-ID> REVIEWABLE
- base/head:
- completed task IDs:
- requirements/acceptance trace:
- changed files:
- test evidence:
- sandbox/isolated Demo evidence:
- skipped/unverified:
- open gates/issues:
- rollback:
- requested review:
```

## 5. Review履歴

独立 Reviewer が次を追記する。

```text
### <date> <HFP-ID> Review Round <n>
- base/head:
- packet completeness:
- verdict:
- P0/P1/P2/NOTE count:
- open issue IDs:
- verified evidence:
- release gate changes:
- next action:
```

### 2026-08-16 HFP-01 merge-prep・main merge（coordinator 実施）

- base/head: branch base `841e10aa` → main `5246783a` 取り込み（`8858bc74`）→ 採番訂正 `28ccd99c`。main merge commit は `3af17e38` の第一親側に含まれる。
- 変更: `V102_2__freee_company_boundary.sql` → `V102_4` へ `git mv`。smoke test 改名（`FlywayV102_4FreeeCompanyBoundarySmokeTest`）、H2/コメント/doc（research §7・design §4.3・review-conversation・review-ledger 追記）を同期。production 意味の変更なし。
- 検証: branch 上 `verify-like-ci` 2144/0/0/0 skip 0。main 上 2246/0/0/0 skip 0。MySQL smoke 5 class 9/0/0/0。
- verdict: merge-prep は変更差分（採番のみ）を確認済み。最終 PASS は sandbox（G01）と merge delta 独立 Review の後。

### 2026-08-16 HFP-02 merge-prep・main merge（coordinator 実施）

- base/head: branch base `841e10aa` → main `ec6df710`（HFP-01 込み）取り込み → merge-prep `292bfbbc`。main merge commit `3af17e38`。
- 変更: `V109__contract_document_cloudsign_dispatch.sql` → `V103_1` へ `git mv`＋history 検証更新。S12〜S17 予約繰上げ docs（`customer-product-expansion-2026` 配下 20 file＋S13〜S17 spec dir の design/tasks 10 file）を main 版へ復旧。`application.yml`（cloudsign 設定を main 版へ挿入）・`messages×4`（6 key を追加）を手動統合。`ApiAuditFilter` は HFP-01 の payroll 除外と HFP-02 の artifact download 判定が両立することを確認。
- 検証: branch 上 `verify-like-ci` 2246/0/0/0 skip 0。main 上 2246/0/0/0 skip 0。MySQL smoke 5 class 9/0/0/0（`FlywayContractDocumentDispatchSchemaSmokeTest` は `version='103.1'` で PASS）。
- verdict: merge-prep は変更差分（採番＋docs 復旧＋config 統合）を確認済み。最終 PASS は sandbox（G02）・運用承認（BLK-01〜06）・G6（merge delta 独立 Review）の後。

### 2026-08-17 HFP-01 merge delta 独立 Review（§8.1 packet への応答）

- Reviewer: 独立 Review AI（coordinator・実装AI と別対話）。base `6d3c2f10` / head `28ccd99c`。
- verdict: **PASS（merge delta）**。新規 P0/P1=0。NOTE 1 件（HFP-01-REV-010: smoke test に history version assert が無い。schema 効果 assert と実 MySQL 適用順は一致しており要件違反ではない）。
- 独立再実行: migration 契約 48/0/0/0・freee 15 class 139/0/0/0・実MySQL smoke 4/0/0/0・`verify-like-ci` **2308/0/0/0 skip 0**（script 末尾の HFP-03 WSL integration 段は本機 WSL 不足で exit 1＝対象外）。
- 記録: spec ledger `payroll-management/review-ledger.md` に `HFP-01-REVIEW-20260817-merge-delta` 節を追記（行 1124〜1172。追記のみ・未 commit）。
- 残 gate: G01（sandbox）のみ。spec 全体の最終 PASS は未付与。

### 2026-08-17 HFP-02 G6 merge delta 独立 Review（§8.2 packet への応答）

- Reviewer: 独立 Review AI（coordinator・実装AI と別対話）。base `d958a813` / head `292bfbbc`（merge 結果 `3af17e38`）。
- verdict: **CONDITIONAL PASS（merge delta）**。新規 P0/P1/P2=0。NOTE 1 件（HFP-02-REV-015: merge-prep 手動統合時に messages_en/ko/zh_CN の 3 key に終止符が追加され branch head と text 差分。key 名・意味不変）。REV-009/REV-014 は本差分で閉塞（VERIFIED_CLOSED）。
- 独立再実行: migration 契約＋bundle 43/0/0/0・CloudSign/ContractDocument 106/0/0/0・実MySQL smoke 6/0/0/0・`verify-like-ci` 2246/0/0/0 skip 0（固定 head `3af17e38` の隔離 worktree。main worktree は稼働中アプリの jar lock のため回避）。
- 記録: spec ledger `contract-document-esign/review-ledger.md` に `## 9. HFP-02-G6-REVIEW-20260817` 節を追記（約 L306〜360。追記のみ・未 commit。既存行の行末ノイズは混合改行＋`.gitattributes` eol=lf による表示差分で内容は無改変）。
- 残 gate: G2（sandbox）・G5（運用承認）。spec 全体の最終 PASS は未付与。
- scope 外観測（HFP 非帰属・S14 owner へ引き継ぎ）: 現在の main head（S14 進行中）では `MessageBundleConsistencyTest`（`my.payroll` key）と `SpecDispatchConsistencyTest`（S14 V105 予約不整合）が 2 件失敗する。固定 head `3af17e38` では発生せず。S14 セッションの進行中差分に起因。

### 2026-08-17 横断統合Review Round 1（main `8eea3fb4`）

- Reviewer: 横断 Reviewer。対象: main `8eea3fb4`（三 spec merge 済み＋S14 進行中 commit 混在）に対する横断判定。
- verdict: **FAIL**
- P0/P1/P2/NOTE: P1=1（`HFP-CROSS-R1-P1-01`、S14 起因・HFP 非帰属）
- packet completeness: `verify-spec-package.ps1` PASS（HFP-01 AC15/T11、HFP-02 AC60/T11、HFP-03 AC36/T12）。各 ledger に trace/test/Demo/BLOCKED/rollback あり、中央 ledger と状態一致。
- 横断 finding: **`HFP-CROSS-R1-P1-01` (P1・OPEN)**: main `8eea3fb4` で CI 同条件の full suite が 2206 run / 20 failure / 14 error / 0 skip で BUILD FAILURE。
  - root cause (a): `t_document_link.skill_sheet_confirmed_at/version` が V1:774-775・V105:180-181・entity `DocumentLink.java:35,38` に存在する一方、H2 test schema（`engineer-schema-h2.sql`）に欠落（AGENTS.md の H2 同期 rule 違反）。これにより HFP-02 の `CloudSignArtifactIntegrationTest`（15 中 7 fail/error）含む 7 class が影響。
  - root cause (b): leave 系 2 class の `T_ENGINEER_ACCOUNT_LINK` 一意制約違反。
  - 両因とも S14 commit 群（`c06042f2`〜`d2944e27`）に起因し、HFP merge delta 起因ではない（`3af17e38..8eea3fb4` で HFP の production file・migration・H2 schema は無変更）。
- 観点 1〜9 の HFP 側: migration 番号整合・config 非上書き・security 境界・secret scan 0 件・fail-closed 起動・consumer 非破壊・HFP-03 script fail-closed・merge 完全性はすべて合格。
- release gate: G01/G02/G03 OPEN、G04 OPEN（回帰失敗により不成立）。
- 次 action:
  1. S14 owner が `HFP-CROSS-R1-P1-01` を修正（`engineer-schema-h2.sql` の `t_document_link` へ 2 列追加＋leave 系 root cause）→ main で verify-like-ci skip 0 green を再実証
  2. fix delta と直接回帰のみを確認する横断 Round 2 で再判定
  3. G01/G02 の owner/期限/再実行手順/本番 block を確定
  4. G03 PROD-001〜008 確定 → HFP-03 production-ready

### 2026-08-16 横断Review（全体判定試行）

- Reviewer: 横断 Reviewer（coordinator とは別）。対象: 三 spec merge 後の main `e462779c` に対する全体判定。
- verdict: **NOT REVIEWABLE**。
- 欠落 field: ① HFP-01 merge delta 独立 Review 未実施（branch head `6d3c2f10` の Round 1〜4 のみでは個別最終 verdict を固定できない）② HFP-02 G6 merge delta 独立 Review 未実施（REV-009/014 は「merge時対応」記録のみで監査済みではない）③ G01/G02 の sandbox evidence（L4）未固定 → 個別 PASS の前提不成立のため横断 PASS・CONDITIONAL PASS とも不成立。
- 充足済み: HFP-03 は merge 済み head `5c639d79` で最終 PASS（残は G03 のみ）。base/head・merge commit・release gate は中央 ledger に固定済み。
- next action: §8 の packet を独立 Reviewer へ渡し、HFP-01 merge delta Review と HFP-02 G6 を実施する（外部 credential 不要・即時着手可）。G01/G02 は外部提供待ち。

### 2026-08-16 HFP-03 merge・最終 PASS（別 session 実施分を coordinator が照合して転記）

- merge: `e892c5bd`（branch `1e34f47e`＋origin/main 取り込み `8a65156b` を main へ merge）。merge-prep 検証: `mvn -B clean test` 2030/0/0/0 skip 0＋backup unit 430 assert PASS＋shellcheck 0＋integration SUCCESS（mid_dml_replayed=1・drill rpo=60s・secret scan 0）。
- 最終 Review: merge 済み commit `e892c5bd` の直接 Review で `.gitignore` の未解決 conflict marker を検出（HFP-03-RF-P1-01）→ `4dca8a46` で修正 → 独立 Reviewer が merge 済み head `5c639d79` を再 Review して **PASS**（OPEN finding 0・RF-P1-01 VERIFIED_CLOSED・GATE-01〜05/07/08 PASS）。
- production-ready は保留: HFP-03-PROD-001〜008（特に PROD-004/006/007）が発注者により確定し HFP-G03 が CLOSED になるまで、production への接続・復元・cutover を開始しない。
- 証跡は branch 版 `database-backup-recovery/review-ledger.md` §7/§8（main に merge 済み）を正とする。

## 6. 全体完了条件

- HFP-01〜03 の各 specが merge済み head で `PASS`。
- HFP-G01〜04 が `CLOSED`。
- 未管理 acceptance、OPEN P0/P1、秘密情報混入が0。
- main統合後の直接回帰と `verify-like-ci` が成功。
- 本 ledger と各 `tasks.md` / `review-ledger.md` の状態が一致。

## 7. branch/worktree・所有権・次の着手（2026-08-16 三 spec merge 後）

| spec | branch / worktree | 主担当（実装AI） | Reviewer | shared file owner | 残 gate と次 action |
|---|---|---|---|---|---|
| HFP-01 | `codex/hfp-01-payroll-freee`（**main merge 済み**。origin の branch ref は旧 head `6d3c2f10` のまま＝merge-prep commit は main 経由で push 済み） | 既存の HFP-01 専任対話 | 独立 Review 対話（Round 4 まで完了） | `FreeeIntegrationService*`、`V102_4` | 残 gate: `FREEE_*` credential（G01）→ HFP-01-011 → merge delta 独立 Review で最終 PASS |
| HFP-02 | `codex/hfp-02-contract-cloudsign`（**main merge 済み**。origin の branch ref は旧 head `d958a813`） | 既存の HFP-02 専任対話 | 独立 Review 対話（Round 3 まで完了） | `ContractDocument*`/`CloudSign*`、`V103_1` | 残 gate: BLK-01〜06（sandbox・運用承認・**BLK-06 `ADOPT/NOT_ADOPT` 業務決定**）→ HFP-02-09/10 → G6 |
| HFP-03 | `codex/hfp-03-backup-pitr`（**main merge 済み** `e892c5bd`・origin push 済み `1e34f47e`） | 既存の HFP-03 専任対話 | 独立 Review 対話（最終 PASS 済み） | `ops/backup/**` | 残 gate: **PROD-001〜008 の発注者確定（G03）** のみ。確定後は `baseline.md` §4 の再実行手順で production-ready 判定 |
| coordinator | main worktree（head `e462779c` = origin/main。dirty: `execution-ledger.md`＋利用者未コミットの seed/pom/run-app 等） | 本対話 | - | `execution-ledger.md`、採番決定、merge 順 | 本 ledger を発注者確認付きで commit。HFP-G01/G02/G03 の CLOSED を待って最終 PASS 判定へ |

運用上の注意（実測で確認済み）:

- 三 spec の production code はすべて main（origin と同期）に merge 済み。残りは (a) **merge delta 独立 Review 2 件（§8 packet 済み・外部依存なし・即時着手可）**、(b) 外部 credential（G01/G02）と production 固有値（G03）。repository 内で閉じる作業はこの 2 Review だけである。
- 横断Review は NOT REVIEWABLE（2026-08-16）。個別 Review 未完了のまま横断 PASS を主張しない。
- HFP-01/HFP-02 の origin branch ref は merge-prep 前の head のまま。必要なら発注者確認後に `git push origin codex/hfp-01-payroll-freee codex/hfp-02-contract-cloudsign`。
- main worktree の HFP と無関係な利用者未コミット差分（`pom.xml` mainClass、seed-scale-300、`run-app.bat` 等、他 spec の browser evidence 再生成物）は保護したまま。HFP merge はこれらを巻き込んでいない。
- 全体完了条件（README §4/§6）は「G01〜04 全 CLOSED＋各 spec 最終 PASS」であり、現時点では G01/G02/G03 が OPEN かつ **HFP-01/HFP-02 の merge delta 独立 Review が未実施**のため**プログラムは未完了**。実装・branch head Review・merge・統合回帰は完了している。

## 8. merge delta 独立 Review の引き渡し packet（2026-08-16 coordinator 作成・Review 未実施）

横断 Review は **NOT REVIEWABLE**（2026-08-16）。以下の二 Review は外部 credential を必要とせず、独立 Reviewer の別対話へ即時引き渡し可能。coordinator は自己判定せず、Reviewer だけが verdict を付与する（handbook §7/§11）。

### 8.1 HFP-01 merge delta Review packet（merge 済み main 上の差分）

- 対象 spec: payroll-management。base（branch head・Round 4 まで Review 済み）: **6d3c2f10**。reviewed merge-prep head: **28ccd99c**（= 8858bc74 main 5246783a merge ＋採番訂正 commit）。main 上の merge 結果: 3af17e38 の第一親側。
- merge-prep で coordinator が行った変更（Review 対象の本体）:
  1. V102_2__freee_company_boundary.sql → V102_4 へ git mv（main 側 V102_2/V102_3 との衝突回避。決定 HFP-D012）
  2. FlywayV102_2FreeeCompanyBoundarySmokeTest → FlywayV102_4FreeeCompanyBoundarySmokeTest（クラス名・history 検証は ersion='102.4' 相当）
  3. FlywayMigrationSmokeTest/FreeeCompanyBoundarySchemaH2Test/schema-freee-payroll-h2.sql のコメント、esearch.md §7、design.md §4.3、eview-conversation.md、eview-ledger.md（追記）の同期
  4. main 5246783a merge の auto-merge 結果: SecurityConfig・pplication.yml・messages×4・engineer-schema-h2.sql（freee 差分と main 差分の共存確認）
- Review 範囲: 6d3c2f10..28ccd99c の HFP-01 由来差分と、main merge 後の shared file 上の role matrix（payroll の静的 rule・no-store・監査1row契約・freee 設定）が main 側変更で弱まっていないこと。main 側の staffing/compliance/portal 差分そのものは各 session の Review 対象であり本 Review の範囲外。
- 再実行 command: mvn test -Dtest='ReviewerVerificationMigrationOrderContractTest,SpecDispatchConsistencyTest,MigrationScriptIntegrityTest,FreeeCompanyBoundarySchemaH2Test,FreeeOAuthContractTest,FreeeOAuthCallbackWebTest,FreeeHrContractTest,FreeeEmployeeMappingTest,PayrollReadModelTest,PayrollSecurityAuditTest,PayrollLandmarkA11yTest,FreeeIntegrationServiceApiTest,FreeeAttendanceProviderTest,PaymentReconciliationServiceImplTest,CashFlowForecastServiceTest,MessageBundleConsistencyTest,JsSyntaxCheckTest,FreeeReauthPersistenceTest,FreeeConcurrentRefreshTest'、FlywayMigrationSmokeTest,FlywayV102_4FreeeCompanyBoundarySmokeTest（実MySQL）、scripts/verify-like-ci.ps1（merge 済み head の実測は 2246/0/0/0・skip 0。Reviewer が独立再実行して確認）。
- 判定対象 acceptance: HFP-01-AC14（main 上の S11/S15/CashFlow/migration/全回帰）、HFP-01-AC15 の merge delta 部分。AC13/AC15 の sandbox 部分は G01 のため本 Review の範囲外（BLOCKED 維持）。

### 8.2 HFP-02 G6 merge delta Review packet（merge 済み main 上の差分）

- 対象 spec: contract-document-esign。base（branch head・Round 3 まで Review 済み）: **d958a813**。reviewed merge-prep head: **292bfbbc**（= main ec6df710（HFP-01 込み）merge ＋採番訂正・docs 復旧 commit）。main 上の merge 結果: 3af17e38。
- merge-prep で coordinator が行った変更（Review 対象の本体）:
  1. V109__contract_document_cloudsign_dispatch.sql → V103_1 へ git mv（S12 の V103 が main に実在化したため。決定 HFP-D013）。smoke test の history 検証を ersion='103.1' へ更新
  2. S12〜S17 予約繰上げ docs（customer-product-expansion-2026 配下 20 file＋S13〜S17 spec dir の design/tasks）を **main 版へ復旧**（HFP-02 由来の予約表変更を撤回）
  3. pplication.yml: main 版に cloudsign 設定 block を挿入。messages×4: cloudsign/contract-document の 6 key を main 版へ追加
  4. ApiAuditFilter: HFP-01 の /api/payroll 除外（main 経由）と HFP-02 の /api/contract-documents/{id}/artifacts/{kind} download 判定の両立を確認（1 request = 1 audit row 維持）
- Review 範囲: d958a813..292bfbbc の HFP-02 由来差分と、HFP-01 merge 後の main との相互作用（ApiAuditFilter・messages・application.yml・cloudsign.enabled=false の fail-closed）。REV-009/REV-014（「merge時対応」と記録）が本差分で実際に閉じているかの監査を含む。
- 再実行 command: mvn test -Dtest='CloudSign*,ContractDocument*,JsSyntaxCheckTest'、FlywayContractDocumentDispatchSchemaSmokeTest,FlywayMigrationSmokeTest,FlywayV102_4FreeeCompanyBoundarySmokeTest（実MySQL）、scripts/verify-like-ci.ps1（merge 済み head の実測は 2246/0/0/0・skip 0。Reviewer が独立再実行して確認）。
- 判定対象 acceptance: HFP-02-AC-11-05/12-01（migration・H2・skip 0）、G6 相当（merge delta・共有 consumer・main 回帰）。G2（sandbox）・G5（運用承認）は本 Review の範囲外（BLOCKED 維持）。

### 8.3 両 Review 共通の引き渡し規約

- Reviewer は実装 AI・coordinator と別の対話で、base/head を固定して実施する（handbook §8/§9）。
- Round 構成は fix delta 方式（新規 P0/P1 があれば実装 branch 側の修正→再 Review。同じ root cause を再起票しない）。
- verdict は PASS / CONDITIONAL PASS（残 gate は G01/G02 のみ）/ FAIL / NOT REVIEWABLE のいずれか。最終 PASS は本 Review と sandbox gate（G01/G02）の両方が揃った後。