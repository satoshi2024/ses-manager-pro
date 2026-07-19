# Design — 作業報告書と要員セルフサービス勤怠（engineer-self-service-timesheet）

対象コード: 新 `WorkRecordDaily`/`EngineerAccountLink` エンティティ一式 / 新 `MyTimesheetApiController`・
`MyTimesheetPageController` / `WorkRecordService(Impl)`（拡張） / 新 `TimesheetPdfService` /
`EngineerServiceImpl`（削除時のリンク解除） / `CustomUserDetailsService` / `SecurityConfig` /
`work-record.js`・`templates/work-record/list.html`（承認UI） / 新 `templates/my-timesheet/index.html`・
`static/js/modules/my-timesheet.js` / `engineer/detail.html`（アカウント紐付けカード）

## 1. マイグレーション（`V32__engineer_self_service.sql`※）

※ 番号は実装時点の最新+1。全体調整は `customer-feature-proposals/README.md` 参照。

```sql
ALTER TABLE sys_user MODIFY role ENUM('管理者','営業','HR','マネージャー','要員') NOT NULL COMMENT 'ロール';
ALTER TABLE t_work_record MODIFY status ENUM('入力中','提出済','差戻し','確定') DEFAULT '入力中';

CREATE TABLE t_engineer_account_link (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id  BIGINT NOT NULL UNIQUE,
  sys_user_id  BIGINT NOT NULL UNIQUE,
  linked_by    BIGINT, linked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  FOREIGN KEY (sys_user_id) REFERENCES sys_user(id)
) COMMENT='要員アカウント紐付け';

CREATE TABLE t_work_record_daily (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL,
  work_date      DATE NOT NULL,
  start_time     TIME NULL, end_time TIME NULL, break_minutes INT NOT NULL DEFAULT 0,
  worked_hours   DECIMAL(4,2) NOT NULL COMMENT '稼働時間(自動計算値を保存)',
  remarks        VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wr_daily (work_record_id, work_date),
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE CASCADE
) COMMENT='日次勤怠';

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('my-timesheet', 'マイ勤怠', '/my', '/api/my', <既存最大+1>);
INSERT IGNORE INTO t_role_menu (role, menu_id)
  SELECT '要員', m.id FROM m_menu m WHERE m.menu_key='my-timesheet';
```

- **既存 t_work_record の status ENUM 拡張**は値追加のみ（既存値の並びは変えない）。
- H2: engineer-schema-h2 に2テーブル追加＋status/role は VARCHAR のため変更不要。
  リプレイ用 `sql/schema-self-service-h2.sql`（IF NOT EXISTS）を application-test.yml へ。
- 状態機械の要点: 「差戻し」は入力可否の判定で「入力中」と同扱い（`saveHours`/日次入力の
  許可条件は `status IN (入力中, 差戻し)`）。`confirmMonth` は `IN (入力中, 提出済)` を
  確定対象とし、**差戻しは対象外**（差戻し＝「数値が誤り」という明示フラグであり、
  一括締めで黙って確定させない。requirements R3-5 と同一。取り残しは月次締めチェックリストの
  「未確定実績」が検出する）。既存の請求済み/BP ガード類は status を見ないため無変更。

## 2. セキュリティ

- `CustomUserDetailsService`: 変更不要（`ROLE_<role>` 生成は汎用）。
- `SecurityConfig`: `/my/**`・`/api/my/**` を `hasRole("要員")` で静的制限（管理者含め他ロールは
  入れない——「本人の画面」のため。管理側は勤怠グリッド/PDFで同じ情報に到達できる）。
  加えて既存の全認可ルールが「要員」ロールにメニューフィルタで閉じることを確認
  （`MenuPermissionFilter` は m_menu 登録済みメニューのみ許可 → 要員は my-timesheet のみ）。
