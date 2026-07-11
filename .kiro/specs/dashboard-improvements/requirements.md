# Requirements Document

## Introduction

本要件は、SES Manager Pro のダッシュボード画面に存在する4つの不具合・未実装機能を解消するためのものである。対象は以下の4点である。

1. `/dashboard/profit`（利益分析ページ）への遷移時に発生する500エラーの解消（ページ・コントローラーの新規実装）
2. ダッシュボードの月次売上・粗利グラフにおける年度切り替えセレクトボックスの機能実装（既存の`t_contract`データからのリアルタイム年度別集計）
3. 「レポート出力」ボタンの機能実装（ブラウザ印刷機能の呼び出し）
4. ヘッダー通知ドロップダウンのハードコードされたダミーデータを、既存データ（退場予定エンジニア、AIマッチング完了ログ等）から動的に生成する仕組みへの置き換え

いずれの課題も新規テーブルを追加せず、既存の`t_contract`、`t_engineer`、`t_ai_log`等のデータをリアルタイムに集計・抽出することで実現する。

## Assumptions（前提事項）

要件定義にあたり、以下を前提とする。ユーザーからの明示的な指定がなかったため、妥当な既定値として設定した。レビュー時に変更が必要な場合は指摘すること。

- **会計年度の定義**: 日本の一般的な会計年度に合わせ、4月開始～翌年3月終了の12ヶ月間とする（例: 「2026年度」は2026年4月～2027年3月）。
- **利益分析ページの並び順**: 契約開始日の降順（新しい契約が先頭）とする。
- **AIマッチング完了通知の対象期間**: `t_ai_log`の`request_type`が「マッチング」であるレコードのうち、作成日時が直近24時間以内のものを対象とする。
- **通知件数の上限**: 通知ドロップダウンには最大10件まで表示する。

## Glossary

- **Profit_Analysis_Page**: `/dashboard/profit`にアクセスした際に表示される、契約ごとの粗利一覧を表示する画面
- **Contract**: `t_contract`テーブルに保存される契約データ（契約番号、売上単価、原価単価、開始日、終了日、ステータス等を含む）
- **Gross_Profit_Amount**: 契約の売上単価から原価単価を減じた金額
- **Gross_Profit_Rate**: Gross_Profit_Amountを売上単価で除して100を乗じたパーセンテージ
- **Dashboard_Summary_Api**: `/api/dashboard/summary`エンドポイントを指すバックエンドAPI
- **Dashboard_Page**: `dashboard/index.html`テンプレートおよび`dashboard.js`によって構成されるダッシュボード画面
- **Fiscal_Year**: 4月開始・翌年3月終了の12ヶ月間の会計年度
- **Fiscal_Year_Selector**: Dashboard_Page上に表示される年度選択用の`<select>`要素
- **Monthly_Revenue_Chart**: Dashboard_Page上の`revenueChart`要素に描画される月次売上・粗利の柱状グラフ
- **Notification_Api**: 通知一覧を返す新規バックエンドAPIエンドポイント
- **Header_Component**: `templates/layout/header.html`によって構成される、通知ドロップダウンを含む共通ヘッダー
- **Retiring_Engineer_Notification**: 退場予定日が現在日から30日以内であるエンジニアに関する通知項目
- **Ai_Matching_Completion_Notification**: `t_ai_log`テーブルの`request_type`が「マッチング」であるレコードから生成される通知項目

## Requirements

### Requirement 1: 利益分析ページの表示

**User Story:** 経営者として、契約ごとの粗利を一覧で確認したい、収益性の高い契約と低い契約を把握するため

#### Acceptance Criteria

1. WHEN a user navigates to `/dashboard/profit`, THE Profit_Analysis_Page SHALL display a table listing every Contract with its contract number, selling price, cost price, Gross_Profit_Amount, and Gross_Profit_Rate.
2. THE Profit_Analysis_Page SHALL calculate the Gross_Profit_Amount for each Contract as the selling price minus the cost price.
3. THE Profit_Analysis_Page SHALL calculate the Gross_Profit_Rate for each Contract as the Gross_Profit_Amount divided by the selling price, expressed as a percentage.
4. IF a Contract's selling price is zero, THEN THE Profit_Analysis_Page SHALL display the Gross_Profit_Rate for that Contract as "N/A" instead of performing the division.
5. THE Profit_Analysis_Page SHALL order the displayed contracts by contract start date in descending order.
6. IF no Contract records exist, THEN THE Profit_Analysis_Page SHALL display a message indicating no contract data is available.

### Requirement 2: 年度別売上・粗利データのリアルタイム集計API

**User Story:** 経営企画担当者として、指定した会計年度の月次売上・粗利データを取得したい、年度単位で経営状況を把握するため

#### Acceptance Criteria

