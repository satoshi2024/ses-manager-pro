# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`T060 PROVISIONAL MAPPING COMPLETE / L0 PASS候補`。T060は公式資料・版・effective period・全帳票項目・permission/retention/asOf・欠落候補と、`COMPLIANCE_RESPONSIBLE` roleの承認状態・監査・runtime assignment・fail-closed契約を確定した。特定の自然人名/user IDは事前固定しない。role assignment、資格/根拠確認、2026-10境界、保存category、クーリング値、外部社労士/弁護士照合はM / 本番gateとして後続実装・本番設定で管理し、T060の完了またはT061以降の開発をblockしない。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5 | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md`（T060 checkboxのみ）。production code/DDL/migration/SecurityConfigは変更しない | L0: 公式5 sourceのURL/版/確認日、4帳票の全項目・反復/条件付き項目、permission/retention/asOf、欠落候補、role code/操作/3状態/監査/runtime assignment/fail-closedを確認。Demo: provisional mapping、後続/M・本番gate（role assignment、資格/根拠、2026-10、保存category、cooling、外部専門家）を提示 | Base `3b03a94c0028f3df522f482f6413ff3648a81fc9`。T060実装対象Headはこの記録を含むcommit（確定後にhash記録） | 開発baselineを本番へ直接伝播しない。productionでは未指名/未確認/資格・根拠不足をfail-closed。rollbackは本3文書のcommitをrevertし、production変更は存在しないためDB rollback不要 |

## M / 本番gateと再開条件

- `COMPLIANCE_RESPONSIBLE` のruntime assignment、資格/根拠の確認、法定責任者の事業所/契約assignmentは、M / 本番設定gateとして実装・設定する。承認eventには実際のactor user ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- 2026-10-01施行分の待遇差説明を求める権利の正確な文言・対象範囲は `GATE-T060-2026-10` としてB1/T066で確認する。`MAPPING-2026-07`へ遡及しない方針は確定済み。
- 個別契約書・就業条件明示書・派遣先通知書のarchive retention categoryは `GATE-T060-RETENTION` としてT061/B1で具体化する。派遣元管理台帳の派遣終了日から3年間保存だけを公式記載のbaselineとする。
- 抵触日算定のクーリング期間値と組織単位変更の同一性基準は `GATE-T060-COOLING` としてT062/T065で具体化する。
- 外部社労士/弁護士の照合は `GATE-T060-EXTERNAL` としてT066 M / 本番解放前のgateである。

T060からT061へ進む条件は、上記L0とDemoが合格し、tasks.mdのT060だけが `[x]` になっていること。T061開始時にmerge済み `db/migration` のlatestを再確認し、V82（衝突時は後発番号、V59は永久欠番）を採番する。role assignment、資格/根拠確認、外部専門家ReviewはT060を再開する条件ではなく、後続M / 本番解放の条件とする。
