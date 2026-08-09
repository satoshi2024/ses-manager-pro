# Implementation Plan — 注文・注文請・月次検収

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T054〜T058はL1〜L3の定向test・直接回帰、T059でL4全量を実行する。
> 契約/請求の共有状態機械を変更した場合は昇格条件を評価する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの基礎DDLは **V80**、R10 remediationは既適用V80を変更せず順方向 **V81** を使用する。
> approval(V75〜V79/V79.1)のmerge後に着手済み。V80/V81を再編集せず、着手時に再確認した実Headの最新をPacketへ固定する。V59/V72は永久欠番。

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

- [x] F2. 見積→注文→契約
  - **Objective**: 見積から注文draftへ顧客・要員・単価・精算幅が引き継がれ、
    注文から契約draftが生成される。契約化を2回実行しても契約は1件。
    注文条件が見積/契約と異なる場合は差分が表示され承認対象になる。
  - **実装ガイダンス**: 条件差分、approval hook（approval spec的adapter）、draft共通化、冪等。
    Contract draftは既存`buildAndSaveDraft`相当の共通経路へorder sourceを追加する（design §2）。
    冪等は`t_contract.order_line_id`のUNIQUE＋状態CASの二重防御（design §5.3）。
  - **テスト要件**: L2〜L3。条件引継ぎ、差分検出、**契約化2回で1件**、
    approval経由の取消時のrollback、注文取消と契約化の競合。
  - **Demo**: 見積から注文2明細→契約2件。契約化ボタンを二重clickして契約が2件にならないことを確認。

- [x] A1. 注文画面/注文請PDF/archive
  - **Objective**: 注文の一覧・詳細・差分が見え、受領した注文書原本をuploadして注文請書PDFを発行できる。
    どちらもarchiveへ保存され、日付・金額・相手先で検索できる。同じ原本hashの二重登録は拒否される。
  - **実装ガイダンス**: CRUD、原本upload、PDF、document links。
    document種別は`ORDER_RECEIVED`/`ORDER_ACKNOWLEDGEMENT`/`ACCEPTANCE`（design §3）。
    **PO重複は警告、同一原本hashは拒否**（design §5.3）。この2つを混同しない。
  - **テスト要件**: L1〜L3。PDF生成/hash、document ACL（注文一覧と同じscope）、
    **PO重複が警告で登録は通ること**、同一hashの登録が拒否されること、mobile 390px。
  - **Demo**: 原本受領→注文請発行。同じPO番号で警告が出つつ登録でき、同じPDFの再uploadは拒否されることを確認。

- [x] B1. 月次検収service/UI
  - **Objective**: 月次の作業実績を提出し、顧客が検収または差戻しできる。差戻し後は再提出できる。
    提出後に工数が変わっても検収額は変わらない。
  - **実装ガイダンス**: submit/accept/reject/cancel、work record link。
    **提出時にwork record `version`と金額をsnapshot**（design §5.1）。
    提出後の工数変更で検収を自動更新せず、差戻し→再提出で処理する。
  - **テスト要件**: L2〜L3。状態遷移、`version` CAS、差戻し→再提出、
    **提出後に工数を変更しても検収額が変わらないこと**、顧客と内部代行の同時操作で先着1件のみ成功。
  - **Demo**: 勤怠確定→検収差戻し→再提出→検収済。提出後に工数を変えても検収金額が動かないことを確認。

- [x] B2. 請求/月次締め/通知統合
  - **Objective**: 未検収の契約からは請求が生成できず、検収不要と明示した契約だけが例外的に生成できる。
    月次締めchecklistに未検収件数が出て、注文未受領・検収未提出・期限超過が通知される。
  - **実装ガイダンス**: invoice生成queryへ
    `acceptance_required = FALSE OR EXISTS(検収済のacceptance)` を**WHERE句として**追加する（design §5.3）。
    取得後のJava filterにしない。deadline通知/KPI。
    月次締めchecklistの未検収件数は**閲覧者のscopeで数える**（design §5.2）。
  - **テスト要件**: L2〜L3。検収要/不要の両分岐、**未検収契約からの請求生成が0件**、
    通知の重複なし、月次締め件数のscope、検収取消と請求生成の競合。
  - **Demo**: 未検収請求拒否→検収後生成。検収不要契約が理由付きで請求できることを確認。

