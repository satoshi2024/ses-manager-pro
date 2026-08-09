# Implementation Plan — 顧客・BP外部ポータル

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T081〜T086はL0〜L3の定向test・直接回帰、T087でL4全量を実行する。
> SecurityFilterChain/portal scope変更はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> ただし**本specは§2（認可母集団）の既定解が適用できない唯一のspec**である。portal userは`sys_user`ではなく、
> DataScope・組織scope・menu権限のいずれも持たない。母集団の解決は `design.md` §6「決定表」を正とする。
>
> **Migration**: 本specの予約番号は **V86**。staffing(V85)のmerge後に着手する。
> `SecurityConfig.java`は本specの統合担当が先に変更・mergeし、engineer portal(S14)はその後。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [ ] 0. G3/G8と公開field inventory
  - **Objective**: portalの公開domain・利用規約・本人確認方式が確定し、
    permission×画面×fieldのmatrixとして「顧客/BPに何を見せて何を見せないか」が表になる。
    以降のDTO実装が公開可否を推測せずに済む状態にする。
  - **成果物**: domain/規約/本人確認/permission×画面×field matrix、threat model。
  - **Demo**: G3 security boundary/field matrixの社内security・support承認。規約の外部法務承認はM/本番gate。
  - **実装ガイダンス**: production codeを変更しない。
    公開文書種別は**G8のallow-listだけ**を使う（前提節）。allow-list外を「たぶん見せてよい」で追加しない。
    threat modelはIDOR・token再利用・組織跨ぎ・file経由の漏洩を最低限含める。
  - **テスト要件**: L0。matrixの全画面×全fieldに公開可否が付いていること、
    公開文書種別がG8 allow-listと一致すること、`git diff --check` exit 0。

- [ ] F1. portal org/user/invite/consent DDL
  - **Objective**: 顧客組織/BP組織とportal userを登録し、期限付き1回限りの招待tokenで参加できる。
    token再利用・期限切れ・email不一致は拒否される。全portal userにTOTP MFAと規約同意が要求される。
  - **実装ガイダンス**: **V86**/V1/H2(`sql/schema-portal-h2.sql`)/MySQL smoke、token/hash/session/permission。
    招待tokenは256bit random、DBは**SHA-256 hashのみ保存**、URL log/mailerでmask（design §2）。
    **一回性はDB CASで保証**（`UPDATE ... WHERE used_at IS NULL`、design §6.3）。
    アプリ側の「存在チェック→更新」にしない。
    `used_at IS NULL`だけで有効と判定せず、期限・email・組織の**4条件すべて**を検証（design §6.1）。
  - **テスト要件**: L1〜L3。token再利用/期限切れ/email不一致/組織不一致の各拒否、
    **token同時使用で1件だけ成功**、tenant分離、停止済userのlogin拒否、
    tokenが平文でDB/ログに残らないこと。
  - **Demo**: 招待→登録→MFA設定→規約同意。同じtokenを2回使って2回目が拒否されることを確認。

- [ ] F2. 専用security chain/DTO boundary
  - **Objective**: portal userが内部URL・内部APIへ到達できず、
    顧客Aが顧客B/BPのID・URL・fileを一切取得できない。公開DTOに原価・粗利・営業memoが構造的に含まれない。
  - **実装ガイダンス**: `/portal/**`・`/api/portal/**`専用`SecurityFilterChain`と`PortalLoginUser`（design §2）。
    **`PortalLoginUser`を内部`LoginUser`へ変換する経路を作らない**。
    `PortalAuthorizationService`が`target → customer_id / bp_company_id`を**query boundaryで**検証する。
    取得後checkにしない。session cookie名/path/CSRFを内部と完全分離。rate limit。
  - **テスト要件**: L3。**顧客A/顧客B/BPの3組織matrixを全endpoint×全HTTP methodでparameterized test**、
    内部API/内部URLへの403、公開DTOのfield allowlist（原価/粗利/営業memo/他社情報が含まれないこと）、
    秘密の非ログ出力、rate limitの発動。
  - **Demo**: portal userが内部URLへ403。顧客Aのsessionで顧客BのIDを直接指定して404/403になることを確認。

- [ ] A1. 顧客portal
  - **Objective**: 顧客が自社の見積・注文請・契約・作業報告・検収・請求を閲覧/downloadでき、
    月次作業報告を検収または差戻しできる。顧客の検収が内部のacceptanceへ1回だけ反映される。
    顧客は請求の入金済状態を直接変更できない。
  - **実装ガイダンス**: documents/acceptance/invoice/支払予定/問い合わせ。
    **検収は`AcceptanceService`へ委譲**し、order specの`UNIQUE(contract_id, work_month)`＋状態CASを使う（design §6.3）。
    portal側で独自の検収テーブル・独自の状態機械を作らない。
    電子署名はCloudSign等の外部URLへ遷移し、portalが署名を代行しない（R2.4）。
  - **テスト要件**: L2〜L3。**顧客portalと内部代行の同時検収で先着1件のみ成功**、
    差戻し→再提出、file ACL（自社分のみ）、入金済状態を変更するAPIが存在しないこと、mobile。
  - **Demo**: 作業報告→顧客検収→内部請求可。顧客と内部が同時に検収して1件だけ成立することを確認。

- [ ] A2. BP portal
  - **Objective**: BPが自社の空き要員を登録/更新し、内部営業のreview後に有効化される。
    発注条件の受領確認、請求書/作業報告書の提出、支払状態の参照ができる。
    口座変更は申請のみで、内部承認前は支払先へ反映されない。
  - **実装ガイダンス**: availability submission、発注確認、請求提出、支払参照、口座変更申請。
    空き要員はingestion review、口座はapproval requestへ委譲（design §3）。
    BPは金額/支払状態を変更できない（R3.3）。
  - **テスト要件**: L2〜L3。**口座変更が承認前にmasterへ反映されないこと**、
    review前のavailabilityが内部の候補に出ないこと、BP組織scope（自社分のみ）、
    金額/支払状態を変更するAPIが存在しないこと。
  - **Demo**: BP提出→内部review→支払予定表示。口座変更を申請し承認前は旧口座のままであることを確認。

- [ ] B1. 管理/通知/利用規約
  - **Objective**: 内部管理者がportal組織・user・招待・session・access logを管理でき、
    規約改定時に再同意が求められる。email通知のlinkがlogin後に目的画面へ安全に戻る。
  - **実装ガイダンス**: user/invite/session/log、terms consent、email preference。
    **return URLは相対のみ**（design §5）。外部URLへのopen redirectを作らない。
    内部contactの退職/無効化でportal accessを失効させる（R1.5）。
  - **テスト要件**: L2〜L3。**return URLのopen redirect拒否**、通知の重複なし、
    terms更新後の再同意強制、contact失効連動でのaccess失効。
  - **Demo**: 規約改定後再同意。外部URLをreturn URLに入れて拒否されることを確認。

- [ ] M. penetration/回帰/運用
  - **Objective**: 顧客A/顧客B/BPの3組織で相互漏洩がなく、portal停止と復旧が訓練できる。
    内部の既存機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    **IDOR matrix全endpoint**、rate limit、file scan、mobile browser、
    内部SecurityConfigの回帰（内部ログインが壊れていないこと）、Node/JS syntax、`git diff --check`。
  - **Demo**: 顧客A/B/BPの3組織受入と停止/復旧訓練。3組織間で相互にデータが見えないことを提示。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    利用規約の外部法務承認は本taskのPASS条件ではなく、**本番releaseのgate**として別管理する。
