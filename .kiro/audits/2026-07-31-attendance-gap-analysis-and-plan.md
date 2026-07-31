# 2026-07-31 要員勤怠の現状棚卸しと実装計画

- 起点: 「要員の勤怠機能が何も無い。最低限の要員提出ロジックすら無い」という指摘
- 目的: 指摘の当否を実装で確認し、**実際に欠落している範囲**を確定して実装順序・採番・受入条件を決める
- 成果物: 本書のみ（production code は変更しない）
- 対象コミット: `ce1ccd4`（`db/migration` の merged 最新は **V70**）

---

## 1. 結論

指摘は**半分正しく、半分は事実と異なる**。

| 論点 | 判定 | 根拠 |
|---|---|---|
| 要員が勤怠を提出するロジックが無い | **誤り** | `engineer-self-service-timesheet` は 10/10 task 完了。日次入力・月次提出・差戻し・承認・PDF まで実装済み |
| 労務（雇用）としての勤怠機能が無い | **正しい** | `attendance-leave-overtime-compliance` は 8 task すべて未着手。打刻・勤務カレンダー・休暇・36協定が一切存在しない |
| 未提出のリマインドが無い | **正しい** | `NotificationGenerateService.generateAll()` の8ジェネレータに勤怠系が無い |

したがって着手すべきは「勤怠を作る」ではなく、**(a) 既存の請求工数勤怠の運用上の穴を塞ぐ** と
**(b) 請求工数とは別 source である雇用勤怠を新設する** の2本である。

---

## 2. 現状棚卸し — 契約工数勤怠（実装済み）

`engineer-self-service-timesheet` spec の成果物。**これは客先請求のための工数**であり、雇用勤怠ではない。

| 層 | 実体 |
|---|---|
| テーブル | `t_work_record`（月次）、`t_work_record_daily`（日次: `start_time`/`end_time`/`break_minutes`/`worked_hours`、`UNIQUE(work_record_id, work_date)`）。V5 / V32 / V39 |
| 本人画面 | `/my/timesheet` — `MyTimesheetPageController`、`MyTimesheetApiController`、`templates/my-timesheet/index.html`、`static/js/modules/my-timesheet.js` |
| 本人操作 | 日次保存・削除、月単位提出（`POST /submit-by-month`、実績0hでも内部保存経路を通す）、差戻しコメント表示、作業報告書PDF |
| 状態機械 | 入力中 →提出済 →確定 / 差戻し →提出済。`WorkRecordServiceImpl.ALLOWED_STATUS` が唯一の権威。遷移はすべて条件付きUPDATE（CAS）で、競合時は409 |
| 承認側 | `/work-record`、`POST /{id}/approve`、`POST /{id}/reject`（コメント trim 後必須・500字）、月次一括確定 `confirm` / 解除 `reopen`（`@PreAuthorize("hasRole('管理者')")`） |
| 越権防止 | `MyTimesheetApiController` は先頭で本人の `engineerId` を解決し、パスに engineerId を受けない。契約所有チェックで 403（他人の契約）と 404（不存在）を分離 |
| 下流連動 | `SettlementCalculator` による精算、BP支払の生成・同期、請求済チェック、月次締めロック（`assertOpenForUpdate`）、確定時の組織・原価部門の凍結、通知 |
| 権限 | V32 で `m_menu('my-timesheet','/my','/api/my')` を seed し `t_role_menu` に `要員` を付与済み |

### 2.1 「何も無い」ように見える直接の原因

`MyTimesheetApiController.currentEngineerId()` は先頭で
`linkService.findEngineerIdByUserId(...)` を引き、**未紐付けなら即 403 `error.my.notLinked`** を返す。

要員アカウントの紐付けは `EngineerAccountLinkApiController`（`/api/engineers/{id}/account-link`、
`@PreAuthorize("hasAnyRole('管理者','HR')")`、要員詳細画面のカード）でしか作れない。
**この紐付けを行っていない場合、機能は完成しているのに要員側では常に空**になる。

### 2.2 その紐付けカードが発見できなかった原因（2026-07-31 修正済み）

実機確認の結果、管理者で要員詳細を開いてもカードに辿り着けなかった。原因は権限でも
`sec:authorize` でもなく、**レイアウトの欠陥**だった。

