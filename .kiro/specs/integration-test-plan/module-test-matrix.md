# SES Manager Pro モジュール内結合テスト（ITa）詳細マトリクス

本ドキュメントは、全 15 モジュールそれぞれの画面UI操作・API入力・内部ロジック・DBテーブル反映先マトリクスです。

---

## 1. MOD-01: 認証・アカウント・権限・MFA・監査ログ
- **主要画面**: `/login`, `/user/list`, `/mfa`, `/audit-log/list`, `/security-session/list`
- **主要DB**: `sys_user`, `t_role_menu`, `m_menu`, `t_audit_log`, `t_security_session`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD01-01 | `/login` | 未認証 | 正しいユーザー名 (`admin`) とパスワード入力 | `sys_user.last_login_at` = NOW()<br>`sys_user.failed_count` = 0 | 認証成功、`/` (ダッシュボード) へ遷移 |
| MOD01-02 | `/login` | 未認証 | 誤ったパスワードを 5 回連続入力 | `sys_user.failed_count` = 5<br>`sys_user.locked_until` = NOW()+30m | ログイン拒否、画面に「アカウントが一時ロックされました」アラート |
| MOD01-03 | `/user/list` | 管理者 | 新規ユーザーModalで `username='sales_new'`、ロール `営業` を登録 | `sys_user` に INSERT (password_hash, role='営業', status='ACTIVE') | Toast「登録しました」、一覧テーブルに即時追加 |
| MOD01-04 | `/user/list` | 管理者 | ログイン中の自分自身 (`admin`) に対し「無効化」ボタン押下 | DB 変更なし (ガードブロック) | Swalエラー「ログイン中の自身を無効化することはできません」表示 |
| MOD01-05 | `/user/list` | 管理者 | 権限設定タブで `営業` ロールから `invoice` メニューのチェックを外し保存 | `t_role_menu` から該当 `menu_id` 削除 | 保存完了。`営業` で再ログイン時、サイドバーから「請求書」メニュー消滅 |
| MOD01-06 | `/audit-log/list` | 管理者 | 監査ログ照会画面で操作種別 `UPDATE`、日付指定で検索 | `t_audit_log` から SELECT 実行 | 上記 MOD01-03/05 のユーザー作成・権限変更履歴がログ一覧に正確に表示 |

---

## 2. MOD-02: 採用・候補者管理 (Recruiting)
- **主要画面**: `/candidate/list`
- **主要DB**: `t_candidate`, `t_candidate_history`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD02-01 | `/candidate/list` | HR | 「候補者登録」Modal で氏名・メアド・得意スキル入力保存 | `t_candidate` に INSERT (name, email, skills, status='応募') | 候補者Kanban/一覧の「応募」列に新カード表示 |
| MOD02-02 | `/candidate/list` | HR | 候補者カードを「一次面接」→「オファー」→「内定承諾」へステータス変更 | `t_candidate.status` = '内定承諾'<br>`t_candidate_history` に履歴 INSERT | ステータスバッジが緑色「内定承諾」へ変化 |
| MOD02-03 | `/candidate/list` | HR | 「内定承諾」候補者の「エンジニアへ正式変換」ボタン押下 | `t_candidate.status` = '要員化済'<br>`t_engineer` に新規 INSERT | Toast「エンジニアへ変換しました」、エンジニア一覧 (`/engineer/list`) へ即時連携 |

---

## 3. MOD-03: エンジニア・職歴・担当営業マスタ
- **主要画面**: `/engineer/list`, `/engineer/detail/{id}`
- **主要DB**: `t_engineer`, `t_engineer_sales`, `t_engineer_career`, `t_engineer_skill`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD03-01 | `/engineer/list` | 営業/HR | 300人データ中、スキル `Java` かつ状態 `稼働中` で絞り込み検索 | `t_engineer` + `t_engineer_skill` SELECT | 255名中条件に合致する要員のみテーブル描画、件数カウント更新 |
| MOD03-02 | `/engineer/detail/{id}` | 営業 | 担当営業変更カードで新営業 `s300.sales02` を「主担当設定」 | `t_engineer_sales` (primary_flag=1, assigned_at=NOW())<br>旧営業: `primary_flag`=0, `released_at`=NOW() | 旧営業が副担当へ降格、担当営業履歴テーブルに全期間が正確表示 |
| MOD03-03 | `/engineer/detail/{id}` | HR | 職歴タブで過去の参加プロジェクト・役割・使用技術を入力追加 | `t_engineer_career` に INSERT (project_name, role, technologies) | 職歴タイムラインに新経歴カード追加、PDF プレビューに即時反映 |
| MOD03-04 (Edge) | `/engineer/detail/{id}` | 営業 | `scope.sales-own-data-only=true` 環境下で他営業担当のエンジニアIDをURL直打ち | `DataScopeService.assertAllowedEngineer()` でブロック | 画面に「404 NotFound」または「アクセス権限がありません」エラー表示、データ漏洩なし |
| MOD03-05 (Edge) | `/api/engineers/{id}/sales-reps` | 営業 | 2名の営業が同時に同一エンジニアの「主担当設定」APIを叩く (Concurrency) | `EngineerSalesServiceImpl` の `@Transactional` | 一方は成功し、もう一方は競合エラー(OptimisticLock/BusinessException)となり「既に更新されています」と表示 |

