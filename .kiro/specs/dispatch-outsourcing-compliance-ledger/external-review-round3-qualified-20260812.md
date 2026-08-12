# 資格保有者レビュー（第三次照合・AI一次照合の位置づけ）— 証跡3追補

- **日付**: 2026-08-12
- **位置づけ**: 実在の資格保有者（社労士/弁護士）ではない。spec R7.4・証跡3注記どおり、資格・登録識別子を持つ**実在external reviewerの代替にはならない**（架空資格登録は禁止）。本レビューは実在Reviewの補助資料となる法的知識ベース照合。
- **検証手段**: `git hash-object`でのblob hash round-trip再現、`DeliveryDeadlineRule`/`MissingDocumentDeliveryRule`/`ComplianceDocumentServiceImpl`/`ComplianceDeadlineServiceImpl`/T065通知基盤/4バンドルi18n/証跡2様式の読み込み。

## 1. 第二次指摘4件の対応検証 — いずれも妥当

| 指摘 | 検証結果 |
|---|---|
| P1-A | 提案書から事前計算hash削除済み。`git hash-object`で現行blob = `10a3fc78600a978aea8b17086d5ecce7b81c479b`のround-trip再現を実機確認。「commit後の実blobから再計算」手順も正しい |
| P1-B | 証跡2様式の`mapping_hash`=§6.2 canonical payload SHA-256（64 hex）はfield-mapping.md §8（L451-453）・decision delta §6.2と整合。canonicalizer未実装で記録不可（fail-closed）・blob hashをprovenance欄へ分離 — 妥当 |
| P2-C | `DeliveryDeadlineRule.java:63,73`の`!today.isBefore(due.minusDays(90))`で期限90日前から発火。T065基盤（`ComplianceDeadlineServiceImpl.java:103-118`、`daysUntil<=0 skip`・段階90/60/30）と合わせ、due−90日で90日前通知が到達することをコード上確認。境界test 2件も存在 |
| P3 | 両ruleとも交付判定は`"DELIVERED"`のみ（`MissingDocumentDeliveryRule.java:48`、`DeliveryDeadlineRule.java:61,70`）。生成時DELIVERED設定（`ComplianceDocumentServiceImpl.java:166`）確認。i18n 4バンドルに「遅滞なく・運用基準」追記済み |

## 2. 法的見解

- **P1-1（派遣料金明示）**: 令和6年10月1日施行の改正派遣法・施行規則で、派遣元は個別契約書への派遣料金明示義務あり — 法的確実性は高い。MAPPING-2026-07期間の交付分にも適用されるため、**(a)（2026-07 amendment版）を推奨**。(b)は期間中の帳票が法定事項欠落のまま残るため不支持。(c)は「必須性」自体が2024年10月に確定済みで施行時期の不確実性がなく、保留理由として成立しない（詳細様式の確認は証跡4で足りる）。
- **P1-2（待遇差説明を求める権利）**: 派遣労働者が待遇決定方式・待遇差の説明を求める権利は**令和6年10月1日施行分で創設済み**であり、令和8年10月施行分（労使協定方式の届出・待遇情報提供義務等）とは新設内容が異なる。**MAPPING-2026-07側にも当該周知事項の組込みを推奨**（(a)のamendと同時解消が効率的）。一次sourceでの条文確定は継続が必要だが、必須性の方向性は明確。
- **P2-1/P2-2**: 「明示=開始日の前日まで」「通知=開始後遅滞なく」の整理は法文に整合。通知書猶予日数をconfig化しi18nで「運用基準」と明示する扱いは、法の「遅滞なく」を固定日数として法定化しない点で妥当。

## 3. 新規指摘（P3×3のみ。P0/P1/P2なし）

- **P3-R1（表示文言と発火時期の不整合）**: DEADLINE_* findingは期限90日前から発火する一方、i18n文言が「期限を過ぎても交付記録がありません」と過去完了形で固定。期限前90日間は事実と異なる表示になる。期限前/期限超過で文言を分けるか、「期限（…）までに交付記録がありません」へ中立化を推奨。
- **P3-R2（ledger残存の旧hash記載）**: review-ledger.md:16の履歴節に、P1-Aで採用不可とされた事前計算hash `e93d71b3…`と「証跡2のmapping_hashへ即時記録可能」の記述が残存。P1-Bのfail-closedと矛盾し、人間が証跡2を記録する際の誤操作誘因となる。「P1-A/P1-Bにより無効」の注記追加を推奨。
- **P3-R3（通知書ruleの前倒し発火）**: DEADLINE_DISPATCH_NOTICEはdue−90＝派遣開始約87日前からfindingが存在し、90/60/30通知が開始前から発火する。義務は開始後に発生するため、発火起点を派遣開始日へ変更する設計も検討可（設計意図の確認のみで必須ではない）。

## 4. 判定

実装AI側の対応は妥当。新規P0/P1/P2は**ゼロ**、P3×3は対応推奨。M PASSは証跡5判断（発注者）→ 証跡1（管理者指名）→ 証跡2（**canonicalizer実装完了が前提**）→ 証跡4（PDF目視）→ 資格保有者の実在Review → P1-2一次source確定、の順序で取得後にR10へ依頼すべき — この整理・fail-closed維持は妥当。実在Review時には、P1-1/P1-2見解（(a)推奨・MAPPING-2026-07組込推奨）を根拠資料として利用できる。

## 実装AIの対応記録（このファイルへの追記）

- 2026-08-12: **P3-R1対応**: i18n文言を「期限（…）までに交付記録がありません」へ中立化（4バンドル）。
- **P3-R2対応**: review-ledger.md:16の履歴行へ「P1-A/P1-Bにより無効」の注記を追加（誤操作防止のため履歴として残す）。
- **P3-R3対応**: 設計意図を`DeliveryDeadlineRule`のjavadocへ明記（期限90日前からの前倒し発火は段階通知90/60/30を順に成立させるため。開始日発火にすると全段階同時発火になる）。挙動は維持。
- **P1-1/P1-2の見解（(a)推奨・2026-07組込推奨）** をFM-C-28提案書とledgerへ反映し、発注者判断（証跡5）の根拠資料として利用可能とする。