`templates/engineer/detail.html` の左列（`col-md-4`）には4枚のカードがある。

1. プロフィール ← **`h-100` が付いていた**
2. 担当営業
3. フォロー履歴
4. ログインアカウント（`sec:authorize="hasAnyRole('管理者','HR')"`）

Bootstrap の `.row` は既定で `align-items: stretch` のため、`col-md-4` の高さは右列
（`col-md-8` の `h-100` カード）に合わせて引き伸ばされる。そこへ先頭カードが `h-100` を
持つと、**1枚目だけで列の全高を占有**し、2〜4枚目がファーストビューの遥か下へ押し出される。
権限のある管理者にも「カードが存在しない」ようにしか見えず、要員の紐付けという
**セルフサービス勤怠の必須初期設定が、事実上到達不能**になっていた。

対処: 先頭カードから `h-100` を除去し、意図をコメントで残した。カードが1枚だけの右列は
`h-100` のままでよい（列の高さを埋めるという本来の用途）。

教訓として、**カードが複数積まれる列の先頭要素に `h-100` を付けない**。
`MessageBundleConsistencyTest` / `StaticAssetLocalityTest` / `MobileResponsiveLayoutTest`
（計27件）で回帰なしを確認済み。

---

## 3. 欠落の実体 — 雇用勤怠（未着手）

`.kiro/specs/attendance-leave-overtime-compliance/` に requirements / design / **overtime-rules** / tasks が
揃っているが、tasks は **0/8**。DDL もコードも存在しない。

spec の最重要境界を再掲する:

> `t_work_record_daily` は顧客請求/契約工数であり、**雇用上の勤怠の唯一の正とはしない**。
> 客先工数と出退勤は一致しないため、新しい雇用勤怠モデルへ分離し、差異だけを比較する。

未実装の範囲:

- 出退勤（雇用側の実労働時間）、勤務区分、勤務地、深夜/休日区分
- 勤務カレンダー（法人/組織/個人別の所定日・所定時間、**法定休日と所定休日の区別**）
- 休暇（有給/半休/時間休/代休/欠勤/特別休暇）、残数の参照または台帳
- 36協定の上限判定（月45h / 年360h / 特別条項年720h / 月100h / 2〜6か月平均80h / 45h超年6回）
- 予兆・超過の段階通知（本人 → 上長 → HR）、医師面談フォロー（実施要否・連絡日・完了日のみ）
- freee 連携（本システムが正、外部は read-only 照合）
- 客先工数との月次差異比較

**値・境界の設計判断は不要**。`overtime-rules.md` が確定値として全ルール・境界の向き・
休日労働の算入可否・config key・変更手順を持っている。実装はそれを読むだけでよい。

---

## 4. 実施計画

### Phase 0 — 既存の請求工数勤怠を実運用可能にする（小、独立、先行推奨）

| # | 内容 | 変更候補 |
|---|---|---|
| 0-1 | **実施済み（2026-07-31）**。要員詳細の「ログインアカウント」カードが事実上発見できない原因を特定して修正した。§2.2 を参照 | `templates/engineer/detail.html` |
| 0-1b | 未紐付けの発見性。要員一覧／ユーザー一覧に「未紐付け」の表示・絞り込みが無く、管理者は要員詳細を1件ずつ開く以外に気づく手段が無い。`/user/list` で `要員` ユーザーを作っても紐付けが必要である旨の示唆が出ない | `templates/engineer/list.html`、`templates/user/list.html` ほか |
| 0-1c | 要員側の行き止まり。403 の文言だけが画面全体で、次の行動（管理者へ連絡）が示されない。新規要員が初日に必ず見る画面である | `templates/my-timesheet/index.html`、`static/js/modules/my-timesheet.js` |
| 0-1d | 候補ドロップダウンが無言で空になる。`candidates` は `role='要員'` かつ `status=1` かつ未紐付けのみを返すため、役割違いのアカウントは理由不明のまま現れない | `EngineerAccountLinkApiController`、`engineer-account-link.js` |
| 0-2 | 未提出リマインドを追加する。**新しい scheduler を作らず** `NotificationGenerateService.generateAll()` にジェネレータを1つ足す（既存の8つと同じ dedupe_key 方式）。締め日前は本人（`NotificationLinks.MY_TIMESHEET`）、締め日超過は上長へ | `NotificationGenerateService`、`m_system_config`（締め日・警告日数） |
| 0-3 | `/my/timesheet` の情報不足を補う。現在 `index.html` は27行で対象月と契約一覧だけ。提出期限、月合計、承認状況を出す | `templates/my-timesheet/index.html`、`static/js/modules/my-timesheet.js`、messages 4バンドル |
| 0-4 | 承認側の一括承認 | `WorkRecordApiController`、`work-record.js` |

