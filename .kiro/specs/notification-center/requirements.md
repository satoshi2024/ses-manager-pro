# Requirements Document — 通知センター・ToDo(P3)

## Introduction

現状の通知(`NotificationServiceImpl`)はリクエスト毎の全件再計算で、既読管理が無く(ベルのバッジが消えない)、
種別も2種類(退場30日以内・AIログ24時間以内)のみ、ループ内 `selectById` の N+1 も抱える。
本フェーズで通知を `t_notification` テーブルに永続化し、定時バッチで生成、既読管理と ToDo 一覧画面を提供する。

## Requirements

### Requirement 1: 通知の永続化と既読管理

#### Acceptance Criteria
1. THE システム SHALL 通知を `t_notification` に保存し、ユーザー単位で既読/未読を管理する。
2. THE ヘッダーのベル SHALL 未読件数をバッジ表示し、通知クリックで既読化 + 関連画面へ遷移する。
3. THE システム SHALL 「すべて既読にする」操作を提供する。
4. THE 通知ドロップダウン SHALL 最新10件(未読優先・新しい順)を表示する。

### Requirement 2: 定時バッチによる通知生成

#### Acceptance Criteria
1. THE システム SHALL 毎日 08:00(サーバー時刻)に通知生成バッチを実行する。
2. THE バッチ SHALL 同一事象の通知を重複生成しない(重複キーで冪等)。
3. THE バッチ SHALL 以下の種別を生成する:
   - CONTRACT_END: 稼動中契約の終了日が30日以内
   - PROPOSAL_STALE: 提案が7日以上ステータス変化なし(オープン状態のみ)
   - BENCH_LONG: Bench 状態が30日超の要員
   - PROJECT_URGENT: 優先度「急募」の募集中案件
4. 通知の宛先は全ユーザー共通(broadcast)とし、既読状態のみユーザー別に持つ。※日数閾値は P8 のシステム設定で可変化するまで定数とする。

### Requirement 3: ToDo センター画面

#### Acceptance Criteria
1. THE システム SHALL `/todo` に通知全件の一覧画面(種別フィルタ・未読のみフィルタ・ページネーション)を提供する。
2. THE 一覧 SHALL 各行に関連画面へのリンクと既読化操作を持つ。
3. THE 画面 SHALL サイドバーに「ToDo・通知」メニューとして追加され、既存のロール別メニュー権限制御に従う。

### Requirement 4: 既存通知処理の置き換え

#### Acceptance Criteria
1. THE `/api/notifications` SHALL 永続化データから返すよう置き換える(即時計算の廃止、N+1 解消)。
2. 既存テスト(`NotificationServiceImplTest` / `NotificationApiControllerTest`)は新仕様に合わせて更新する。
