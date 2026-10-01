# S-NF01〜10 Production Readiness 実行台帳

## 実行権限と判定境界

- 依頼者は2026-10-01の対話で、総括agentによる実行統括、実装に`gpt-6-luna`、独立Reviewに`gpt-6-astra`の使用を指示した。
- 依頼者は技術方式・優先順位の選定を総括agentへ委任した。これは法的判断、外部契約、実在する責任者の署名、production接続・credential使用・deployの代理承認ではない。
- 開発・テスト用の実装と修復、分離branch、既存worktreeの安全な再利用、ローカルcommitを進める。外部公開・push/PR/merge・production操作は別途実行権限を確認する。
- 各featureの既存承認記録は履歴として保持する。新しい技術実行の委任を過去のDG/Privacy/Legal承認へ読み替えない。
- `IMPLEMENTATION_PASS`、`REVIEW_PASS`、`PRODUCTION_APPROVED`、`ENABLED`を独立した状態として管理する。NF06/NF07をOFFのまま全機能完了にしない。

## 固定された事実

| 項目 | 証跡 |
|---|---|
| 再fetchした初期base | `1443fb68943849a3260d7ebe005259f3eb609d60` |
| PR101 Head | `8ad0c34eacbb04a031667934f313a2b6965583f1`。baseとのtree差分なし、baseに全commit包含 |
| PR101 CI | run `36857893770`、7 jobs SUCCESS |
| main CI | run `36858538460`、6 SUCCESS / Browser FAILURE |
| Browser失敗 | `AccountingIntegrationBrowserDemoTest:349`、account linkのengineer_id unique競合。14/0/1/0。seed `101246425020` |
| migration | **181 versioned scripts + 1 repeatable**、最大V182、正規化version重複0。旧157件は24件の小数相当version（`V102_1`等）を除外した誤集計 |
| 主checkout | `C:\work\ses-manager-pro`。`application-dev.yml`、`CustomUserDetailsService.java`、`LoginUser.java`の利用者変更あり（後2件は実行中に追加を観測）。変更・reset・cleanしない |
| 最終R3証跡 | `C:\work\ses-snf-r3-remediation-b2-b`を保存。試験結果の混合集計を新runの証拠にしない |

## 分離と現在の担当

全初期branchのbaseは上記SHA。依存変更を取り込む際はactual base/headを追記する。

| Batch | 担当model | branch / worktree | 状態 |
|---|---|---|---|
| F0 統括・決定包 | root | `codex/snf-prod-control` / `C:\work\ses-snf-status-audit` | IN_PROGRESS |
| C0 Browser fixture | gpt-6-luna | `codex/snf-prod-browser-isolation` / `C:\work\ses-nf02-remediation` | `0d78cc13` Astra CODE PASS、control `778e372d`へ統合。main CI再実行なし |
| R09 provider境界 | gpt-6-luna | `codex/snf-prod-nf09-provider-boundary` / `C:\work\ses-nf09-system-actor-attribution` | 最終`da850b97`、profile4＋実service反復2=6/0/0/0、Astra CODE PASS、control `036b3eed`へ統合 |
| R03 training修復 | gpt-6-luna | `codex/snf-prod-nf03-training` / `C:\work\ses-nf03-remediation` | `5facee22` 対象限定16/0/0/0、限定CODE PASS。MySQL実並行P2補強中 |
| R10 reauth準備 | 未割当 | `codex/snf-prod-nf10-reauth` / `C:\work\ses-nf10-remediation` | worktree準備済み、実装待ち |
| R02/R03/R10 baseline Review | gpt-6-astra | R3最終証跡worktreeをread-only参照 | 静的Review完了、P1計4件。専用report参照 |
| R04 baseline Review | gpt-6-astra | R3最終証跡worktreeをread-only参照 | 静的Review完了、P1×3/P2×1。専用report参照 |
| N06/N07技術Plan Review | gpt-6-astra | control `09ab55a8` | CONDITIONAL。W0可、W1前に契約補正と再Review |
| R02 scope | 未割当 | `codex/snf-prod-nf02-scope` / `C:\work\ses-manager-pro-nf02-nf03-review-v2` | clean/包含/process確認済み、準備完了 |
| R04 offline | 未割当 | `codex/snf-prod-nf04-offline` / `C:\work\ses-review-snf-r3-a` | clean/包含/process確認済み、準備完了 |
| N06 contracts | gpt-6-luna / nf06_contracts | `codex/snf-prod-nf06-contracts` / `C:\work\ses-review-snf-r3-b` | 5 adapterの実domainと照合しW0契約同期中。実装/DDL未着手 |
| N07 foundation plan | root設計、Astra審査待ち | `codex/snf-prod-nf07-foundation-plan` / `C:\work\ses-review-snf-r3-c` | `f007ef9a`、hold線形化/intent先行とspec同期。未実装・未統合 |
| E05/E08 baseline Review | gpt-6-astra | R3固定Head | 新規P2計4件、専用report参照。production BLOCKED |
| R01a lifecycle境界 | gpt-6-luna | `codex/snf-prod-nf01-lifecycle` / `C:\work\ses-engineer-lifecycle-workflow` | 安全再利用済み、01/02/04/05/06修復中。NF01現行実装FAIL、歴史COMPLETEを継承しない |

