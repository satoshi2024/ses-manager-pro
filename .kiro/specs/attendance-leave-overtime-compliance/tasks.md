# Implementation Plan — 雇用勤怠・休暇・時間外労働

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T067〜T073はL0〜L3の定向test・直接回帰、T074でL4全量を実行する。
> calculator/締め/共通transaction変更はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **時間外計算の値・境界・変更手順の唯一の正は `overtime-rules.md`。** 値は**確定済み**であり、
> 社労士確認待ちではない。実装は同書の値でそのまま進める。確認結果がずれた場合は同書§4の手順で変更する
> （多くは`/system-config`の値変更だけで済む）。**閾値をコードへ直書きしないこと。**
>
> **Migration**: 本specの正式migrationは **V83**。order(V80/V81)のmerge後、dispatch(V84)と並行実装可。V83のmerge/applyはS10の予約V84確定後に行う。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [x] 0. source matrixと法人別36協定の棚卸し
  - **Objective**: 雇用勤怠の正が本システムであることがsource matrixとして確定し、
    法人ごとの36協定の実内容（特別条項の有無、上限、起算日、法定休日の曜日）と適用除外者が棚卸しされる。
    F1が`m_overtime_agreement`へ登録すべき行を、推測せずに決められる状態にする。
  - **成果物**: source of truth matrix、勤務区分、カレンダー（法定休日の曜日）、休暇種別、
    **法人別の36協定内容一覧**、適用除外者（管理監督者）の一覧。
  - **Demo**: 本システムを正とするsource matrixのHR確認と、法人別36協定の棚卸し結果の提示。
  - **実装ガイダンス**: production codeを変更しない。
    **時間外の計算ルール自体は`overtime-rules.md`で確定済みなので、本taskで決め直さない。**
    本taskが集めるのは「その確定ルールへ流し込む法人別のデータ」である。
    `t_work_record_daily`（客先請求工数）と雇用勤怠が**別sourceである**境界を明文化する。
    協定書が未入手の法人は「未入手」と記録する。`overtime-rules.md`§3のとおり、
    協定行が無い法人は判定不能としてfindingになる（既定値で「適合」にしない）。
  - **テスト要件**: L0。法人ごとに協定の有無と入手状況が記録されていること、
    法定休日の曜日が全法人分そろっているか未確認と明記されていること、
    source matrixが既存work recordとの境界を含むこと、`git diff --check` exit 0。

> S11の正式migrationは **V83**。S10はV84へ繰り上げたため、V83のmerge/applyはS10の予約V84確定後に行う。過去のV82予約を補填しない。

- [x] F1. calendar/attendance/month/leave/agreement DDL
  - **Objective**: 社員の日別出退勤・休憩・勤務区分を分単位で登録でき、法人/組織/個人別の勤務カレンダーを持てる。
    月次状態が入力中→提出済→承認済→締め済で進み、締め済みは管理承認なしに変更できない。
  - **実装ガイダンス**: **V83**/V1/H2(`sql/schema-attendance-h2.sql`)/MySQL smoke、
    **分の整数モデル**（浮動小数を使わない、design §1）、scope。
    `(source, source_external_id)`にUNIQUE。
    `scheduled_minutes IS NULL`（所定日でない）と`= 0`（所定日だが0分）を区別する（design §5.1）。
    **同じV83で`overtime.*`のconfigをseedする**（`overtime-rules.md`§1/§2のconfig key全件）。
    seedは`INSERT IGNORE`で既存値を壊さない（V56の書き方に合わせる）。
    `m_overtime_agreement.valid_from`は**月初のみ許可**する制約を入れる（`overtime-rules.md`§2 #7）。
  - **テスト要件**: L1〜L3。期間/unique、締め済みの変更拒否、休暇との整合、
    `scheduled_minutes`のNULLと0の区別、外部sourceの重複登録拒否、
    **月初でない`valid_from`の登録が拒否されること**、`overtime.*` configが全キーseedされていること。
  - **Demo**: 社員の1週間分の勤怠を登録し月次集計が出ることを確認。
    締め済み月を更新しようとして拒否されることを確認。
    `/system-config`に`overtime.*`が表示され編集できることを確認。

