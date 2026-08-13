# 既存半完成機能の本番完成化プログラム

## 1. 目的

2026-08-12 時点のソース、既存 spec、テスト、公式外部仕様を照合し、画面や API の骨格は存在するものの、本番で顧客価値を完結できない機能を、実装・実機受入・独立 Review まで収束させる。

本プログラムは新機能の追加提案ではない。既存の約束を完成させ、外部 API や復元処理について「動くように見えるが、実契約・実データでは成立しない」状態を解消するための実行入口である。

計画作成 branch の基点は `99fbed8294dd1a6c320b4413b832f7c7b9292da1`。本ディレクトリ作成時点では production code を変更しない。

## 2. 対象

| ID | spec | 現在の実態 | 完成状態 |
|---|---|---|---|
| HFP-01 | `payroll-management` | OAuth、token 暗号化/refresh、従業員対応付け、給与画面はあるが、freee 人事労務の公式 endpoint・company context・response schema・賞与/項目明細・実 tenant E2E が未完結 | freee の公式契約に合う読み取り専用給与・賞与参照を、接続から再認可/解除まで実 tenant で完走できる |
| HFP-02 | `contract-document-esign` | ローカル PDF、画面、API、CloudSign client の骨格はあるが、生成 PDF の実 upload、正式認証、合意締結証明書、定期同期が未完結 | 同一 PDF の hash を保持したまま送信、締結、署名済 PDF/証明書保存、権限付き download まで sandbox/契約環境で完走できる |
| HFP-03 | `database-backup-recovery` | full backup/binlog archive の script 骨格はあるが、`--target` に基づく snapshot 選択、binlog replay、uploads、manifest/SHA 検証、隔離復元演習が未完結 | 指定時点への隔離 PITR と uploads 復元を再現可能な証拠付きで実施し、RPO/RTO と破壊防止 gate を満たす |

各機能の正本は次の順序で読む。

1. repository root `AGENTS.md`
2. 本 `README.md`
3. `audit-summary.md`
4. `execution-review-handbook.md`
5. `dependency-and-ownership.md`
6. 対象 spec の `research.md` または `baseline.md`
7. 対象 spec の `requirements.md`
8. 対象 spec の `design.md`
9. 対象 spec の `tasks.md`
10. 対象 spec の `start-conversation.md` または `review-conversation.md`
11. `spec-review-report.md`
12. `execution-ledger.md`

矛盾時は、発注者の最新明示指示、公式 provider/MySQL 仕様、requirements、design、tasks、対話文の順に優先する。実装 AI は下位文書だけを根拠に要件を狭めない。

## 3. 明示的な対象外

- `.kiro/specs/customer-product-expansion-2026/copyable-conversations/COPY-INDEX.md` が定義する S01〜S17 の機能追加・再 Review。
- freee 会計の入金突合、freee 勤怠 mapping、tenant 分離、法定文書台帳等、HFP-01〜03 を完成させるために直接必要でない隣接機能。
- SES Manager Pro 内での給与計算、税・社会保険・年末調整、給与金額の書戻し。freee 人事労務を正本とする。
- 給与明細金額や口座・扶養情報の永続化。別途 privacy/retention の承認を得た新 spec なしに追加しない。
- Web 画面からの本番 DB 一発復元。
- AI の既定 `mock`、skillsheet ingestion、旧 tasks の未チェックだけを理由とした再実装。
- 本番管理者 password 変更、reverse proxy 実機確認等の deployment acceptance。これらは release gate として記録するが、半完成ユーザー機能には数えない。

## 4. 完成の共通定義

以下をすべて満たすまで「実装完了」または `PASS` としない。

1. acceptance ID ごとに production path、正常/拒否/境界/競合 test、Demo、証拠が対応している。
2. 外部 API は公式一次資料の版・確認日・endpoint/field fixture を固定し、推測した契約を実装しない。
3. credential、token、給与本文、契約本文、DB dump、暗号鍵を log・test artifact・Review packet に残さない。
4. mock test は error mapping と決定論的回帰に使い、sandbox/隔離実機必須 gate の代替にしない。
5. DB 変更時は、対象 table/column が V1 に既に統合されている場合だけ V1 も更新する。V20/V21 等で初めて導入された table は V1 へ逆輸入せず、実装時点の `latest + 1` migration、対象 H2 schema/replay、entity、fresh/legacy MySQL smoke assertion を同一 task で同期する。公開済み migration は変更しない。
6. 計画文書は `powershell -NoProfile -ExecutionPolicy Bypass -File .kiro/specs/half-finished-production-readiness/verify-spec-package.ps1`、実装差分は `git diff --check`、定向 test、直接 consumer 回帰、`scripts/verify-like-ci.ps1` が必要な checkpoint で成功し、skip を列挙する。
7. 実装者は自己成果を `PASS` にせず、base/head commit を固定した独立 Review が最終判定する。
8. provider credential、Docker、実 MySQL 等が無い場合は `BLOCKED` または release gate とし、静的 test だけで合格にしない。
9. P0/P1 が 0、未管理の acceptance が 0、必須 release gate が 0 の merge 済み head だけを最終 `PASS` とする。

## 5. 実行単位

三つの spec は別 branch・別 worktree・別主担当で実装する。共通 file の所有権、開始条件、merge 順は `dependency-and-ownership.md` を正とする。通常の入口は各 spec の `start-conversation.md`、独立 Review の入口は各 spec の `review-conversation.md` とする。

全体調整が必要な場合だけ `start-conversations.md` の統括対話を使う。三つの実装を一つの巨大対話で同時に変更してはならない。