---

## 4. MOD-04: 顧客・CRM・商談・コンタクト
- **主要画面**: `/customer/list`, `/customer/detail/{id}`, `/crm/list`
- **主要DB**: `t_customer`, `t_customer_contact`, `t_lead`, `t_opportunity`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD04-01 | `/customer/list` | 営業 | 「顧客企業追加」Modal で企業名・インボイス登録番号 T1234567890123 を入力 | `t_customer` (company_name, invoice_number) | 顧客一覧に新カード追加、登録番号バッジ表示 |
| MOD04-02 | `/crm/list` | 営業 | リードから商談化ボタン押下、想定月額単価 85万円、確度 B を設定 | `t_lead.status` = '商談化'<br>`t_opportunity` に INSERT (amount=850000, probability='B') | 商談パイプラインに新カード登場、売上見込み合計額が自動再計算 |

---

## 5. MOD-05: SES案件・要件スキル・AIマッチング
- **主要画面**: `/project/list`, `/project/detail/{id}`
- **主要DB**: `t_project`, `t_project_skill`, `t_ai_match_score`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD05-01 | `/project/list` | 営業 | 「案件登録」で月額単価80〜90万円、精算幅140-180h、Java/Spring必須を設定 | `t_project` (price_min=800000, settlement_hours_min=140...)<br>`t_project_skill` | 案件一覧に公開カード追加、精算条件が正しく表示 |
| MOD05-02 | `/project/detail/{id}` | 営業 | 「AI適合度マッチング試算」ボタン押下 | `t_ai_match_score` に結果一時キャッシュ | 300人中適合率 80% 以上の要員 5 名がスコア順にポップアップ表示 |

---

## 6. MOD-06: 提案Kanban・メールテンプレート
- **主要画面**: `/proposal/kanban`, `/email-template/list`
- **主要DB**: `t_proposal`, `t_proposal_history`, `t_email_template`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD06-01 | `/proposal/kanban` | 営業 | 要員と案件を選択し新規提案作成、Kanban カードを「提案中」へ移動 | `t_proposal` (status='提案中')<br>`t_proposal_history` に更新履歴 | カードが「提案中」列に移動、ステータス変更日時が記録 |
| MOD06-02 | `/proposal/kanban` | 営業 | 提案カードの「メール送信用Modal」を開き、スキルシート添付・プレビュー表示 | DB 変更なし | 変数 `${engineerName}`, `${projectName}` が置換されたメール本文表示 |

---

## 7. MOD-07: 契約・単価改定・S10コンプライアンス・署名
- **主要画面**: `/contract/list`, `/compliance/dispatch-ledger`, `/contract-document/list`
- **主要DB**: `t_contract`, `t_contract_price_history`, `t_dispatch_compliance`, `t_contract_document`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD07-01 | `/contract/list` | 営業 | 成約済みの提案から自動作成された契約ドラフトを開き単価改定（+5万円）保存 | `t_contract.unit_price` = 850000<br>`t_contract_price_history` に改定履歴 | 単価改定履歴モーダルに過去単価と新単価が並んで表示 |
| MOD07-02 | `/compliance/dispatch-ledger` | 管理者 | **S10**: 派遣・請負コンプライアンスチェック実行、抵触日 3年ルール検証 | `t_dispatch_compliance` (limit_date) | 抵触日まで残り 30 日の要員に対し画面上に黄色警告バナー表示 |
| MOD07-03 | `/contract-document/list` | 営業 | CloudSign 電子署名依頼ボタン押下 | `t_contract_document` (status='SENT', external_doc_id) | 文書ステータス「署名待ち」に変化、CloudSign モック連携完了 Toast |
| MOD07-04 (Edge) | `/contract/list` | 営業 | **S10**: 抵触日を過ぎた要員に対して新規の派遣契約登録を試行 | `ComplianceMappingServiceImpl` によるバリデーション | 「抵触日(3年ルール)を超過しているため新規契約できません」エラー表示 |