- [x] F2. 集計/時間外calculator
  - **Objective**: `overtime-rules.md` §1の6ルールが計算され、同書の境界どおりに警告が出る。
    特に**月100時間だけが「ちょうどで違反」**、他の上限は「ちょうどは適合」になる。
    協定行の無い法人は「適合」ではなく判定不能のfindingになる。
  - **実装ガイダンス**: **値は`overtime-rules.md`が正。ここでも設計判断をしない。**
    実装が守る構造制約は同書§4/design §5.2の3点:
    (1) 判定式は`OvertimeComplianceCalculator`に**1メソッド1ルール**で書く、
    (2) 閾値をコードへ直書きせず`m_overtime_agreement`→`m_system_config`→定数の順で解決する、
    (3) 休日労働の算入可否は**calculatorへ渡す入力の選択1箇所**に閉じ、ルール内へ条件を持ち込まない。
    `special_clause = false`の協定ではルール3〜6を判定しない（同書§3）。
    boundary fixtureは`src/test/resources/fixtures/overtime/`へJSON。
    **fixtureはconfig値を読んで`limit±1`を生成**し、境界の向きだけ明示的に書く（同書§5）。
  - **テスト要件**: L1〜L3。`overtime-rules.md` §5の推奨fixture最小セット全件、特に
    **ルール4が`limit`ちょうどで違反、他は適合**、休日労働のみ超過時のルール1/2/3適合とルール4/5違反、
    所定休日労働がルール1へ算入されること、n月分データ不足時のskip（0扱いにしない）、
    協定年度またぎ（ルール2/3/6はリセット、ルール5はまたぐ）、適用除外者の全ルール非判定、
    **協定行が無い法人が「適合」にならないこと**、月中入退職を按分しないこと。
  - **Demo**: fixture結果をHRへ提示。45h/360h/80h平均それぞれの境界値ちょうどで判定が変わることを確認。

- [x] A1. 本人/管理画面と月次状態
  - **Objective**: 本人が自分の勤怠をcalendarで入力して提出でき、上長が差戻し・承認、HRが締めを行える。
    本人は自分の分だけ、上長は配下だけ、HRは法人分が見える。
  - **実装ガイダンス**: calendar、入力/提出/差戻し/承認/締め。
    **HRは法人scope、マネージャーは組織scope ∩ DataScope、本人は自己のみ**（design §5.3）。
    **営業には勤怠scopeを与えない**（design §5.3の明示的逸脱）。
  - **テスト要件**: L2〜L3。本人/上長/HRのscope分離、状態CAS、
    **営業が勤怠APIへアクセスして拒否されること**、mobile 390px。
  - **Demo**: 本人提出→上長差戻し→再提出。営業ログインで勤怠画面/APIへ到達できないことを確認。

  - **R2-P1-02 fix delta（方式A）**: 休憩区間は1勤務日に複数の開始・終了を保存し、勤務開始基準のoffsetで跨夜を一意に表す。`breakMinutes`は区間合計から導出し、calculatorは勤務区間と休憩区間のintersectionで法定内・時間外・休日・深夜を再計算する。重複、勤務区間外、開始≧終了、勤務時間全体超過、既存の区間不明行はfail-closedとする。V83は編集せず、追補DDLは**発注者割当のV91**（`t_employee_attendance_break`）で適用済み。V91実在に伴いS12〜S17の予約はV92〜V97へ繰り上げ済み。
  - **追加テスト**: 深夜前/中/後、跨夜、複数休憩、0分、全時間休憩、重複、勤務区間外、開始≧終了、8時間/週40時間/22時境界、月次再集計、既存`breakMinutes`のみの区間不明行、`breakMinutes`と区間合計の不一致400拒否（R3-P2-01）をdirect regressionへ含める。

- [x] A2. 休暇/approval統合
  - **Objective**: 有給・半休・時間休・代休・欠勤・特別休暇を申請でき、上長/HRの承認後にcalendarへ反映される。
    客先報告が必要な休暇は営業へtask/通知が作られる。
  - **実装ガイダンス**: 申請、残数/外部参照、営業通知。approval specのengineを利用する。
    **休暇残数が外部正の場合は参照表示のみで、残数不足でも申請を拒否しない**（design §5.4、R2.2）。
    本システム正の場合のみ残数CASで不足を拒否する。
  - **テスト要件**: L2〜L3。半休/時間休の分計算、残数不足時の挙動（外部正/本システム正の両方）、
    期間重複の拒否、代理承認、営業への通知が休暇種別で分岐すること。
  - **Demo**: 休暇申請→承認→calendar反映。外部正モードで残数不足でも申請できることを確認。
  - **実装内容（T071完了）**: `t_leave_ledger`（付与/消化台帳、**V98**＝発注者割当、S12〜S17はV99〜V104へ繰り上げ）、
    config `leave.balance.source`（internal=G6既定/external/未設定=判定不能finding）・`leave.balance.types`・
    `leave.sales-notification.types`、`LeaveService`（分計算はcalendarの所定分、期間重複拒否、締め済み月拒否、
    残数両モード、取消=承認付き、付与/照会）、`LeaveApprovalAdapter`（leave.request/leave.cancel、状態CAS・
    残数CAS・月次leave_minutes反映・営業通知）、menu `myLeave`/`leaveManagement`＋`leave.*`権限seed、
    本人/管理画面と4言語i18n。営業はSecurityConfig＋menuで休暇scopeなし（通知のみ、design §5.3）。
  - **検証（T071完了）**: 休暇系4 class **20/0/0/0 skip 0**（分計算・両モード・重複・代理承認・通知分岐・
    取消戻し・付与・営業403）、全指定回帰 **191/0/0/0 skip 0**、MySQL smoke 4/0/0/0（V83/V91/V98）、
    `git diff --check` PASS。

