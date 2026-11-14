# NF07 状態文書の整合一覧（N7-2 / S-1、2026-11-14）

状態: **整合済み（文書のみ）。テストの再実行、Review verdict の記録、承認状態の変更は行っていない。**
2026-11-14 追記: R1（`3d59ffae`）に対する独立 Plan Review の結果は外部記録（control repo の review 報告）にあり、本書には転記しない。R2 では X-3 の訳語だけを改めた。
基準: `1be03bea`。`.kiro/specs/privacy-retention-dsar/` と `src/{main,test}/java/com/ses/privacy/` は `73aa7000` から無変更（`git diff --quiet 73aa7000 1be03bea -- <path>` で確認）。
入力: control repo の調査報告 `ses-claude-control-20261112-r1/reports/nf07-privacy-readiness.md` §4（2026-11-12、基準 `73aa7000`）が挙げた C-01〜C-21。各箇所を基準 `1be03bea` で grep し直して位置と文言を確認した。報告と行番号が違ったものは本表の値が正しい（例: C-21 の `prod-wiring-decision-contract.md` は `:38` と `:286`）。下表の行番号は整合前（`1be03bea`）の行である。

## 1. 現在の事実の決め方（以後の状態記述にも適用する）

| 種類 | 事実の出所 | 扱い |
|---|---|---|
| 人の署名・外部 gate | `review-ledger.md` と `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md:201-213`（DG-07 節） | 反証の記録が無いので BLOCKED のまま。本整合では変更しない |
| コードの存在 | 基準 SHA の git tree | `com.ses.privacy.foundation` は main 68 / test 24 file、Spring アノテーションなし、package 外からの参照なし |
| テスト結果 | 記載された固定 SHA の証拠だけ | 別 SHA の件数を合算しない。本整合では再実行していない |
| 独立 Review verdict | 実装 branch の外の記録（`review-ledger.md:3,22`） | spec 内の「Astra PASS」「受理」は実装側の転記（手掛かり）。repo 内に記録が無いものは **UNKNOWN** |

状態文の書き方: 日付・固定 SHA・範囲・証拠の path を付ける。PASS へ昇格させない。過去の状態文は消さずに「作成時の記録」と明記し、その直後に「2026-11-14 状態整合」の注記を置く。

用語:

| 表記 | 意味 |
|---|---|
| 記録上 PASS（外部記録未照合） | 実装側の文書に Astra の判定が SHA 付きで書かれているが、正式 verdict は外部記録で確認する必要がある |
| UNKNOWN | repo 内に判定記録が無い、または記録同士が食い違う |
| 未完了（`[ ]`） | task の Demo・完了条件を全件証明した記録が無い |
| BLOCKED | 人の署名・外部 gate が無い |

## 2. 整合一覧（21件）