---

## 8. MOD-08: 勤怠タイムシート・S11承認・36協定・過重労働・月次締め
- **主要画面**: `/my/timesheet`, `/attendance/list`, `/attendance/overtime-alert`, `/monthly-closing/list`
- **主要DB**: `t_work_record`, `t_attendance_discrepancy`, `t_monthly_closing`, `t_leave_balance`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD08-01 | `/my/timesheet` | 要員 | 画面カレンダーから当月全日の出退勤・休憩を入力し「提出」 | `t_work_record` (status='SUBMITTED', work_hours, overtime_hours) | 各カレンダーセルが「提出済」緑色になり、編集ロック |
| MOD08-02 | `/attendance/overtime-alert` | マネージャー | **S11**: 月80時間超の過重労働アラート・36協定超過チェック画面照会 | `t_attendance_discrepancy` | 該当要員が赤色ハイライト一覧描画、面談推奨アラート表示 |
| MOD08-03 | `/monthly-closing/list` | マネージャー | 対象月を選択し「月次締め確定」を押下 | `t_monthly_closing` (status='CLOSED', closed_at=NOW()) | 締め確定バナー表示、要員画面での同月勤怠変更が Swal で拒否される |
| MOD08-04 (Edge) | `/api/my/timesheet` | 要員 | 締め済み月の勤怠データをAPI経由で強制的に更新試行 (cURL等) | `MonthlyClosingServiceImpl.assertOpenForUpdate()` でブロック | `BusinessException` がスローされ `{"code": 400, "message": "締め済み月のため更新できません"}` のJSON応答 |
| MOD08-05 (Edge) | `/my/timesheet` | 要員 | **S11**: 深夜労働（22時以降）を複数日入力し保存 | `t_work_record` (night_shift_hours) | 「深夜労働時間が計上されました」のアラートと、割増賃金計算フラグが立つ |

---

## 9. MOD-09: 請求書自動発行・AR売掛金・入金消込・S16 JP PINT
- **主要画面**: `/invoice/list`, `/reconciliation/list`, `/invoice/jp-pint`
- **主要DB**: `t_invoice`, `t_invoice_item`, `t_invoice_payment`, `t_jp_pint_export_log`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD09-01 | `/invoice/list` | 経理/営業 | 確定勤怠から「当月一括請求書作成」ボタン押下 | `t_invoice` (invoice_number, total_amount)<br>`t_invoice_item` (控除/残業按分) | 契約単価・精算幅上下限計算・消費税が正確に計算された PDF プレビュー |
| MOD09-02 | `/reconciliation/list` | 経理 | 銀行入金一覧から該当請求書に対し「入金消込」ボタン押下 | `t_invoice_payment` (paid_amount, paid_date)<br>`t_invoice.status` = 'PAID' | 消込完了、売掛金エイジング一覧の該当金額が 0 円にクリア |
| MOD09-03 | `/invoice/jp-pint` | 経理 | **S16**: 対象請求書の「JP PINT デジタルインボイス XML 出力」ボタン押下 | `t_jp_pint_export_log` に出力ログ記録 | ブラウザで PEPPOL UBL 規格準拠の `.xml` ファイルが即座にダウンロード開始 |

---

## 10. MOD-10: BPパートナー・S12キャパシティ・S13外部ポータル
- **主要画面**: `/bp-availability/list`, `/staffing/capacity-planning`, `/portal/customer-bp`
- **主要DB**: `t_bp_company`, `t_bp_availability`, `t_staffing_capacity`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD10-01 | `/bp-availability/list` | 営業 | BP企業登録、BP要員空き情報メール取り込み実行 | `t_bp_company`, `t_bp_availability` | BP空き要員一覧テーブルに新要員追加 |
| MOD10-02 | `/staffing/capacity-planning` | マネージャー | **S12**: 今後6ヶ月の要員稼働予測シミュレーション実行 | `t_staffing_capacity` | 稼働率グラフ（折れ線）がリアルタイム更新、ベンチ数変動 |
| MOD10-03 | `/portal/customer-bp` | 外部BP | **S13**: 外部ポータルで匿名化スキルシート照会・応募 | `t_bp_application` | 応募完了 Toast、営業画面に案件応募通知が届く |

