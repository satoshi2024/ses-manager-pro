# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`T060 FAIL / R10 R1対応中`。R1-P1-01の公式項目欠落と根拠なし2026-10 mapping行を修正し、R1-P1-02の社内責任者承認event証跡は未取得のため、T060を未完了へ戻した。自然人をseed/specへ固定しない方針は維持するが、実actor、承認status、承認日時、mapping version/hash、公式source版を含む承認証拠が必要である。T061/V82/production変更は停止する。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5 | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md`（T060 checkboxを未完了へ戻した）。production code/DDL/migration/SecurityConfigは変更しない | L0/direct regression **PASS**: form mapping 96行、SRC-E ⑱=1行、SRC-L ④=1行、根拠なし2026-10 mapping行=0行、全mapping行11列、version/effective period、T060 3文書の`git diff --check` exit 0。Demo: mapping修正はFIXED_BY_IMPLEMENTER、社内承認Demoは証拠未取得のためOPENのまま | R10固定範囲 Base `f8adbc028ae0e260ed8123d0405901febee16f5a` → original Head `8fdadb4af51d224d7659d377196b6774d46dea1f` → Packet Head `be2fb190dcdf6d13286694ebe3a6a31cb477fb09`。R1 fix Headはcommit後に記録 | T061/V82へ進めない。productionでは未指名/未確認/資格・根拠不足をfail-closed。rollbackはR1 fix commitをrevertし、production変更は存在しないためDB rollback不要 |

## R10 Issue Register

| issue ID | Review status | Implementer status | violated / location | fix evidence | verification / next action |
|---|---|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R1-P1-01 | **OPEN** | **FIXED_BY_IMPLEMENTER** | T060 Objective/L0全項目網羅、R2.1／field-mapping.md SRC-E section・SRC-L section・2026-10行 | SRC-E「社会保険の加入手続きが完了していない場合の理由（⑱）」とSRC-L「60歳以上か否かの別（④）」を独立mapping行へ追加。4公式PDFに確認できない2026-10通知行を削除し、一次source特定gateへ戻した | R10が公式4PDFの項目番号完全性と余分な行の不存在を再確認するまでVERIFIED_CLOSEDにしない |
| dispatch-outsourcing-compliance-ledger-R1-P1-02 | **OPEN** | **OPEN / APPROVAL_REQUIRED** | T060 Demo、tasks.md、review-ledger.md／社内責任者の実actor・承認日時・mapping version/hash・source版・status証跡 | repo内に有効な承認event記録がないため、証跡を捏造せずT060 checkboxを `[ ]` へ戻した。`COMPLIANCE_RESPONSIBLE`のruntime構造とfail-closed仕様は保持 | 発注者/管理者がruntimeで承認eventを取得し、actor user ID、表示名snapshot、role、権限、承認日時、mapping version/hash、公式source版を対象commitと一致させる。取得後にR10が確認するまでVERIFIED_CLOSEDにしない |

## M / 本番gateと再開条件

- `COMPLIANCE_RESPONSIBLE` のruntime assignment、資格/根拠の確認、法定責任者の事業所/契約assignmentは、M / 本番設定gateとして実装・設定する。承認eventには実際のactor user ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- 2026-10-01施行分の待遇差説明を求める権利の正確な文言・対象範囲は `GATE-T060-2026-10` としてB1/T066で確認する。`MAPPING-2026-07`へ遡及しない方針は確定済み。
- 個別契約書・就業条件明示書・派遣先通知書のarchive retention categoryは `GATE-T060-RETENTION` としてT061/B1で具体化する。派遣元管理台帳の派遣終了日から3年間保存だけを公式記載のbaselineとする。
- 抵触日算定のクーリング期間値と組織単位変更の同一性基準は `GATE-T060-COOLING` としてT062/T065で具体化する。
- 外部社労士/弁護士の照合は `GATE-T060-EXTERNAL` としてT066 M / 本番解放前のgateである。

T060からT061へ進む条件は、R1-P1-01のL0/direct regressionをR10が確認し、R1-P1-02の有効な社内承認event（active assignment、承認権限、actor、承認日時、mapping version/hash、公式source版）がrepoの証跡として確認され、tasks.mdのT060が `[x]` へ戻ること。R10が2件をVERIFIED_CLOSEDするまでT061/V82/production変更を開始しない。T061開始時にはmerge済み `db/migration` のlatestを再確認する。