- `LoginSuccessHandler`: ログイン後の遷移先を要員ロールのみ `/my/timesheet` へ分岐する
  （既定の遷移先はダッシュボード系のため、要員ではログイン直後に 403 になってしまう）。
- **本人スコープ**: `MyTimesheetApiController` は冒頭で
  `engineerAccountLinkMapper.selectByUserId(currentUserId)` から engineerId を解決し、
  以降のすべての読み書きをその engineerId 配下に限定（パスに engineerId を受けない設計にする
  ことで越権の余地自体を消す）。未紐付けユーザーは 403（`error.my.notLinked`）。

## 3. サービス層

### 3.1 `WorkRecordService` 拡張

```java
// 日次: 保存(upsert)・削除。いずれも合計再計算→既存精算ロジック呼び出しまで一体で行う
WorkRecord saveDaily(Long contractId, String workMonth, WorkRecordDaily daily);
void deleteDaily(Long contractId, String workMonth, LocalDate workDate);
List<WorkRecordDaily> listDaily(Long workRecordId);
// 提出・承認
void submit(Long workRecordId);                        // 入力中/差戻し→提出済 + 承認者へ通知
void approve(Long workRecordId);                       // 提出済→確定（既存確定と同義の後続処理）
void reject(Long workRecordId, String comment);        // 提出済→差戻し + 要員へ通知(コメント付き)
```

- `saveDaily` の合計再計算: `SUM(worked_hours)` → **既存 `saveHours` を内部呼び出し**して
  `actual_hours`/`billing_amount`/`payment_amount` を更新（精算・請求済みガード・
  期間ガードをそのまま享受する）。`saveHours` 側に「日次由来の呼び出しか」を渡す
  内部フラグを追加し、R2-5 の「日次行がある月の手動合計入力拒否」を同メソッドで判定する
  （判定式: `!fromDaily && dailyCount > 0` → `error.workRecord.dailyManaged`）。
- `approve` は既存 confirm 単体版: status→確定 のうえ、`confirmMonth` の BP 生成・金額同期
  （`syncRootBpAmount`）**と同じ処理を単契約分**行う。共通化のため confirmMonth の
  BP 生成部を `generateOrSyncBpFor(WorkRecord)` として抽出し両者から呼ぶ（二重実装禁止）。
- 状態遷移はサーバ側 `Map<String,Set<String>>`（契約・提案と同型）。
- 通知: `TIMESHEET_SUBMITTED`（承認者向け・リンク=work-record グリッド）/
  `TIMESHEET_REJECTED`（要員向け・リンク=`/my/timesheet`）。リンクは `NotificationLinks` へ
  定数追加（ルート整合テストに自動で乗る）。

### 3.2 アカウント紐付け

`EngineerAccountLinkService`: link(engineerId, sysUserId)（対象ユーザーが role=要員・未紐付けで
あること検証）/ unlink / findEngineerIdByUserId。
`EngineerServiceImpl.removeById`: 削除成功後に unlink＋当該ユーザー無効化（既存の
`releaseAllByEngineerId` と同じ位置に追加。lifecycle G3 の「成功後にのみ」規約に従う）。
`UserApiController.delete`: 紐付け中の要員アカウントの削除は拒否する（`error.engineerAccount.linkedUserDelete`
——先に要員詳細から紐付けを解除させる。既存の担当営業残存ガードと同列のガードとして追加）。

## 4. API / ページ

- `MyTimesheetApiController`（`/api/my/timesheet`）: `GET ?month=`（自分の契約×日次×状態）/
  `POST /daily` / `DELETE /daily` / `POST /{workRecordId}/submit` / `GET /{workRecordId}/report.pdf`
- `WorkRecordApiController` 追記: `POST /api/work-records/{id}/approve` / `POST /{id}/reject` /
  `GET /{id}/report.pdf`（管理側出力）/ `GET /{id}/daily`（承認時の日次参照）
- `MyTimesheetPageController`: `GET /my/timesheet` → `my-timesheet/index`

