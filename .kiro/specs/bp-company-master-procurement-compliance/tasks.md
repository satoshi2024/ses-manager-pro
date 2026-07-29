# Implementation Plan — BP会社マスタ・発注コンプライアンス

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T034〜T039はL0〜L3の定向test・直接回帰、T040でL4全量を実行する。
> migration/共通取引先contract変更時だけ中間昇格を判定する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの予約番号は **V66**。着手時にmerge済み`db/migration`の最新を再確認し、
> 衝突していれば後発（本spec）を上へ繰り上げる。前の欠番を埋めない。V59は永久欠番。

- [ ] 0. G2法務確認/既存自由入力profiling
  - **Objective**: 取適法/フリーランス法の必須明示項目と支払ruleが確認票として確定し、
    既存の会社名自由入力のdistinct値・件数・候補衝突が把握できている。移行で人が解決すべき件数が見積もれる状態にする。
  - **成果物**: 適用確認票、必須明示項目、支払rule、distinct値/件数/候補衝突。
  - **Demo**: 公式URL/版付き適用確認票の社内責任者確認と移行dry-run報告。外部専門家承認はM/本番gate。
  - **実装ガイダンス**: production codeを変更しない。`BpAvailability.bpCompany`と
    `BpPayment.payeeCompanyName`のdistinct値を実データで数え、正規化後の衝突（同名別法人）を列挙する。
    法的結論をシステムで断定しない。確認票は「確認すべき項目」であって判定結果ではない。
  - **テスト要件**: L0。確認票の全項目に根拠URL/版/確認日が付いていること、
    dry-run件数がDBの実数と一致すること、`git diff --check` exit 0。

- [ ] F1. BP master/terms/contact/bank DDL
  - **Objective**: BPを法人/個人事業主/フリーランスで登録でき、法人番号・適格請求書番号・支払条件・
    連絡先・口座を管理できる。口座は一覧・詳細・exportのいずれでもマスク表示され、復号値が出ない。
  - **実装ガイダンス**: **V66**/V1/H2(`sql/schema-bp-company-h2.sql`)/MySQL smoke、暗号化/masking、service。
    `compliance_applicability IS NULL`は**未確認**であり「非該当」ではない（design §5.1）。
    `t_bp_terms`の版切替は`effective_from`基準で、**支払確定日**で解決する。
  - **テスト要件**: L1〜L3。法人番号/登録番号のunique、terms期間の重複、
    **bank復号値がどのAPIレスポンスにも出ないこと**、状態遷移、`compliance_applicability IS NULL`がfindingになること。
  - **Demo**: BP法人と個人事業主を登録。口座を登録し一覧/詳細/CSVすべてで末尾のみ表示されることを確認。

- [ ] F2. 既存在庫/要員/支払移行
  - **Objective**: 既存の会社名自由入力から仮BPが生成され、exact一致分は自動linkされる。
    複数候補・空値は例外一覧に出て人が解決できる。過去のBP支払は会社名・支払条件のsnapshotを持ち、
    マスタ変更後も表示が変わらない。
  - **実装ガイダンス**: affiliation、`bp_company_id`、snapshot、例外解決。
    正規化は法人格/全半角/空白を除くが、**同一候補を自動mergeしない**（R1.5）。
    仮BP生成は`UNIQUE(tenant_id, normalized_name)`で冪等（design §5.4）。
    `t_engineer_bp_affiliation`はplatform-invariants §1.2の期間代数を全case適用する。
  - **テスト要件**: L2〜L3。移行前後の件数/金額合計一致、同名別法人が法人番号で分離されること、
    read fallback/write ID必須、再実行で仮BPが重複しないこと、BP乗換（同日/未来/遡及/空白期間）の期間case。
  - **Demo**: staging DBで未解決0件まで解消。マスタの会社名を変更しても過去支払の表示が変わらないことを確認。

- [ ] A1. BP管理画面
  - **Objective**: BP詳細から連絡先・口座・条件・文書・所属要員・評価・価格協議・支払へ辿れる。
    取引停止したBPが新規提案/発注の候補に**SQLレベルで**現れない。
  - **実装ガイダンス**: detail tabs、document link、評価、停止、autocomplete。
    Autocomplete/ingestionは候補score+理由を返し、confirm時にID必須。
    `status = 取引停止`は候補queryの**WHERE句で**除外する（design §5.3）。取得後filterにしない。
  - **テスト要件**: L1〜L3。CRUD、scope（営業は既存DataScope、組織で追加制限しない）、CSRF、
    PII field mask、取引停止BPが候補APIに出ないこと、mobile 390px。
  - **Demo**: BP→所属要員→支払までdrilldown。BPを取引停止にして提案候補から消えることを確認。

- [ ] B1. 発注コンプライアンスrule/価格協議
  - **Objective**: 必須明示事項が不足した発注は確定できず、不足項目が具体的に表示される。
    支払期日が起算日から60日を超える設定、方針に反する支払手段・手数料負担が警告される。
    価格協議の要請日・回答日・合意額が履歴として残る。
  - **実装ガイダンス**: applicability確認、明示項目、60日/支払手段/手数料、交渉履歴。
    findingは**都度導出し永続化しない**（design §5.4）。ack/対応状態だけを永続化する。
    ruleはconfig version付き。システムは不足/期限/不整合だけを検査し、法的結論を断定しない。
  - **テスト要件**: L1〜L3。60日の境界（59/60/61日）、具体日未特定の検知、
    手数料控除の検知、例外理由+承認がある場合の抑止、`compliance_applicability`未確認時の挙動。
  - **Demo**: 不足発注を拒否し、補完後に警告0。受領日+60日ちょうどと+61日で結果が変わることを確認。

- [ ] B2. リスクdashboard/通知
  - **Objective**: 期限切れ文書・支払期限リスク・未確認適用区分・低評価BPが一覧で見え、
    そこから対象BPの詳細へ遷移できる。通知は担当営業と管理者だけに届き、重複しない。
  - **実装ガイダンス**: 期限文書、未確認、低評価、支払期日。通知の宛先は個人指定で組織一斉にしない。
  - **テスト要件**: L2〜L3。通知の冪等（同一リスクで1日1回）、recipient scope、
    dashboardの母集団が閲覧者のscopeに従うこと。
  - **Demo**: BPリスクから対象detailへ遷移。同じリスクで通知が二重に出ないことを確認。

- [ ] M. 回帰/旧入力廃止判定
  - **Objective**: 現役のBP要員・在庫・支払で会社名の自由入力が0件になり、新規の自由入力ができない。
    既存のBP支払・在庫・提案フローが壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、migration reconciliation（件数/金額）、
    Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 新規自由入力不可、既存フロー全通し。現役データの自由入力0件をSQLで提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