- [x] B1. freee/provider sync
  - **Objective**: 承認/締め済みの雇用勤怠をfreeeへ冪等送信またはCSV出力でき、
    外部データはread-onlyの照合に使われる。締め済み月が外部から上書きされない。
  - **実装ガイダンス**: 本システムを正とし、外部dataはread-only照合（G6決定）。
    cursor/冪等/error UI。OAuth/refreshはaccounting adapterと共通基盤。
    **外部が締め済み・承認済みを上書きしようとしたら拒否してfindingへ**（design §5.4）。
    黙って上書きも、黙って無視もしない。timezoneはtenant設定（design §3）。
  - **テスト要件**: L2〜L3。401 refresh 1回/429 backoff/timeout、重複送信で外部1件、
    部分失敗、**締め済み月への外部更新が拒否されfindingになること**、秘密の非ログ出力。
  - **Demo**: sandbox syncと再実行。締め済み月に外部更新を流して拒否されることを確認。
  - **実装内容（T072完了）**: `AttendanceProvider` interface（design §3）＋`MockAttendanceProvider`
    （`attendance.sync.provider=mock`既定、冪等キーで外部1件）＋`FreeeAttendanceProvider`
    （`FreeeIntegrationService`のapiGet/apiPost共通基盤へ委譲。401 refresh 1回・429 backoff・
    timeout 503・4xx retryなし・冪等キー/相関IDヘッダー・秘密非ログを実装）。
    `AttendanceSyncService`（承認/締め済み月の冪等push・外部updated_at cursor差分pull・
    締め済み/承認済み月への外部更新は**拒否してfinding**＝`t_overtime_followup`へ
    warning_code='EXT_OVERWRITE_REJECTED'でUPSERT＋HR/管理者通知（dedupe key）・
    cursor/直近結果は`m_system_config` JSON（SYSTEM_MANAGED）・timezoneは
    `attendance.sync.timezone` tenant設定）。API run/status/export-csv（runは管理者/HR、
    status/CSVは管理者/HR/マネージャー、scopeはdesign §5.3）。管理画面に同期カード＋JS、
    4言語i18n。
  - **検証（T072完了）**: 新規5 class **29/0/0/0 skip 0**（provider matrix 7・sync 8・API 7・
    provider 3・mock 3＋JS 1）、指定回帰 **165/0/0/0 skip 0**、`git diff --check` PASS。
    唯一の既知FAILはNOTE-R4-03（`project.detail.desc`、scale-300起因・統合担当OPEN）。

- [x] B2. 客先工数差異/通知
  - **Objective**: 雇用勤怠合計と契約工数の差が月次で表示され、閾値超過を理由付きで確認できる。
    差異を確認しても請求金額は変わらない。
  - **実装ガイダンス**: 月次比較、理由確認、warning/escalation。
    **read-only DTOとし、`WorkRecordServiceImpl`の金額計算・請求ロジックへ接続しない**（design §5.4、R4.2）。
  - **テスト要件**: L2〜L3。**差異確認の前後で請求金額が不変であること**、
    scope、通知の冪等、閾値の境界。
  - **Demo**: 8h差異を確認して理由保存。理由保存の前後で請求金額が同じことをSQLで提示。
  - **実装内容（T073完了）**: `AttendanceDiscrepancyService`（read-only比較DTO: 雇用勤怠`worked_minutes`と
    契約工数`actual_hours×60`を月次比較、閾値`attendance.discrepancy.threshold-minutes`（既定480分）超過判定、
    理由確認は**m_system_config JSON**（`attendance.discrepancy.confirmed`、closing.confirmed-months前例。
    新規テーブルは予約外migration禁止・work record系テーブルへの書込みは「請求ロジック接続」と見なすため、
    逸脱と根拠をledgerへ記録）、scopeはdesign §5.3（管理者=全件/HR=法人/マネージャー=組織、営業403は既存SecurityConfig）、
    `pendingWarnings`（scheduler principal=全件・閾値超過かつ未確認のみ））。API list/confirm
    （`/api/work-records/attendance/discrepancy/**`）。管理画面に差異カード＋JS。日次バッチ
    `NotificationGenerateService.attendanceDiscrepancyWarning()`（管理者/HRへdedupe key冪等通知）。
  - **検証（T073完了）**: 新規2 class **13/0/0/0 skip 0**（Service 8・API 5）、指定回帰 **232/0/0/0 skip 0**、
    `git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（`project.detail.desc`、scale-300起因・統合担当OPEN）。
    Demo: 8h差異（1200分）を確認して理由保存、保存前後のbilling_amount・actual_hoursがSQLで同一を実測。

- [ ] M. 回帰/法務受入
  - **Objective**: 6か月rollingのfixtureが期待どおりで、月次が一気通貫で動く。
    既存の給与・work record・請求機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    給与・work record回帰、境界fixture全件、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 6か月rolling fixtureと月次全通し。客先工数を編集しても雇用勤怠が変わらないことを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    法人別36協定/就業規則の確認と外部社労士Reviewは本taskのPASS条件ではなく、**本番releaseのgate**として別管理する。