Phase 0 は雇用勤怠と**テーブルを共有しない**ため、Phase 1 以降と並行して進められる。

### Phase 1 — 承認 engine の最小実装（決定により前倒し）

`attendance` spec の A2（休暇承認）は `approval-workflow-internal-control` の engine を前提にするが、
同 spec は 0/7 で未着手である。**先に approval engine の最小実装を行う**方針を採る。

- 範囲は「申請 → 承認ルート解決 → 承認/差戻し → 状態CAS + 監査」に絞る。金額決裁や多段階委任は含めない
- 既存の `WorkRecordServiceImpl` の CAS + 遷移表パターンを踏襲し、**新しい競合制御方式を発明しない**
- 休暇（Phase 3）と将来の order/dispatch がこの engine に載る前提で、汎用の申請テーブルとして設計する

### Phase 2 — 雇用勤怠の骨格（spec の F1 + F2）

- DDL: `m_work_calendar` / `m_work_calendar_day` / `t_employee_attendance` / `t_attendance_month` /
  `m_overtime_agreement` / `t_overtime_followup`
- **分の整数モデル**（浮動小数を使わない）
- `m_work_calendar_day.scheduled_minutes` は **NULL（所定日でない）と 0（所定日だが0分）を区別**する
- `(source, source_external_id)` に UNIQUE（外部取込の冪等）
- `m_overtime_agreement.valid_from` は**月初のみ許可**する制約
- 同一 migration で `overtime.*` config を `INSERT IGNORE` で seed（V56 の書き方に合わせる）
- `OvertimeComplianceCalculator`: **1メソッド1ルール**、閾値をコードへ直書きせず
  `m_overtime_agreement` → `m_system_config` → 定数の順で解決。協定行の無い法人は「適合」ではなく**判定不能の finding**
- 「休日労働を含むか」の分岐は calculator への**入力の選択1箇所**に閉じる
- 境界 fixture を `src/test/resources/fixtures/overtime/` へ JSON。**ルール4（月100h）だけが `>=`**

### Phase 3 — 画面と休暇（A1 + A2）

- `/my/attendance`、`/attendance`、`/leave`
- scope: 本人=自分のみ / マネージャー=組織scope ∩ DataScope / HR=法人scope /
  **営業は勤怠不可視**（design §5.3 の明示的逸脱。営業が受けるのは客先報告が必要な休暇の通知のみ）
- 休暇は Phase 1 の approval engine に載せる
- 休暇残数が**外部正の場合は残数不足でも申請を拒否しない**（参照表示のみ）。本システム正の場合のみ残数CASで拒否

### Phase 4 — 連携と差異（B2 → B1）

- B2（客先工数差異）を先に行う。**read-only DTO とし、`WorkRecordServiceImpl` の金額計算・請求ロジックへ一切接続しない**。
  「差異を確認・理由保存しても請求金額が変わらない」ことをテストで固定する
- B1（freee sync）は本システムを正とし、外部は read-only 照合。
  **締め済み月への外部更新は拒否して finding にする**（黙って上書きも、黙って無視もしない）

### Phase 5 — M task（全量回帰）

`mvn clean test` 全量、fresh/legacy MySQL smoke、給与・work record・請求の回帰、
境界 fixture 全件、390px の表示確認、`git diff --check`。

---

## 5. Migration 採番の是正（着手前に必ず読む）

