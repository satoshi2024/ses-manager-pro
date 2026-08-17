# Implementation Plan — 要員セルフサービスポータルV2

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T088〜T092はL1〜L3の定向test・直接回帰、T093でL4全量を実行する。
> UI Taskは対象browser/MVCを実施し、全画面回帰はMへ集約する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> 本specの母集団は原則「本人のみ」であり、**engineer-account linkから解決する。
> リクエストの`engineerId`を信用しない。**
>
> **Migration**: 本specの正式migrationは **V105**（実在済み）。external portal(V104)のsecurity chain merge後に着手する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [x] F1. change/expense/1on1/survey DDL
  - **Objective**: 要員がプロフィール変更・経費・1on1候補日・survey回答を登録でき、
    本人Aが本人Bのデータを一切取得できない。任意のJSONからentityへ反映される経路が存在しない。
  - **実装ガイダンス**: **V105**/V1/H2(`sql/schema-engineer-selfservice-h2.sql`)/MySQL smoke、本人scope、field allowlist。
    **`request_type`ごとのDTOでallowlist**（design §2/§6.3）。
    allowlist外のkeyが来たら**リクエストを拒否**する（黙って無視しない）。
    `applied_at IS NULL`は未反映であり、承認済と混同しない（design §6.1）。
  - **テスト要件**: L1〜L3。**本人A/本人Bのparameterized scope test**、
    allowlist外keyの拒否、不正JSONの拒否、状態遷移、`version`競合、
    `applied_at IS NULL`の承認済申請が検出できること。
  - **Demo**: 要員が変更申請を登録し、別要員のIDを指定して404になることを確認。
    allowlist外のfieldを送って拒否されることを確認。

- [x] A1. my dashboard/profile/skill申請
  - **Objective**: 要員が公開プロフィール・skill・careerの変更を申請でき、
    HR承認前はEngineer masterが一切変わらず、承認後に1回だけ反映される。
    本人はスキルシートの公開項目をpreviewできるが、原価・commissionは見られない。
  - **実装ガイダンス**: preview/diff/approval apply、公開契約条件。
    approval finalで既存Engineer/Skill/Career serviceをtransaction内に呼ぶ（design §2）。
    **target version競合時は再申請を要求**（design §6.3）。自動マージしない。
  - **テスト要件**: L2〜L3。**承認前のmaster不変**、承認後の反映が1回だけ、
    再送で二重反映しないこと、原価/commissionが本人レスポンスに含まれないこと、
    master側が同時更新された場合の競合検出。
  - **Demo**: skill申請→HR承認→sheet preview。承認前にmasterのskillが変わらないことをSQLで確認。

- [x] A2. 本人給与/勤怠導線
  - **Objective**: 要員が自分の給与明細だけを再認証/MFA後に閲覧でき、
    勤怠・休暇・作業報告へmy dashboardから遷移できる。本人Aが本人Bの明細を取得できない。
  - **実装ガイダンス**: `/api/my/payroll`専用endpoint（design §3）。
    **管理API`FreeePayrollApiController`を本人用に再利用しない**。
    engineer-account linkから本人を解決し、**request engineerIdを受け取らない**。
    sensitive responseは`Cache-Control: no-store`、再認証時刻/MFA contextを検証。
    一覧に金額を不用意に露出しない（R2.2）。
  - **テスト要件**: L2〜L3。**本人scope（engineerIdパラメータが存在しないこと）**、
    session再認証/MFA未実施時の拒否、`no-store`ヘッダ、provider障害時の挙動、
    一覧レスポンスに金額が含まれないこと。
  - **Demo**: 本人が自分の明細だけ表示。MFA未実施の状態で明細APIが拒否されることを確認。

- [x] B1. 経費申請/承認/archive
  - **Objective**: 要員が交通費/立替経費を領収書付きで申請し、承認後に会計へ1回だけ連携される。
    同じ経費が二重に会計連携されない。未scan/感染の領収書は本人にも表示されない。
  - **実装ガイダンス**: receipt scan、approval adapter `EXPENSE_REQUEST`、accounting outbox link。
    **`accounting_job_id`のUNIQUEで冪等**（design §6.3）。
    金額は円。**本人が任意の科目codeを送れない**（design §4）。税区分/勘定科目は会計側mapping。
    承認後の領収書差替えは再申請（R3.3）。
  - **テスト要件**: L2〜L3。金額validation、**二重会計連携なし**、差戻し→再申請、
    receipt ACL（本人scope）、**未scan/感染時に本人にも表示されないこと**（fail-closed）、
    任意科目codeの送信が拒否されること。
  - **Demo**: 経費→承認→会計待ち。同じ経費の連携を2回試行してjobが1件のみを確認。

- [x] B2. 1on1/survey/privacy
  - **Objective**: 要員が1on1候補日を申請して実施記録の公開部分を閲覧でき、
    稼動満足度・負荷・継続意向を定期回答できる。confidential相談はHR/指定管理者だけが見え、
    営業画面へ自由記述が出ない。少人数組織のsurvey集計は非表示になる。
  - **実装ガイダンス**: 日程、公開/private note、campaign、匿名閾値、retention input。
    **`private_note_ref`を通常の`RetentionRisk` DTOへ出さない**（design §5/§6.2）。
    retention riskの入力に使う場合もスコアへの寄与のみで**原文を表示しない**（R4.4）。
    最低回答数configで少人数組織を非表示（design §5）。
    survey未回答は平均値の母数から除外する（design §6.1）。
  - **テスト要件**: L2〜L3。**confidential相談が営業/マネージャーのレスポンスに含まれないこと**、
    最低回答数未満のsegmentが非表示になること、未回答が0点として集計されないこと、通知。
  - **Demo**: 回答→HR限定相談→followup。営業ログインで相談内容が取得できないことをAPIレスポンスで確認。

- [x] M. 回帰
  - **Objective**: 要員ログインから全my機能が一気通貫で動き、
    本人以外のデータが1件も漏れない。既存の`/my/timesheet`・Engineer・Payroll機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    **PII leak scan（本人A/Bのparameterized全endpoint）**、mobile 390px、
    既存`/my/timesheet`回帰、Node/JS syntax、`git diff --check`。
  - **Demo**: 要員loginから全my機能一気通貫。本人Bのデータが本人Aのどのレスポンスにも出ないことを提示。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
