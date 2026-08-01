# Implementation Plan — CRM複数担当者・商機管理

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T048〜T052はL1〜L3の定向test・直接回帰、T053でL4全量を実行する。
> 通常Taskごとの全量test反復は禁止する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの予約番号は **V73**（2026-08-01に中央台帳で確定）。BP master(V70/V71)は
> `origin/main`にmerge済みで、適用済みの最新は**V71**。したがってV73は空き番号であり、そのまま使用する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。
> approvalの予約V72は未使用のまま残るが、本specがV72を代わりに使ってはならない（欠番は埋めない）。

- [ ] F1. contact/lead/opportunity DDLと移行
  - **状態**: DDL(V73) / 移行 / entity / 定向test は完了（`review-ledger.md` 参照）。
    ただし Demo「既存顧客の担当者がdetailに表示」は顧客詳細画面がT050(A1)の成果物のため未成立。
    Demo成立まで `- [x]` にしない（Round 1指摘 CRM-R1-P1-03 / NOTE-7）。
  - **Objective**: 1顧客に決裁者・現場・調達・請求・契約の担当者を役割付きで登録でき、
    既存の`m_customer.contact_*`が初回contactへ移行されて顧客詳細に表示される。
  - **実装ガイダンス**: **V73**/V1/H2(`sql/schema-crm-h2.sql`)/MySQL smoke、既存contact→初回contact。
    `primary_flag`は「1顧客につき有効期間内に1件」。**0件も許容**し、先頭担当者へ暗黙fallbackしない（design §6.1）。
    既存単一contact fieldはmigration後read compatibility、write禁止。
  - **テスト要件**: L1〜L3。移行件数と値の一致、primary一意（0件許容）、
    PII scope（**exportにも同じmask**）、期間重複の拒否。
  - **Demo**: 既存顧客の担当者がdetailに表示。移行前後で担当者名/emailが一致することを提示。

- [x] F2. opportunity状態/変換/forecast排他
  - **状態**: 完了。状態CAS/楽観ロック、終端更新拒否、受注時の案件・見積変換、source UNIQUEによる冪等変換、forecast排他を実装・検証済み。
  - **実測**: `OpportunityServiceImplTest` 7/7、`OpportunityServiceIntegrationTest` 2/2（H2実DBで2回変換して案件/見積各1件、受注済み商機をforecastから除外）。
  - **Demo**: H2統合テストで商機→受注→案件/見積変換を実行し、再変換後も同一ID・各1件、forecast母集団に未変換openだけが残ることを確認。
  - **Objective**: 商機をstageで進め、受注時にproject/quotationへ変換できる。
    受注操作を2回実行してもproject/quotationは1件しか作られない。
    提案へ変換済みの商機がforecastで二重計上されない。
  - **実装ガイダンス**: `OpportunityService`状態機械と`convertToProject/Quotation`。
    **opportunity IDをproject/quotation source列へ保存しUNIQUE**（design §6.3）。CAS＋UNIQUEの二重防御。
    forecast母集団は`converted_quotation_id IS NULL AND stage NOT IN (受注, 失注)`。
    `converted_quotation_id IS NULL`は明示NULL判定を要する（platform-invariants §1.1）。
  - **テスト要件**: L2〜L3。stage遷移と終端の編集不可、**受注2回実行で1件**、
    opportunity forecastと既存提案forecastの**二重加算なし**、失注時の`lost_reason`必須。
  - **Demo**: 商機→見積/案件変換を2回実行し1件。変換後にforecast合計が増えないことを確認。

- [ ] A1. 顧客contacts/timeline
  - **Objective**: 顧客詳細でcontacts・opportunities・activitiesが1つのtimelineで見え、
    請求書送付時に「請求担当」を宛先として選べる。退職した担当者は新規宛先候補に出ない。
  - **実装ガイダンス**: 複数担当、役割、activity/mail/document link。
    帳票の宛先は名称/email snapshotを保存し、以後の担当者変更で過去帳票の宛先表示を変えない（design §6.1）。
  - **テスト要件**: L1〜L3。役割別の宛先候補、退職者の候補からの除外（履歴は残る）、
    PII mask（画面とexportで同一）、mobile 390px。
  - **Demo**: 請求担当を請求書送付先に選択。担当者を退職にして新規宛先候補から消え、過去帳票は変わらないことを確認。

- [ ] A2. lead/opportunity UI
  - **Objective**: leadを登録して顧客/商機へ転換でき、商機をkanbanでstage移動できる。
    転換を2回実行しても顧客/商機が重複しない。D&Dが失敗したら元の位置へ戻る。
  - **実装ガイダンス**: lead list、opportunity kanban/list、next actionからtask作成。
    未割当leadは営業全員から可視（母集団0件を避ける、design §6.2）。
  - **テスト要件**: L1〜L3。filters/scope、mobile 390px、**D&D失敗時のUI rollback**、
    lead転換の冪等、重複lead候補が警告のみで自動mergeしないこと。
  - **Demo**: lead→顧客/商機→見積。D&D中にAPIを失敗させカードが元に戻ることを確認。

- [ ] B1. CRM KPI
  - **Objective**: stage別金額・滞留日数・活動なし日数・担当別転換率・失注理由・source ROIが表示され、
    担当別funnelからdrilldownできる。営業Aには自分の担当分だけが集計される。
  - **実装ガイダンス**: stage金額/滞留/転換/失注/source ROI。
    集計口径は既存の提案forecastと重複させない（design §6.3）。両系列を足す画面を作らない。
  - **テスト要件**: L2〜L3。集計口径（全社=担当別合計）、scope（営業A/Bで母集団が異なる）、
    forecast二重計上なし。
  - **Demo**: 担当別funnel drilldown。全社合計と担当別合計が一致することを提示。

- [ ] M. 回帰
  - **Objective**: 新規leadから受注までが一気通貫で動き、既存のcustomer/proposal/quotation機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    customer/proposal/quotation回帰、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 新規leadから受注まで一気通貫。受注後のforecastが二重計上されないことを提示。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