再利用したworktreeは開始時clean、origin/main未包含commit 0、当該path依存processなしを確認した。
既存の試験用`target`は、同一worktree内`target-preserved-20261001-prod-start`へ移動して保存した。
これらをstage、削除、上書きしない。`git add .`は禁止し、変更pathを列挙する。

## 残作業キュー

| 順序 | Batch | 開始・完了条件 |
|---|---|---|
| 1 | C0 | 原seed再現→原因修正→全Browser原seed/別seed→Astra固定Head Review |
| 1 | R09 | prod/mock混在のfail-closed、実provider未設定時の誤確認防止→Astra Review。実provider完了とは別 |
| 2 | R02/R03/R04/R10 | 現行scopeの独立Review→finding修復→専用Demo→再Review |
| 2 | N06 Plan delta | 委任範囲で技術scopeを具体化し、Astra Plan Review。実source/production移行承認を分離 |
| 2 | P07 Policy/Plan | 現行coverage再生成、未確定policy・署名欄を明示。法的期間を推測しない |
| 3 | E05/E08/R09 real adapter | 既存統合と互換の技術案、prod設定契約、sandboxテスト。実credential/外部送信は別権限 |
| 3 | N06 implementation | approved technical planの全波をsynthetic環境で実装・検証し、実source未検証を残す |
| 3 | N07 implementation | policyを入力として受ける安全な基盤の技術Planを独立Review。未承認policyで実データを処分しない |
| 4 | M5/O6 | fresh/upgrade/V182 repair、staging、代表性restore、環境adapterとrunbook |
| 5 | Q6 | 固定RCでFast/MySQL/Performance/Browser/backup全gate、zero skipped、独立Review |
| 6 | G7 | 有効な人の署名・実環境証拠・停止/rollback条件が揃って初めてGo/No-Go提出 |

## 共通Gate

- local preflight: Java 21.0.12.1 / Maven 3.9.6 / Docker daemon / Node 24.19.0 / Chrome / Git Bashを確認済み。これは全suiteやproduction環境のPASSではない。
- NF07 mechanical scan再実行: `BLOCKED_COVERAGE_INCOMPLETE` / exit 2。253 table、244 entity table、699 provider候補、未分類73 table、欠落column1077、欠落provider276。旧coverage PASSは現行Headに適用不可。
- 試験は一run一suiteでSHA、command、開始/終了、環境、test/failure/error/skipped、log/XML hashを保存する。
- Maven重負荷suiteは総括が直列化し、異なるagentのDocker/browser試験の干渉を避ける。
- migration番号、H2 schema/init、shared security/document/config、中央traceabilityは総括が調整する。
- checksum改変、skip、固定順序による問題隠し、tenant default推測、mock成功へのproduction fallbackは禁止。
- push前に`scripts/verify-like-ci.ps1`。最終RCではFast/MySQL/Performance/real Chrome/backup、fresh/upgrade、PITR、安全・運用演習を要求する。
- `PENDING=0`だけでV182完了にしない。4表のscope未確定0、親子scope一致、callback一意性、実際の業務帰属・actorを確認する。
- 完了のための人工gate: Production Owner、Security、Privacy/Legal、Operations、provider契約/credential、production topology/representative profile、最終deploy権限。

## 現在の総合判定

**NO-GO / ACTIVE_REMEDIATION**。独立Review、NF06/NF07実装、実provider、policy、production環境の証拠は未完了。
この台帳の作成はproduction approvalではない。
