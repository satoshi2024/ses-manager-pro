# Implementation Plan — 注文・注文請・月次検収

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T054〜T058はL1〜L3の定向test・直接回帰、T059でL4全量を実行する。
> 契約/請求の共有状態機械を変更した場合は昇格条件を評価する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの予約番号は **V80**。approval(V75)のmerge後に着手する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [x] F1. 注文/明細/検収DDL
  - **Objective**: 顧客の注文書を注文番号・顧客PO番号・期間・金額・支払条件付きで登録でき、
    1要員1明細で複数要員の注文を表現できる。契約×月の検収が1件だけ作られる。
  - **実装ガイダンス**: **V80**/V1/H2(`sql/schema-order-acceptance-h2.sql`)/MySQL smoke、
    entity/mapper/採番/状態。`t_contract.order_line_id`にUNIQUE、`t_acceptance`に`UNIQUE(contract_id, work_month)`。
    **`t_contract.acceptance_required`は`NOT NULL DEFAULT TRUE`**（design §5.1）。
    NULLを許すと「未設定＝検収不要」に化けてR3.3が破れる。
  - **テスト要件**: L1〜L3。注文番号/PO/明細のunique、金額集計、状態遷移、複数明細、
    `UNIQUE(contract_id, work_month)`の競合、`acceptance_required`がNULL不可であること。
  - **Demo**: 2要員分の注文を2明細で登録し、明細ごとに金額と期間が独立していることを確認。
    同一契約×同一月の検収を2件作ろうとして拒否されることを確認。

- [ ] F2. 見積→注文→契約
  - **Objective**: 見積から注文draftへ顧客・要員・単価・精算幅が引き継がれ、
    注文から契約draftが生成される。契約化を2回実行しても契約は1件。
    注文条件が見積/契約と異なる場合は差分が表示され承認対象になる。
  - **実装ガイダンス**: 条件差分、approval hook（approval specのadapter）、draft共通化、冪等。
    Contract draftは既存`buildAndSaveDraft`相当の共通経路へorder sourceを追加する（design §2）。
    冪等は`t_contract.order_line_id`のUNIQUE＋状態CASの二重防御（design §5.3）。
  - **テスト要件**: L2〜L3。条件引継ぎ、差分検出、**契約化2回で1件**、
    approval経由の取消時のrollback、注文取消と契約化の競合。
  - **Demo**: 見積から注文2明細→契約2件。契約化ボタンを二重clickして契約が2件にならないことを確認。

- [ ] A1. 注文画面/注文請PDF/archive
  - **Objective**: 注文の一覧・詳細・差分が見え、受領した注文書原本をuploadして注文請書PDFを発行できる。
    どちらもarchiveへ保存され、日付・金額・相手先で検索できる。同じ原本hashの二重登録は拒否される。
  - **実装ガイダンス**: CRUD、原本upload、PDF、document links。
    document種別は`ORDER_RECEIVED`/`ORDER_ACKNOWLEDGEMENT`/`ACCEPTANCE`（design §3）。
    **PO重複は警告、同一原本hashは拒否**（design §5.3）。この2つを混同しない。
  - **テスト要件**: L1〜L3。PDF生成/hash、document ACL（注文一覧と同じscope）、
    **PO重複が警告で登録は通ること**、同一hashの登録が拒否されること、mobile 390px。
  - **Demo**: 原本受領→注文請発行。同じPO番号で警告が出つつ登録でき、同じPDFの再uploadは拒否されることを確認。

- [ ] B1. 月次検収service/UI
  - **Objective**: 月次の作業実績を提出し、顧客が検収または差戻しできる。差戻し後は再提出できる。
    提出後に工数が変わっても検収額は変わらない。
  - **実装ガイダンス**: submit/accept/reject/cancel、work record link。
    **提出時にwork record `version`と金額をsnapshot**（design §5.1）。
    提出後の工数変更で検収を自動更新せず、差戻し→再提出で処理する。
  - **テスト要件**: L2〜L3。状態遷移、`version` CAS、差戻し→再提出、
    **提出後に工数を変更しても検収額が変わらないこと**、顧客と内部代行の同時操作で先着1件のみ成功。
  - **Demo**: 勤怠確定→検収差戻し→再提出→検収済。提出後に工数を変えても検収金額が動かないことを確認。

- [ ] B2. 請求/月次締め/通知統合
  - **Objective**: 未検収の契約からは請求が生成できず、検収不要と明示した契約だけが例外的に生成できる。
    月次締めchecklistに未検収件数が出て、注文未受領・検収未提出・期限超過が通知される。
  - **実装ガイダンス**: invoice生成queryへ
    `acceptance_required = FALSE OR EXISTS(検収済のacceptance)` を**WHERE句として**追加する（design §5.3）。
    取得後のJava filterにしない。deadline通知/KPI。
    月次締めchecklistの未検収件数は**閲覧者のscopeで数える**（design §5.2）。
  - **テスト要件**: L2〜L3。検収要/不要の両分岐、**未検収契約からの請求生成が0件**、
    通知の重複なし、月次締め件数のscope、検収取消と請求生成の競合。
  - **Demo**: 未検収請求拒否→検収後生成。検収不要契約が理由付きで請求できることを確認。

- [ ] M. 全通し
  - **Objective**: 見積→注文→契約→勤怠→検収→請求がIDで追跡でき、
    既存のdocument/approval/contract/invoice機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    document/approval回帰、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 見積→注文→契約→勤怠→検収→請求。各段階のIDが次段階から辿れることを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