spec 群は V67〜V81 を事前予約しているが、**merged 最新は V70** で、V71（CRM）〜V74（dispatch）は
いずれも未着手である。CLAUDE.md の規約どおり、**採番は着手時点の merged `db/migration` を正とし、
予約番号をそのまま使わない**。

理由: 予約番号を飛ばして先に merge すると、後から下位番号を merge した環境が
`FlywayValidateException: Detected resolved migration not applied to database` で起動不能になる。
本計画の順序（approval を先行）では approval が V71 相当、attendance が V72 相当となる可能性が高い。

また、以下は spec 本文の**記述誤り**であり、採番の根拠に使ってはならない（ヘッダの予約番号が正）:

- `attendance-leave-overtime-compliance/tasks.md` — 本文が order を V72、dispatch を V73 と書くが、
  実際の予約は approval=V72 / order=V73 / dispatch=V74
- 同 F1 の本文が「同じ **V74** で `overtime.*` を seed する」と書くが、同 spec の予約は V75
- `dispatch-outsourcing-compliance-ledger/tasks.md` — 自身が V74 でありながら attendance も V74 と書く

（本計画では spec ファイルを変更していない。採番確定時に併せて是正すること。）

H2 側の同期も忘れない: `sql/schema-attendance-h2.sql` の追加、`application-test.yml` の
`spring.sql.init.schema-locations` への登録、必要なら `engineer-schema-h2.sql` の更新。
これを怠ると MyBatis-Plus の生成 SELECT が "Unknown column" で落ちる。

---

## 6. 決定事項

| # | 論点 | 決定 |
|---|---|---|
| D1 | 指摘「提出ロジックが無い」への対応 | 既存実装が存在するため作り直さない。運用（account-link）と情報不足（Phase 0）として扱う |
| D2 | 雇用勤怠と請求工数の関係 | **別テーブル・別 source**。`t_work_record*` は請求のまま不変とし、差異のみ read-only で比較する |
| D3 | 休暇の承認 engine | `approval-workflow-internal-control` の**最小実装を先行**させ、休暇はその上に載せる（attendance 内に独自承認を作らない） |
| D4 | 時間外の閾値 | `overtime-rules.md` を唯一の正とし、実装時に設計判断をしない。コードへ直書きしない |
| D5 | 営業の勤怠可視性 | 与えない。担当要員であることは労務情報の閲覧根拠にならない |
| D6 | Migration 採番 | 予約番号ではなく着手時の merged 最新 + 1 を使う |

---

## 7. 受入条件

**Phase 0**

1. 要員ロールのユーザーで `/my/timesheet` に到達でき、日次入力から提出までが通る
2. 未提出の要員へ締め日前に本人通知が飛び、同日に二重発行されない（dedupe_key）
3. 4バンドル（ja/en/zh_CN/ko）にキーが揃い、`MessageBundleConsistencyTest` が緑

**Phase 2**

4. `overtime-rules.md` §5 の推奨 fixture が全件一致。特に**月100hちょうどが違反、他の上限はちょうどで適合**
5. 協定行の無い法人が「適合」ではなく判定不能の finding になる
6. `scheduled_minutes` の NULL と 0 が区別される
7. 月初でない `valid_from` の協定登録が拒否される

**Phase 4**

8. 客先工数を編集しても雇用勤怠が変わらない
9. 差異の確認・理由保存の前後で請求金額が不変であることを SQL で提示できる
10. 締め済み月への外部 sync が拒否され finding になる

---

## 8. リスク

| リスク | 影響 | 対応 |
|---|---|---|
| 予約番号どおりに採番して先に merge する | 全環境が起動不能（`FlywayValidateException`） | §5。着手時に merged 最新を再確認 |
| 雇用勤怠を `t_work_record` に相乗りさせる | 請求金額の意図しない変動 | D2。テーブルを分け、差異は read-only DTO |
| 閾値をコードへ直書きする | 社労士確認後の値変更でコード改修が必要になり、変更漏れが出る | D4。3段階解決 + 1メソッド1ルール |
| approval engine を待たず attendance 内に独自承認を作る | 後日の二重実装と移行コスト | D3。engine を先行 |
| 営業に勤怠 scope を与えてしまう | 労務情報の不適切な閲覧 | D5。営業の勤怠APIアクセス拒否をテストで固定 |