| # | 対象（ファイル:行、日付・範囲） | 統一前 | 統一後 | 根拠 | 変更した箇所 |
|---|---|---|---|---|---|
| C-01 | `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md:60`（2026-08-27、承認面）と同 `:18`（2026-10-01 節、技術面） | 一方は「NF-07 CANDIDATE、Owner 未定」、他方は「SF基盤・D2b2・W3a 限定 scope 統合済み」 | 二軸で書く: 承認面は CANDIDATE、技術面は合成基盤統合済み・production bridge 未接続 | §1 の出所の分け方。両者は scope が違い、どちらも正しい | `requirements.md` §0 表の NF-07 行（spec 外の roadmap は変更しない） |
| C-02 | `traceability.md:324`（2026-09-07 統合 gate） | NF-07「未着手 / DISCOVERY 維持」 | 過去記録として読む。現在は合成基盤 68 main class が存在する | 基準の git tree | 変更なし（spec 外）。本表の読み替え規則だけ |
| C-03 | `.kiro/roadmap/2026-09-07-snf01-10-remediation-audit.md:176-181`（2026-09-07） | DISCOVERY（DEV-0/D0 only）、F1〜M STOPPED | 「DEV-0/D0 only」は過去記録。F1〜M STOPPED は、実装着手について Q-8（2026-11-14）で解除、本番開放について維持 | 依頼者の決定 Q-8 | `tasks.md` 「F1〜M」節の変更記録（spec 外は変更しない） |
| C-04 | `requirements.md:10`、`plan.md:8`（2026-10-01） | SF-W1 の独立 Implementation Review は未完了／待ち | SF-W1 は `6ec4ca28` で記録上 PASS（外部記録未照合、18/0/0/0） | `tasks.md:6,12`、`completion-matrix.md:42,78,86`、`synthetic-foundation.md:3,30` が同じ SHA・件数で記録 | `requirements.md:10`、`plan.md:8` |
| C-05 | `synthetic-foundation.md:3`、`tasks.md:17-19` | 「SF-W2以降未着手」、SF-W2/W3/W4 は `[ ]` | SF-W2 以降は合成部分実装あり（W2a、W2b Slice B/C、D2b2、W3a）。各 wave の完了条件は全件証明されていないので `[ ]` のまま | `tasks.md:22,24` の自己記述、基準の git tree | `synthetic-foundation.md:3`、`tasks.md:17` |
| C-06 | `sf-w2-protocol.md:3` | 技術設計案 / Astra 追加 Plan 再 Review 待ち | 作成時の記録と明記し、以後の限定受理（Slice B 10/0/0/0、Slice C 13/0/0/0、control C 16/0/0/0）を併記。protocol 全体の PASS ではない | `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:813-815,855,914` | `sf-w2-protocol.md:3` |
| C-07 | `sf-w2b-execution-plan.md:3` | 実装前の技術実行案 / Astra 追加 Review 待ち | 作成時の記録と明記し、projection/witness/recovery が合成層として実装済みであることと限定受理の所在を併記 | execution-ledger 同上、基準の git tree | `sf-w2b-execution-plan.md:3` |
| C-08 | `sf-w2b-slice-d-recovery-plan.md:3,5` と同ファイル末尾の 2026-10-16 節（`:140` 付近） | 冒頭は「設計案 / Astra review 待ち」「D2b1 候補」、末尾は「D2b2 `ac894ac6` 35/0/0/0 限定証拠 PASS」 | 最新状態は末尾の 2026-10-16 節（記録上 PASS、外部記録未照合）。§7.3 の未完境界は未完了のまま | 同ファイル内の新しい節、execution-ledger `:377` | `sf-w2b-slice-d-recovery-plan.md:3` |
| C-09 | `sf-policy-readonly-query-plan.md:3` | PLAN REVISION (R4) / 未実装 | 実装済み（`110eeb36`、`1559d437`）。`c8b78dc3` で 42/0/0/0・Fast 4,292 の歴史的証拠。独立 CODE verdict は UNKNOWN | `tasks.md:13`、基準の git tree | `sf-policy-readonly-query-plan.md:3` |
| C-10 | `completion-matrix.md:63,71`、`pure-policy-verification-kernel-plan.md:4`、`tasks.md:16`（2026-10-28） | Batch 2 は CODE REVIEW PENDING | Batch 2 の独立 CODE verdict は UNKNOWN。2026-11-04 の受理はテスト履歴（57件・4,307件）であって CODE PASS ではない | `.kiro/roadmap/2026-11-04-snf-autonomous-coordination.md:44` | `completion-matrix.md:63,71`、`pure-policy-verification-kernel-plan.md:4`、`tasks.md:16` |
| C-11 | `tasks.md:13`（`[ ]`、独立 CODE Review 待ち）、`pure-policy-verification-kernel-plan.md:479,497-499`（REVIEW PENDING）と `production-readonly-boundary-slice-plan.md:10,16`（2026-11-04） | 一方は待ち、他方は「Batch 3 は Astra 独立 CODE PASS 済み（受理記録に基づく）」 | Batch 3 の独立 CODE verdict は UNKNOWN | repo 内に受理記録が無い（§1） | `production-readonly-boundary-slice-plan.md:3` の注記、`tasks.md:13`、`pure-policy-verification-kernel-plan.md:4` の注記 |
| C-12 | `production-readonly-boundary-slice-plan.md:3` と `policy-source-catalog-wiring-plan.md:4`（`711aa557`、2026-11-04） | 一方は CODE REVIEW PENDING、他方は「`498f04ba` 限定 CODE/runtime PASS 76/0/0/0」 | Batch 4a の独立 CODE verdict は UNKNOWN。76件の証拠 dir は repo 内に無い。技術証拠は `73aa7000` の FULL_GATE artifact の 3 class 19/0/0/0（8 + 4 + 7、本整合で再実行なし） | control 報告 `nf07-privacy-readiness.md` §3.2（EVID73 の XML と SHA-256） | `production-readonly-boundary-slice-plan.md:3`、`policy-source-catalog-wiring-plan.md:4`（N7-1 の取込時） |
| C-13 | `completion-matrix.md`（最終更新 `35f16aad`、2026-10-28） | Batch 4a の記載が無い | Batch 4a の節を追加（設計・コード・テスト・verdict UNKNOWN・本番状態） | `97007872` で統合済みのコード | `completion-matrix.md` 2026-11-14 節 §7 |
| C-14 | `prod-wiring-decision-contract.md:108,110,111`（2026-10-26 R3） | `LoginUser` の ID 欠落時は username を使う、`ExternalApiPrincipal`・サービス主体を写像する | 現行の正本は Batch 4a と実装: ID 欠落は拒否、External/Portal/service principal は本 slice では拒否 | `production-readonly-boundary-slice-plan.md:81,88`、`SecurityContextCallerContextProvider.java:57-60,108-114` | `prod-wiring-decision-contract.md` §3.2 表の直後に注記 |
| C-15 | `completion-matrix.md:32-34,87`、`prod-wiring-decision-contract.md:50-52`（`cde61306`）、`plan.md:10-11`（B0/V182）、`.kiro/roadmap/2026-10-01-snf-production-privacy-coverage.md:3-12`（`ad9bb1c6`） | 3組の数値がそれぞれ「最新」「現行」として書かれている | 全て過去 SHA の値。基準 `1be03bea`（V183〜V186 を含む）の scan は未実施で、現行 coverage は UNKNOWN（BLOCKED 想定） | 各数値の測定 SHA が異なる。基準での scan 記録が無い | `plan.md:10-11`、`completion-matrix.md:87`、`prod-wiring-decision-contract.md:54` |
| C-16 | `requirements.md:38`、`plan.md:25`、`review-handoff.md:6-8`（`f131f51c` / `0333b0a4`）と `requirements.md:5`、`tasks.md:6`（`1443fb68`）、Batch 4a（`7e1a9cdf`）、統合候補（`73aa7000` / `1be03bea`） | 文書ごとに異なる base を記載 | 全て技術比較 base。approved Base branch/SHA は未提供で BLOCKED | `requirements.md:97-98`（PR-R5.2） | `requirements.md` §0 表の Base 行、`review-handoff.md:8` の直後 |
| C-17 | `review-ledger.md:22` と `completion-matrix.md:42-45,65,71`、`tasks.md:6,12-16` | ledger は「実装 AI は外部 verdict を記録しない」、matrix/tasks は Astra PASS を記載 | matrix/tasks の verdict は実装側の転記（手掛かり）として読む。正式 verdict は外部記録。`review-ledger.md` は変更しない | `review-ledger.md:3,22` | `completion-matrix.md`・`tasks.md` の冒頭に読み方を追記 |
| C-18 | execution-ledger `:3`（最新節 2026-10-24） | Batch 2（10-28）・Batch 4a（11-04）・統合候補（11-11）の記録が無い | ledger から Batch 2/3/4a の状態を推定しない | 記録時期の差 | 変更なし（spec 外）。本表の読み替え規則だけ |
| C-19 | control の handoff `HANDOFF-zh-CN.md:72`、`nf-readiness-zh-CN-r2.md:21,80`（repo 外） | Journal の 28件だけを挙げ、`711aa557` を専用 branch HEAD と記載 | `73aa7000` には privacy の XML が 13 class ある。`711aa557` が `73aa7000` に足すのは計画文書1件だけで、N7-1 で取り込んだ | control 報告 §3.2・§3.3 | 変更なし（repo 外）。`policy-source-catalog-wiring-plan.md` を spec に追加 |
| C-20 | control の handoff `HANDOFF-zh-CN.md:41`（repo 外） | 「3件の未 push commit と policy 証拠を審査、13 patch 一致」 | 3件（`fd142a52` / `5fa20f4c` / `91a67611`）の patch は `73aa7000` の `35f16aad` / `11bfeb26` / `e7162e29` と同一で、`73aa7000` は基準 `1be03bea` の祖先 | control 報告 §3.3 の patch-id 比較、`git merge-base --is-ancestor 73aa7000 1be03bea` | 変更なし（repo 外） |
| C-21 | `prod-wiring-decision-contract.md:38,286`（2026-10-26） | NF07 本番 DDL は「V185 未割当」「V185 は占用しない」 | V185 は NF10 が `V185__report_schedule_claim_token.sql` として使用済み（最新 V186）。NF07 の application DB 側 DDL は merged 状態の次の空き番号を総括が採番（W3-14 は暫定 V192/V193）。Journal/Witness 側は別 location | 基準の `db/migration`、CLAUDE.md の版番号規則 | `prod-wiring-decision-contract.md:38,286` |

