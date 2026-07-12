# Requirements Document — 営業活動管理(P6)

## Introduction

顧客マスタ(`m_customer`)は現状「住所録+信頼度ランク」に留まる。商談・訪問・フォローアップの記録と、
顧客単位の実績(案件数・提案成功率)を可視化する軽量 CRM 機能を追加する。

**依存**: P3(通知センター)導入済みならフォローアップ通知を連携する(未導入でも本体は動作)。

## Requirements

### Requirement 1: 営業活動の記録

#### Acceptance Criteria
1. THE システム SHALL 顧客に対する活動(種別: 商談/訪問/電話/メール/その他、活動日、タイトル、内容、次回アクション予定日)を登録・編集・削除できる。
2. THE 活動 SHALL 登録者(`created_by`)を自動記録する。
3. 削除は論理削除とし、SweetAlert2 確認後に実行する。

### Requirement 2: 顧客詳細画面

#### Acceptance Criteria
1. THE システム SHALL `/customer/{id}` の顧客詳細画面を提供し、以下を表示する:
   - 基本情報(既存項目)
   - 活動タイムライン(活動日降順、種別アイコン付き)
   - 関連実績サマリ: 案件数、提案数、成約数、成約率(成約÷クローズ済提案)、稼動中契約数
2. THE 顧客一覧 SHALL 行クリック(または詳細ボタン)で詳細画面へ遷移する。

### Requirement 3: フォローアップ管理

#### Acceptance Criteria
1. THE システム SHALL 次回アクション予定日が今日以前で未完了の活動を「要フォロー」として顧客詳細と一覧バッジに表示する。
2. THE 活動 SHALL 「完了」フラグを持ち、完了操作でフォロー対象から外れる。
3. WHERE P3 導入済み、THE 通知バッチ SHALL 期限到来のフォローアップを FOLLOW_UP 種別で通知する(dedupe_key = `FOLLOW_UP:{activityId}:{予定日}`)。

### Requirement 4: 権限

#### Acceptance Criteria
1. 顧客詳細・活動 API は既存 `customer` メニュー(path_prefix=/customer, api_prefix=/api/customers)の権限制御下に置く(メニュー追加なし)。
