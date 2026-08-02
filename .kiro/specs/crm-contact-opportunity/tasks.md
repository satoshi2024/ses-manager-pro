# Implementation Plan — CRM複数担当者・商機管理

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T048〜T052はL1〜L3の定向test・直接回帰、T053でL4全量を実行する。
> 通常Taskごとの全量test反復は禁止する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specのDDLは **V73**、権限seedは **V74**、Round 5 legacy forward-fixは **V74.1**
>（2026-08-02に確定）。BP master(V70/V71)は
> `origin/main`にmerge済みで、適用済みの最新は**V71**。したがってV73は空き番号であり、そのまま使用する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。
> S07 approvalの予約V75は維持し、本specがV75を代わりに使ってはならない。V72は欠番のまま残す。

- [x] F1. contact/lead/opportunity DDLと移行
  - **状態**: 完了。DDL(V73) / 移行 / entity / 定向testに加え、T050の顧客detailで移行contactの表示を確認。
  - **Objective**: 1顧客に決裁者・現場・調達・請求・契約の担当者を役割付きで登録でき、
    既存の`m_customer.contact_*`が初回contactへ移行されて顧客詳細に表示される。
  - **実装ガイダンス**: **V73**/V1/H2(`sql/schema-crm-h2.sql`)/MySQL smoke、既存contact→初回contact。
    `primary_flag`は「1顧客につき有効期間内に1件」。**0件も許容**し、先頭担当者へ暗黙fallbackしない（design §6.1）。
    既存単一contact fieldはmigration後read compatibility、write禁止。
  - **テスト要件**: L1〜L3。移行件数と値の一致、primary一意（0件許容）、
    PII scope（**exportにも同じmask**）、期間重複の拒否。
  - **Demo**: 顧客detailのcontactsカードへ移行contactを表示。V73本体の移行testで移行前後のname/email/phone一致も確認済み。

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

- [x] A1. 顧客contacts/timeline
  - **状態**: 完了。顧客detailのcontacts/opportunities/activities、請求宛先選択、退職者除外、PII mask/exportを実装・検証済み。
  - **実測**: `CustomerContactServiceIntegrationTest` 3/3、`CustomerContactApiControllerTest` 1/1、`InvoiceServiceImplTest` 41/41、`InvoiceApiControllerTest` 10/10、`SalesActivityApiControllerTest` 8/8、`CustomerApiControllerTest` 3/3、`MobileResponsiveLayoutTest` 23/23、`MessageBundleConsistencyTest` 4/4 PASS。
  - **Demo**: 顧客detailで移行contactと関連商機を表示し、請求書リマインドの有効contact候補を選択。退職処理後は候補から消え、`t_mail_delivery.recipient` に送信時点の宛先snapshotが残る。CSVは画面と同じmask。
  - **Objective**: 顧客詳細でcontacts・opportunities・activitiesが1つのtimelineで見え、
    請求書送付時に「請求担当」を宛先として選べる。退職した担当者は新規宛先候補に出ない。
  - **実装ガイダンス**: 複数担当、役割、activity/mail/document link。
    帳票の宛先は名称/email snapshotを保存し、以後の担当者変更で過去帳票の宛先表示を変えない（design §6.1）。
  - **テスト要件**: L1〜L3。役割別の宛先候補、退職者の候補からの除外（履歴は残る）、
    PII mask（画面とexportで同一）、mobile 390px。
  - **Demo**: 請求担当を請求書送付先に選択。担当者を退職にして新規宛先候補から消え、過去帳票は変わらないことを確認。

- [x] A2. lead/opportunity UI
  - **状態**: 完了。lead登録・重複候補警告・顧客/商機転換、opportunity kanban、失敗時D&D rollbackを実装・検証済み。
  - **実測**: `LeadServiceIntegrationTest` 2/2、`CrmUiRegressionTest` 1/1、`MobileResponsiveLayoutTest` 22/22、`MessageBundleConsistencyTest` 4/4、`OpportunityServiceImplTest` 7/7、`OpportunityServiceIntegrationTest` 2/2 PASS。Node `--check` 2ファイル PASS。
  - **Demo**: `/crm/leads`で重複候補を警告し自動統合せず保存、lead→顧客/商機を2回実行して同一IDを返す。`/crm/opportunities`のカードD&DでAPI失敗時に元stageへ復元するUI契約を確認。390px向け横スクロールとカード幅を確認済み。
  - **Objective**: leadを登録して顧客/商機へ転換でき、商機をkanbanでstage移動できる。
    転換を2回実行しても顧客/商機が重複しない。D&Dが失敗したら元の位置へ戻る。
  - **実装ガイダンス**: lead list、opportunity kanban/list、next actionからtask作成。
    未割当leadは営業全員から可視（母集団0件を避ける、design §6.2）。
  - **テスト要件**: L1〜L3。filters/scope、mobile 390px、**D&D失敗時のUI rollback**、
    lead転換の冪等、重複lead候補が警告のみで自動mergeしないこと。
  - **Demo**: lead→顧客/商機→見積。D&D中にAPIを失敗させカードが元に戻ることを確認。

- [x] B1. CRM KPI
  - **状態**: 完了。stage別金額/加重forecast、滞留日数、活動なし日数、担当別lead転換率/商機受注率、失注理由、source ROI、提案/商機forecast別系列を実装・検証済み。
  - **実測**: `CrmKpiServiceIntegrationTest` 1/1、`CrmKpiScopeIntegrationTest` 1/1、`CrmUiRegressionTest` 1/1、`MobileResponsiveLayoutTest` 23/23、`MessageBundleConsistencyTest` 4/4 PASS。Node `--check` 3ファイル PASS。
  - **Demo**: `/crm/opportunities/kpi`でstage別表・担当別funnel・失注理由・source ROI・forecast別系列を表示。営業scopeでは許可customerかつ本人担当の商機/leadだけを集計し、変換済み商機をopportunity forecastから除外。
  - **Objective**: stage別金額・滞留日数・活動なし日数・担当別転換率・失注理由・source ROIが表示され、
    担当別funnelからdrilldownできる。営業Aには自分の担当分だけが集計される。
  - **実装ガイダンス**: stage金額/滞留/転換/失注/source ROI。
    集計口径は既存の提案forecastと重複させない（design §6.3）。両系列を足す画面を作らない。
  - **テスト要件**: L2〜L3。集計口径（全社=担当別合計）、scope（営業A/Bで母集団が異なる）、
    forecast二重計上なし。
  - **Demo**: 担当別funnel drilldown。全社合計と担当別合計が一致することを提示。

- [ ] M. 回帰
  - **状態**: Round 5修正のL1〜L3・MySQL fresh/legacy/partial/repairはgreen。L4全量とdesktop/390px全role browser Demoは最終gateとして残る。
  - **実測**: Round 5定向回帰77件 / failures 0 / errors 0、MySQL migration smoke 4件 / failures 0 / errors 0、Node `--check` 3/3、compile PASS、`git diff --check` exit 0。
  - **ブラウザDemo**: 管理者ログイン後、`/crm/leads`、`/crm/opportunities`、`/crm/opportunities/kpi`を確認。KPIは390px幅で主要見出し、Forecast、担当別、失注理由を確認。
  - **Objective**: 新規leadから受注までが一気通貫で動き、既存のcustomer/proposal/quotation機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    customer/proposal/quotation回帰、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 新規leadから受注まで一気通貫。受注後のforecastが二重計上されないことを提示。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