## 3. 21件以外に見つかって処理したもの

| # | 対象 | 内容 | 処理 |
|---|---|---|---|
| X-1 | `prod-wiring-decision-contract.md` §4.2 の 3.（`:222-224`） | 「外部送信には `CaseApprovalEnvelope` と `VerifiedDispatchFence` が不可欠」が、依頼者の決定 B-NF08-2（2026-11-13、NF08 は署名済み決定IDに束縛した承認設定＋送信毎の監査）と食い違う | §4.2 の後に追記: その条項は NF07 自身の外部 dispatch に適用し、NF08 Copilot は B-NF08-2 に従う。NF07 policy は `nf07PolicyId` で参照する（production-bridge-phased-plan.md §8）。Plan Review の確認対象 |
| X-2 | `pure-policy-verification-kernel-plan.md:461` | 表の見出しの先頭に中国語の2文字の語（U+4F20 U+5165、「渡された」の意）が残っていた | 「引数」に置き換えた（意味は同じ） |
| X-3 | `policy-source-catalog-wiring-plan.md:1`（`711aa557`） | 見出しの「接続計画」に当たる語が中国語の字形（U+7EBF、U+8BA1、U+5212 を含む）で書かれていた | N7-1 の取込時に日本語の「接続計画」へ直した（R1 の訳語は中国語式の用語だったため、R2 で改めた） |
| X-4 | `dev-gate-evidence.json`（2026-08-28）の Full gate prohibited `F1-M` | Q-8（2026-11-14）と食い違う | JSON は当時の決定記録なので変更しない。tasks.md の変更記録に、Q-8 が実装着手についてだけ上書きすること、validator の FULL_FEATURE_PRODUCTION mode の HARD_STOP は本番承認が無い現状と一致することを書いた |