## 5. フロントエンド

- **要員ポータル** `my-timesheet.js`: 月セレクタ＋契約タブ＋日次カレンダー式テーブル
  （行=日付、開始/終了/休憩/備考のインライン編集、保存で合計・精算額が更新表示）。
  「提出」ボタン（確認ダイアログ＋未入力日数の警告）。差戻しコメントの表示帯。
- **承認側** `work-record.js` 拡張: グリッドに状態バッジ（提出済=青・差戻し=黄）、
  提出済行に「承認」「差戻し（コメント入力）」ボタン、日次明細の展開表示、PDF ボタン。
  既存の月次一括確定・解除ボタンは現行のまま。
- **要員詳細** `engineer/detail.html`: 「ログインアカウント」カード（紐付け/解除。
  freee 連携カードと同型。対象ユーザーのセレクトは role=要員・未紐付けのみ）。

## 6. PDF（新 `TimesheetPdfService`）

作業報告書: ヘッダ（対象月・要員名・案件名・客先名）/ 日次明細表 / 合計 / 承認欄（客先確認印・
自社確認印の空枠）。日次なし月は合計のみの簡易様式。`PdfProperties` のフォント流用。

## 7. i18n

`menu.myTimesheet` / `my.timesheet.*`（ポータル一式・15キー前後）/
`workRecord.status.submitted/rejected` / `workRecord.approve/reject.*` /
`error.my.notLinked` / `error.workRecord.dailyManaged` / `error.workRecord.dailyInvalidTime` /
`error.engineerAccount.*`（紐付け検証・削除ガード4本）/ 通知 `notification.msg.TIMESHEET_SUBMITTED/REJECTED` — 4言語。

## 8. レーン分割

- **レーンF（基盤）**: V32・エンティティ2種・Mapper・`EngineerAccountLinkService`・
  SecurityConfig・状態機械定数・i18n 全キー先行投入・H2 同期（共有資産の凍結。
  engineer-sales-commission の F 方式を踏襲）。
- **レーンA（要員ポータル）**: MyTimesheet API/ページ/JS・saveDaily/submit（F 後）。
- **レーンB（承認側）**: approve/reject・BP 生成共通化・グリッド拡張・通知（F 後、A と並行可。
  `WorkRecordServiceImpl` を A と分割編集するため、メソッド単位で担当を分け衝突時は A 優先）。
- **レーンC（PDF＋紐付けUI）**: TimesheetPdfService・report エンドポイント・要員詳細カード
  （F 後、A/B と並行可）。
- **M（統合）**: confirmMonth の対象拡張の回帰・全通し。
- 他 spec との競合: `WorkRecordServiceImpl` は P6（単価履歴）と交差 → **P1 完了後に P6**。

## 9. テスト方針

| 対象 | ケース |
|---|---|
| saveDaily | 合計再計算→saveHours 連動（精算額更新）/ 時刻不正（負・24h超）拒否 / 同日 upsert / 提出済・確定月は拒否 / 差戻し月は可 |
| 手動合計との排他 | 日次あり月の手動 saveHours 拒否（dailyManaged）/ 日次なし月は従来どおり可（後方互換） |
| submit/approve/reject | 遷移許可・不許可 / approve で BP 生成が confirmMonth と同結果（共通化の回帰）/ reject 通知に コメント・リンク |
| confirmMonth | 入力中・提出済 が一括確定される / **差戻しは確定されず残る** / 既存テスト（入力中のみの月）グリーン維持 |
| 本人スコープ | 他要員の workRecordId 指定で 403 / 未紐付けユーザー 403 |
| 紐付け | role≠要員 拒否 / 二重紐付け拒否 / 要員削除で unlink＋ユーザー無効化（削除失敗時は何もしない） |
| ルート | `NotificationLinks` 追加定数が NotificationLinkRouteTest で自動検証される |