---

## 11. MOD-11: S15 会計連携・全銀FBデータ・BP支払
- **主要画面**: `/accounting/export`, `/bp-payment/list`
- **主要DB**: `t_accounting_journal`, `t_fb_transfer_log`, `t_bp_payment`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD11-01 | `/bp-payment/list` | 経理 | 勤怠確定データから BP パートナーへの支払明細一括生成 | `t_bp_payment` (payment_amount, due_date) | BP 支払一覧に支払予定額・源泉税・控除額が正しく計算 |
| MOD11-02 | `/accounting/export` | 経理 | **S15**: 会計仕訳 CSV および全銀協フォーマット FB 振込データ出力 | `t_accounting_journal`, `t_fb_transfer_log` | 借方/貸方完全一致の CSV および全銀 FB テキストファイルダウンロード |

---

## 12. MOD-12: S14 要員セルフサービスポータル v2
- **主要画面**: `/portal/engineer-self`
- **主要DB**: `t_engineer_self_profile`, `t_engineer_expense`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD12-01 | `/portal/engineer-self` | 要員 | 自自身の最新スキルタグ・資格を更新し保存 | `t_engineer_self_profile` | 更新完了 Toast、営業画面のスキルシートに即時反映 |
| MOD12-02 | `/portal/engineer-self` | 要員 | 交通費・書籍購入費の経費申請（領収書画像アップロード） | `t_engineer_expense` (amount, receipt_file_path) | 経費申請一覧に「申請中」カード表示 |

---

## 13. MOD-13: S17 AIフィードバック学習
- **主要画面**: `/ai/feedback-learning`
- **主要DB**: `t_ai_feedback_log`, `t_ai_model_config`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD13-01 | `/ai/feedback-learning` | 管理者 | 成約/失注データの適合度パラメータ調整、「再学習実行」押下 | `t_ai_feedback_log`, `t_ai_model_config` | AI プロンプト精度スコアと学習完了タイムスタンプが更新 |
| MOD13-02 (Edge) | `/ai/feedback-learning` | マネージャー | **S17**: 推奨されたマッチングが「面談不合格」だったため、評価を「1 (最低)」としてフィードバック送信 | `t_ai_feedback_log` に低評価記録 | 次回のAIマッチング試算時、同系統の案件・スキルに対するスコアが有意に低下することを確認 |

---

## 14. MOD-14: 組織・管理会計・営業歩合・ダッシュボード
- **主要画面**: `/`, `/sales-performance`, `/management-accounting`
- **主要DB**: `m_organization`, `m_system_config`, `t_sales_commission_snapshot`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD14-01 | `/` | 全ロール | ダッシュボード表示（稼働率、予想売上、粗利益率、ベンチ数） | Caffeine キャッシュ / 各マスタ SELECT | 300人データに基づく最新 KPI パネル・グラフが描画 |
| MOD14-02 | `/sales-performance` | 管理者 | 歩合ルール共通設定（粗利ベース / 15%）を変更保存 | `m_system_config` (key='commission.rate') | 全営業のコミッション計算結果が画面上で即座に再試算描画 |

---

## 15. MOD-15: 多段階承認・見積・注文・検収・文書保管
- **主要画面**: `/approval/list`, `/quotation/list`, `/sales-order/list`, `/document/list`
- **主要DB**: `t_approval_request`, `t_quotation`, `t_sales_order`, `t_document_archive`

| テストID | 操作画面 | 操作ロール | UI操作内容・パラメータ | DBデータ反映先 (テーブル.カラム) | 期待される画面挙動・結果 |
|---|---|---|---|---|---|
| MOD15-01 | `/quotation/list` | 営業 | 見積書新規発行、捺印画像選択し PDF 出力 | `t_quotation` (quotation_number, total_amount) | 印影画像が正しく埋め込まれた見積書 PDF がプレビュー表示 |
| MOD15-02 | `/approval/list` | マネージャー | 申請された見積・契約の多段階承認（第1段階承認）を実行 | `t_approval_request`, `t_approval_step` | ステータスが「第2次承認待ち」へ更新、次承認者へ通知送信 |

---