1. WHERE a fiscal year parameter is provided in the request, THE Dashboard_Summary_Api SHALL return monthly sales and gross profit data for the 12 months of the specified Fiscal_Year instead of the default trailing period.
2. IF the fiscal year parameter is not provided, THEN THE Dashboard_Summary_Api SHALL return data using the existing default 6-month trailing behavior.
3. THE Dashboard_Summary_Api SHALL calculate the monthly sales for a given month as the sum of selling prices of all Contract records active during that month.
4. THE Dashboard_Summary_Api SHALL calculate the monthly gross profit for a given month as the sum of Gross_Profit_Amount of all Contract records active during that month.
5. THE Dashboard_Summary_Api SHALL treat a Contract as active during a given month WHEN the Contract's start date is on or before the last day of that month AND the Contract's end date is either null or on or after the first day of that month.
6. THE Dashboard_Summary_Api SHALL compute the 12 months of a Fiscal_Year as April of the specified year through March of the following year.
7. IF the requested Fiscal_Year contains no active Contract records for a given month, THEN THE Dashboard_Summary_Api SHALL return zero for that month's sales and gross profit values.
8. IF the requested Fiscal_Year contains at least one active Contract record for a given month, THEN THE Dashboard_Summary_Api SHALL return the calculated sales and gross profit values for that month as the actual computed result, even WHEN that computed result equals zero, without treating it as the no-active-contract case described in Acceptance Criteria 2.7.

### Requirement 3: 年度切り替えセレクトボックスの動作

**User Story:** 経営企画担当者として、年度セレクトボックスを切り替えたら該当年度のグラフが即座に表示されるようにしたい

#### Acceptance Criteria

1. WHEN a user changes the selection in the Fiscal_Year_Selector, THE Dashboard_Page SHALL request updated monthly sales and gross profit data for the selected Fiscal_Year from the Dashboard_Summary_Api.
2. WHEN updated monthly data is received from the Dashboard_Summary_Api, THE Dashboard_Page SHALL re-render the Monthly_Revenue_Chart with the received data without a full page reload.
3. WHEN the Dashboard_Page loads, THE Fiscal_Year_Selector SHALL default to the current Fiscal_Year and THE Dashboard_Page SHALL display Monthly_Revenue_Chart data for that Fiscal_Year.
4. IF the request to the Dashboard_Summary_Api fails, THEN THE Dashboard_Page SHALL display an error notification and retain the previously rendered Monthly_Revenue_Chart data.

### Requirement 4: レポート出力（印刷）機能

**User Story:** マネージャーとして、ダッシュボードの内容を会議資料として印刷出力したい

#### Acceptance Criteria

1. WHEN a user clicks the レポート出力 button, THE Dashboard_Page SHALL invoke the browser's print preview function.
2. WHEN the browser's print preview function is invoked, THE Dashboard_Page SHALL apply print-optimized styling that hides the レポート出力 button and the navigation sidebar from the printed output.
3. WHEN the browser's print preview function is invoked, THE Dashboard_Page SHALL include the KPI cards, the Monthly_Revenue_Chart, the status distribution chart, and the retiring engineer table in the printed output.

### Requirement 5: 通知データの動的生成API

**User Story:** システム利用者として、実際のデータに基づいた通知情報を取得したい、最新の状況を把握するため

#### Acceptance Criteria

1. WHEN a client requests the Notification_Api, THE Notification_Api SHALL return a list of notifications generated from current system data.
2. THE Notification_Api SHALL include one Retiring_Engineer_Notification for each engineer whose associated active Contract has an end date within 30 days of the current date.
3. THE Notification_Api SHALL include one Ai_Matching_Completion_Notification for each `t_ai_log` record whose `request_type` is "マッチング" and whose creation timestamp is within the most recent 24 hours.
4. WHERE no notification conditions are met, THE Notification_Api SHALL return an empty notification list.
5. THE Notification_Api SHALL order the returned notifications by their associated date in descending order.
6. IF the number of notifications matching the notification conditions exceeds 10, THEN THE Notification_Api SHALL return only the most recent 10 items according to the ordering defined in Acceptance Criteria 5.5, instead of the empty notification list described in Acceptance Criteria 5.4.

### Requirement 6: 通知ドロップダウンの動的表示

**User Story:** システム利用者として、ヘッダーの通知ドロップダウンで最新の通知内容を確認したい

#### Acceptance Criteria

1. WHEN any page using the Header_Component loads, THE Header_Component SHALL request the notification list from the Notification_Api.
2. WHEN the notification list is received, THE Header_Component SHALL render each returned notification as an item in the notification dropdown, replacing the previously hardcoded notification items.
3. IF the received notification list is empty, THEN THE Header_Component SHALL keep the notification dropdown container visible and display, within that container, a message indicating there are no new notifications.
4. THE Header_Component SHALL display a distinct icon for Retiring_Engineer_Notification items than for Ai_Matching_Completion_Notification items.
5. IF the request to the Notification_Api fails, THEN THE Header_Component SHALL display the notification dropdown with a message indicating notifications could not be loaded.
