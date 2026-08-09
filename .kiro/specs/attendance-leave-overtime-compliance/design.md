# Design — 雇用勤怠・休暇・時間外労働

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（S11正式migration V83。V82適用後にmerge/apply）

- `m_work_calendar(id, legal_entity_id, name, valid_from/to)`、`m_work_calendar_day(calendar_id,date,type,scheduled_minutes)`。
- `t_employee_attendance(id, engineer_id, work_date, clock_in/out, break_minutes, work_type,
  workplace_type, source, source_external_id, status, version)`。
- `t_attendance_month(engineer_id, work_month, totals..., overtime_minutes, status, submitted/approved/closed metadata)`。
- `t_leave_request(engineer_id, leave_type, start/end/date/time, requested_minutes, reason, status,
  approval_request_id)`。
- 本システム残数を正にする場合だけ`t_leave_ledger`。外部正ならmapping/cacheのみ。
- `m_overtime_agreement(legal_entity_id, valid_from/to, special_clause, limits/config)`。
- `t_overtime_followup(engineer_id, period, warning_code, status, notified_at, health_action_status)`。

時間は分の整数で保存し、表示時に時間へ変換。浮動小数を使わない。

## 2. Calculator

- `AttendanceCalculator`: 日次→月次、深夜/休日区分。
- `OvertimeComplianceCalculator`: rolling 2〜6か月、年、45h超月数。休日労働を含む/含まない定義を
  ruleごとに明示する。
- official boundary fixtureをtest resource JSONへ保存。

## 3. Integration

- `AttendanceProvider` interface: Internal/Freee。OAuth/refreshはaccounting adapterと共通基盤。
- syncは外部updated_at cursor+external IDで冪等。timezoneはAsia/Tokyo固定ではなくtenant設定。
- work record comparisonはread-only DTO、金額serviceへ接続しない。

## 4. UI

- `/my/attendance`, `/attendance`, `/leave`。
- calendar、月次集計、warning、差異、提出/承認/締め。
- managerはorganization scope、HRは法人scope、本人は自己のみ。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 勤務カレンダー | 有効期間内の`m_work_calendar` | `valid_from/to` | — | **勤務日**時点の有効カレンダー | 法人既定カレンダー |
| 所定時間 | `m_work_calendar_day.scheduled_minutes` | カレンダー版で切替 | 月次確定時に固定 | 勤務日時点 | **所定なし（休日）**。0分と区別する |
| 36協定 | 有効期間内の`m_overtime_agreement` | `valid_from/to` | 判定結果を`t_overtime_followup`へ | **対象月**時点の有効協定 | **協定未締結**。特別条項判定不可としてfindingにする |
| 月次勤怠 | `t_attendance_month.status` | — | 締め済で固定 | 対象月 | 未提出 |
| 休暇残数 | 本システム正なら`t_leave_ledger` | 付与/消化履歴 | — | 申請日時点 | 外部正の場合は**参照のみ**。残数不明で拒否しない |
| 客先工数 | `t_work_record_daily` | — | — | 対象月 | — |

- `scheduled_minutes IS NULL`（所定日でない）と`= 0`（所定日だが0分）を区別する。§1.1に該当。
- **締め済み雇用勤怠は上書き不可**（R1.4）。freee/importのsyncが締め済み月を更新しようとしたら
  拒否してfindingにする。sourceの優先順位で黙って上書きしない。

### 5.2 時間外計算の境界定義

**唯一の正は `overtime-rules.md`。** 値・境界の向き・休日労働の算入可否・優先順位・変更手順は
すべて同書にあり、ここでは再掲しない。実装・Review・値変更のいずれも同書を参照する。

決定は**確定済み**であり、「G6/社労士待ち」ではない。社労士確認と法人別36協定の突合は
**本番releaseのgate**であって、実装やM taskのPASSを止めない。確認結果がずれていた場合は
`overtime-rules.md` §4 の手順で変更する（多くの場合`/system-config`の値変更だけで済む）。

実装側が守る構造上の制約は3点だけ:

1. **判定式は`OvertimeComplianceCalculator`に1メソッド1ルールで書く。**
   条件を複数箇所へ散らすと`overtime-rules.md` §4.3の変更が漏れる。
2. **閾値をコードへ直書きしない。** 解決順は
   `m_overtime_agreement`（法人別）→ `m_system_config`（`overtime.*`）→ コード定数（最終fallback）。
   協定行が無い法人は**判定不能としてfinding**を出す（既定値で「適合」にしない）。
3. **「休日労働を含むか」の分岐は、calculatorへ渡す入力の選択（`overtimeMinutes` か `totalMinutes` か）
   1箇所に閉じる。** ルール内へ条件を持ち込まない。

境界で最も事故るのは**ルール4（月100時間）だけが `>=` 判定**である点。
「100時間**未満**」なので100時間ちょうどが違反、他のルールは「以内」なので上限ちょうどは適合。
`limit-1 / limit / limit+1` の3点fixtureで必ず固定する。

### 5.3 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 全warning | 月次集計、warning batch |
| HR | **法人scope**（全要員の勤怠・休暇・warning） | 同左 | 担当法人のwarning | — |
| マネージャー | 組織scope ∩ DataScope。`manager_user_id`直属は個人単位で追加 | 同左 | 配下のwarning | — |
| 営業 | **勤怠は不可視。** 客先報告が必要な休暇のtask/通知のみ受ける（R2.3） | — | 担当要員の休暇通知 | — |
| 要員（本人） | **自分のみ** | 自分のみ | 自分のwarning | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は本人→上長→HRの段階通知（R3.3） | 日次集計、warning判定 |

- **逸脱: 営業には勤怠scopeを与えない。** §2.1の既定（営業は既存DataScopeのまま）に対する
  絞り込み側の逸脱である。根拠: 雇用勤怠は労務情報であり、営業の担当要員であることは
  閲覧根拠にならない（R4相当）。営業が受け取るのは客先報告が必要な休暇の通知のみ。
- 医師面談・健康対応は**実施要否/連絡日/完了日だけ**を保持し、診療内容を保存しない（R3.4）。
  この3列もHR/管理者限定とする。
- 段階通知の宛先は**個人指定**。組織一斉通知にしない（§2.4）。

### 5.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| month 入力中 | →提出済 | 状態CAS | 二重提出 | 入力中へ |
| 提出済 | →承認済 / →差戻し | 状態CAS＋`version` | 上長とHRの同時操作 | 提出済へ |
| 差戻し | →提出済 | 状態CAS | — | — |
| 承認済 | →締め済 / →差戻し（承認取消） | 状態CAS | 締めとsync の競合 | 承認済へ |
| 締め済 | →再open（**管理承認必須**、R1.4） | 状態CAS | sync が締め済を更新しようとする→**拒否** | 承認済へ |
| leave 申請中 | →承認済 / →却下 | approval spec の engine を利用 | 二重承認 | 申請中へ |
| 承認済 | →取消（承認付き） | 状態CAS | 残数消化との競合 | 残数を戻す |

- **freee/import syncの冪等**: `(source, source_external_id)`にUNIQUE。
  外部`updated_at` cursorで差分取得し、同じ外部レコードを二重登録しない。
- **sourceの優先順位**: 本システム(manual/system) > freee/import。
  外部が締め済み・承認済みを上書きしようとしたら**拒否してfindingへ**（R1.3）。
  黙って上書きも、黙って無視もしない。
- **客先工数との非連動**（R4.2）: 差異表示はread-only DTO。
  `WorkRecordServiceImpl`の金額計算・請求ロジックへ一切接続しない。
  差異を確認・理由保存しても請求金額は変わらないことをtestで固定する。
- 休暇残数が外部正の場合、残数不足でも**申請を拒否しない**（参照表示のみ、R2.2）。
  本システム正の場合のみ残数CASで不足を拒否する。

## 6. テスト

DST影響なしのJST日界、跨夜、休憩、休日、45/360/720/80/100境界、source of truth、sync retry、
leave残、closing、work record差異非連動。