- [x] Remediation (Round 9 指摘対応)
  - [x] R9-SPEC: requirements.md, design.md, tasks.md, review-ledger.md, spec-execution-ledger.md の改訂およびS09 FIX戻し
  - [x] R9-MIG: V80マイグレーション構造判定の強化（NON_UNIQUE/column order/prefix/cascade）、marker前失敗のdurability確保、legacy fixture追加 (P1-01, P1-02)
  - [x] R9-SCOPE: HRロールの全パス遮断（注文・検収・文書・通知・KPI）および検収文書の対象月as-of scope判定 (P0-01)
  - [x] R9-CONCURRENCY: submit対reopenの行ロック、approval adapterのバージョンロック/TOCTOU防止、二重契約化retry修正、hash並行uploadのDB UNIQUE原子化 (P1-03, P1-04, P1-09, P2-01)
  - [x] R9-DOCUMENT: 注文請PDFアーカイブfail-closed化、発行法人のデータバインド・PDF印字、専用downloadエンドポイントの `file.download` 権限・双方向監査ログ (P1-05, P1-07, P1-08)
  - [x] R9-INVOICE-UI: 検収免除理由の非空必須判定（DB/SQL/API）、PO警告の自己判定バグ修正、UIアクセシビリティ対応 (P1-06, P2-02, P2-03)
  - [x] R9-M: 実MySQL環境での見積〜請求全通しBrowser Demo証跡（HAR/console/PNG）、DB通知dedupe/KPI自動テスト強化、L4実行 (P1-10, P2-04)

- [x] Remediation (Round 10 指摘対応)
  - [x] R10-SPEC: requirements.md, design.md, tasks.md, review-ledger.md, spec-execution-ledger.md の改訂およびS09 FIX戻し
  - [x] R10-MIG: V80を原状復元し、新規順方向マイグレーションV81__order_acceptance_remediation.sqlを新設。NON_UNIQUE/全構成列/順序/prefix/cascade三分岐制御、marker前失敗のdurability確保、実V79.1 legacy fixture追加 (R10-P0-01, R9-P1-01, R9-P1-02)
  - [x] R10-SCOPE: StatusConstants.ROLE_HR ("HR") を使用し、DocumentServiceImpl/DataScopeServiceImpl/AcceptanceServiceImpl/MonthlyClosingServiceImpl/DashboardServiceImplにてHRアクセスを完全遮断。検収文書のarchive list/detail/downloadを同じworkMonth-as-of SQL母集団へ統一 (R9-P0-01)
  - [x] R10-CONCURRENCY: WorkRecordServiceImpl.reopenMonth にて Acceptance を FOR UPDATE ロックし提出済/検収済を拒否。登録用 (tenant_id, document_type, file_hash) の DB UNIQUE 制約追加による原本hash重複アトミックClaim。明細契約化の二重clickキー競合で FOR UPDATE 再読 (R9-P1-03, R9-P1-09, R9-P2-01)
  - [x] R10-DOCUMENT: 注文請PDF発行のPOST/DL GET分離、ActionPermissionResolver/ApiAuditFilterのexact/method-aware判定、/api/autocomplete/legal-entities へのUI候補接続・自社代表法人動的解決、PDF再発行時のアーカイブ済bytes返却 (R9-P1-07, R9-P1-08, R10-P1-01)
  - [x] R10-INVOICE-UI: V81/V1/H2へ DB CHECK 制約 chk_contract_acceptance_exemption を追加、PO警告の自己除外、UIアクセシビリティ対応（aria-labelledby, aria-label, explicit id） (R9-P1-06, R9-P2-03)
  - [x] R10-M: 実MySQL環境での見積〜請求閉ループBrowser Demo証跡（HAR/console/PNG）、DB通知dedupe/KPI境界自動テスト追加、L4全量実行・git diff --checkパス (R9-P1-10, R9-P2-04)

- [x] Remediation (Round 11 FAIL指摘対応)
  - [x] R11-P1-01: `.tmp-ui-scale-r3` の未登録dirty gitlinkをroot indexから除去し、nested repositoryは削除せず`.gitignore`で再取り込みを防止
  - [x] R11-P1-02: Document archive scopeの全検収行Javaロードを廃止し、workMonth/as-ofで許可されたcontract IDをSQL母集団へ渡してdocument IDを絞り込み
  - [x] R9-P1-09: DocumentStorage put前のrollback compensation登録、transaction中の失敗cleanup、Local storage partial file cleanupを追加
  - [x] R9-P1-10: 注文画面のfilter/table cardを分離し、390pxのfilter開閉・sidebar開閉・modal/reload/back/keyboard/double-click証跡を再取得
  - [x] R11-P2-01: `ConcurrentSubmitReopenTest` の競合判定を409 `BusinessException`かつ許容message keyに限定し、その他Throwableをunexpectedとして失敗扱い
  - [x] R11-M: 修正Head `67de0d4`で定向test 30/0/0/0・9/0/0/0・3/0/0/0、L4 282/1582/0/0/0、実MySQL smoke、Browser evidence、`git diff --check`を確認

- [x] M. 全通し
  - **Objective**: 見積→注文→契約→勤怠→検収→請求がIDで追跡でき、
    既存のdocument/approval/contract/invoice機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    document/approval回帰、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 見積→注文→契約→勤怠→検収→請求。各段階のIDが次段階から辿れることを提示。
