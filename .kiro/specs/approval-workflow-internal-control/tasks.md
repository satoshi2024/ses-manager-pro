# Implementation Plan — 承認ワークフロー・内部統制

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T041〜T046はL0〜L3の定向test・直接回帰、T047でL4全量を実行する。
> 共通approval adapter/state machine合流時はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> 本specは「状態機械 × 期間 × 金額 × 権限」の四重交差であり、S02と同じ事故構造を持つ。
> **design.md §6.2の金額帯境界を実装前に確定すること。実装中に決めない。**
>
> **Migration**: 本specの予約番号は **V75**。BP(V70/V71)とCRM(V73/V74)のmerge後に着手する。V72は永久欠番。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [ ] 0. G7と対象操作inventory
  - **Objective**: 対象5業務（見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopen）の
    現endpoint・現service・申請field・route・SLA・職務分離が表として確定する。
    以降のadapter実装が「どのmethodを1回だけ呼ぶか」を推測せずに決められる状態にする。
  - **成果物**: 操作、現endpoint/service、申請field、route、SLA、職務分離表。
  - **Demo**: 財務/管理者レビュー。金額帯の閾値と承認者はG7で決定済みか、既定を採るかを明記して提示。
  - **実装ガイダンス**: production codeを変更しない。対象5業務の既存単件methodを特定し、
    `applyApproved`が委譲する先を1対1で対応付ける。既存の状態機械/金額検証/監査を**再実装しない**前提を確認する。
    G7は`blocking=no`だが、閾値を変える場合は推奨既定を採るか発注者決定を得たことを明記する。
  - **テスト要件**: L0。対象5業務の全endpointが表に存在すること、
    各操作に対応するrequirements IDが付いていること、`git diff --check` exit 0。

- [ ] F1. route/request/action/delegation DDL
  - **Objective**: 対象操作が直接確定されず申請draftと差分snapshotになる。
    routeが対象種別・組織・金額帯・申請者roleから1件に決まり、決まらない場合は申請が受け付けられず管理者へ通知される。
    申請者自身は自分の申請を承認できない。
  - **実装ガイダンス**: **V75**/V1/H2(`sql/schema-approval-h2.sql`)/MySQL smoke、engine core/CAS。
    **route snapshotは申請時に確定し以後不変**（design §6.1）。
    金額帯はmin/max ともに**inclusive**、判定に`amount_snapshot`（税込）を使う。
    `amount_snapshot IS NULL`を0円として金額帯へ当てない。負の金額は**絶対値**で判定（design §6.2）。
    複数route該当時の決定順は「組織の具体性→金額帯の狭さ→version_noの新しさ」。
  - **テスト要件**: L1〜L3。route解決の**境界fixture `min-1/min/max/max+1`**、
    該当routeなしで申請拒否（既定routeへfallbackしない）、自己承認の拒否、
    並列groupの全員承認/1人却下、代理期間の内外、`version`+`current_step`の複合CAS競合。
  - **Demo**: 金額帯の境界値ちょうどの申請が意図したrouteへ流れることをcurlで確認。
    route未設定の金額帯で申請すると拒否され管理者へ通知が飛ぶことを確認。

- [ ] F2. 5 target adapters
  - **Objective**: 見積・契約・請求・BP支払・月次締めの5業務が申請経由でのみ確定し、
    最終承認で既存serviceのmethodが**1回だけ**呼ばれる。
    承認中に対象が変更されていたら競合として再申請を求め、古いsnapshotを適用しない。
  - **実装ガイダンス**: 既存service委譲、version snapshot、idempotency、outbox。
    **最終承認transactionの順序を守る**（design §3/§6.4）:
    request lock → target version再検証 → `applyApproved` → request approved → outbox insert。
    外部API/メール送信はDB transaction外（platform-invariants §3.3）。
    対象側に`UNIQUE(approval_request_id)`を置いて二重適用を構造的に防ぐ。
  - **テスト要件**: L2〜L3。adapterごとに正常/version競合/rollback/再送、
    **二重clickとretryで最終業務操作が1回**、承認transaction rollback時に対象が変わらないこと、
    outboxがcommit後にのみ実行されること。
  - **Demo**: curlで各対象申請→承認。同じ承認リクエストを10回送って業務操作が1回だけ起きることを確認。

- [ ] A1. inbox/request/diff/history UI
  - **Objective**: 自分の申請・承認待ち・完了が一覧で見え、差分・comment・履歴・対象画面へ辿れる。
    承認者が閲覧権限を持たないfieldは「変更あり（値非表示）」として表示され、値が漏れない。
  - **実装ガイダンス**: 一覧、差分、comment、対象link、mobile。
    可視性は`applicant_id` OR 解決される承認者 OR 代理の当事者。**組織scopeを重ねない**（design §6.3）。
    `diff_json`の表示はfield単位permissionに従う（原価・給与・口座を承認画面で素通ししない）。
  - **テスト要件**: L2〜L3。requester/approver scope、**field masking（承認画面とexport両方）**、
    差戻し→修正→再申請の一連、mobile 390px。
  - **Demo**: 差戻し→修正→再申請→承認。原価fieldの権限がない承認者の画面で値が出ないことを確認。

- [ ] A2. route/代理管理
  - **Objective**: routeをversion付きで編集し適用開始日を指定できる。
    route改版後も進行中の申請の承認者は変わらない。
    代理は期間・対象・委任者・理由を持ち、監査表示で「代理」と明示される。
  - **実装ガイダンス**: version/有効日、approver preview、代理期間。
    **代理は承認操作の実行時点で評価する**（design §6.1。申請時点ではない）。
    同一stepに本人と代理者の両方が解決された場合は**先着1件を有効**とし2件目はCAS失敗（design §6.4）。
  - **テスト要件**: L2〜L3。**進行中申請のroute snapshot不変**、
    申請〜承認の間に代理期間が開始/終了した両case、approver解決不能時の受付拒否、
    本人と代理の同時承認で承認者数が二重にならないこと。
  - **Demo**: route変更前後の2申請で承認者が異なる。代理期間中と期間外で承認可否が変わることを確認。

- [ ] B1. 通知/SLA/escalation
  - **Objective**: 申請・差戻し・承認・却下・期限超過が**対象本人だけ**に届く。
    stepごとのSLA期限を超えると上位責任者へescalateされ、同じ超過で二重に通知されない。
  - **実装ガイダンス**: recipient限定、冪等scheduler、`NotificationLinks`定数を使う。
    `sla_hours IS NULL`は**期限なし**でescalation対象外（design §6.1）。
  - **テスト要件**: L2〜L3。期限境界（超過直前/ちょうど/直後）、
    **同一超過で通知が重複しないこと**、宛先が対象本人に限定されること、`sla_hours IS NULL`が対象外であること。
  - **Demo**: overdueを上位責任者へ通知。schedulerを2回起動して通知が1件のみを確認。

- [ ] M. 対象画面統合/回帰
  - **Objective**: 対象5業務の画面が「実行」から「申請」へ変わり、申請者単独では確定できない。
    二重click/retryでも業務操作は1回。既存の5業務の機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    5業務のbrowser通し（desktop/390px）、既存Contract/Invoice/BpPayment/Closingの回帰、
    Node/JS syntax、`git diff --check`。
  - **Demo**: 申請者単独確定不可と二重実行0を確認。5業務それぞれで申請→承認→適用を通す。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
