# Requirements — 人員ライフサイクル×状態連動の整合性（lifecycle-status-consistency）

この spec は 2026-07-17 に実施した第3次業務ロジック横断調査の結果をまとめたもの。
金銭フロー（`money-flow-consistency`、対応済み）の次に残っていた、
**マスタ/人員のライフサイクル（退職・無効化・削除）と業務データ・状態機械の連動断絶**を対象とする。

対象フロー: `ユーザー(営業)・要員のライフサイクル → 担当営業割当 → 提案成約 → 契約 → 要員ステータス → 通知`

関連既存 spec との関係:

- `business-logic-integrity-hardening`（A1 Flyway / A2 DB制約 / E2 統合回帰が未完了のまま残っている——本 spec の対象外だが再開が必要）
- `engineer-sales-commission`（担当営業モデルの導入元。退職営業の**過去実績の保持**は規定済みだが、退職営業が**現任主担当のまま残る**場合の挙動は未規定——本 spec S1/S2 が補完）
- `money-flow-consistency` F5（通知リンク404と同族の残存が S3）
- `.kiro/specs/README.md` セクション0 の申し送り「CONTRACT_END が後続契約の有無をチェックしているか要確認」→ 調査の結果**チェックしていない**（S4）

## 調査サマリ（発見事項一覧）

| # | 発見事項 | 深刻度 | 種別 |
|---|---|---|---|
| S1 | **退職済み営業が主担当のままの要員は提案を「成約」にできない**。`createDraftFromProposal` が主担当をそのまま契約に引き継ぎ、insert 経路の `validate` が在職営業を要求して `BusinessException` → 成約遷移ごとロールバック。ユーザー側（無効化・削除・ロール変更）に担当割当の残存チェックもない | **重大** | 業務停止 |
| S2 | **要員を削除しても `t_engineer_sales` の現任割当が解除されない**。削除ガードは稼動中契約・オープン提案のみ。割当が残ると (a) 営業成績の「担当要員数」（`countActivePrimaryGroupBySalesUser` は `t_engineer` を join しない）に削除済み要員が数え続けられる、(b) 要員詳細画面が消えるため UI から解除する手段がない | 中 | データ残骸・集計歪み |
| S3 | **通知リンク切れが2件残存**: `INVOICE_OVERDUE` → `/invoice/list`（実ルートは `/invoice`）、`PROPOSAL_STALE` → `/proposal`（実ルートは `/proposal/kanban`）。money-flow-consistency F5 と同族＝通知リンクとページルートの対応を検証する仕組みがない | 中 | 導線切れ |
| S4 | **CONTRACT_END（稼動終了間近）通知が自動更新と連動しない**: `auto_renew=1` で更新ドラフト生成済みの契約も終了日まで毎日「稼動終了間近」を通知し続ける（dedupeKey は `id:endDate` 単位なので日次では増えないが、更新済み案件へのノイズ）。README セクション0 の未確認事項の確定 | 中 | 通知ノイズ |
| S5 | **要員ステータスの手動編集に整合性ガードがない**: `PUT /api/engineers` は `updateById` 直通で、稼動中契約のない要員を「稼動中」に、稼動中契約のある要員を「Bench」に手動設定できる。稼働率 KPI・ベンチ一覧・ステータスチャートはすべて `t_engineer.status` を信頼するため全社 KPI が事実と乖離する | 中 | KPI歪み |
| S6 | **契約削除が要員ステータスを再計算しない**: `ContractServiceImpl.removeById` は `releaseIfIdle` を呼ばない。成約→ドラフト生成→（案件消滅で）ドラフト削除、の後で提案は「成約」でクローズ済み・契約も無いのに要員が「提案中」のまま残り続ける | 中 | 状態残骸 |
| S7 | **工数入力 API が契約期間・契約状態を検証しない**: `saveHours` は契約の存在のみ確認。グリッド外から直接 API を叩けば契約期間外・解約済み契約の月に実績を作成でき、確定すれば**請求書にも計上される**（`selectUnbilledWorkRecords` は期間を見ない）。UI 経由では起きないが金銭に直結する防御欠落 | 低〜中 | 縦深防御 |

**問題なしを確認済みの領域**（参考）: 顧客削除ガード（案件・契約・請求書の残存を全て拒否）、
案件削除ガード（契約・オープン提案を拒否）、候補者→要員の変換（手動リンク方式で残骸なし）、
提案の削除経路（存在しないため断絶なし）、`releaseIfIdle` 自体のロジック
（オープン提案と稼動中契約の両方を確認してから Bench 化——正しい）。

## Requirements

### S1. 営業ユーザーのライフサイクルと担当・成約フローの整合

#### Acceptance Criteria
1. WHEN 主担当営業が退職済み（`status=0` または論理削除）の要員の提案を「成約」へ遷移させた場合、THE システム SHALL 成約を失敗させず、契約ドラフトを生成できる。方式は次のいずれかを設計で確定する:
   - (a) `createDraftFromProposal` が主担当の在職を確認し、退職済みなら `sales_user_id=NULL`（未帰属）でドラフト生成＋通知で担当設定を促す
   - (b) insert 経路の `validate` にも「システム由来の引き継ぎ値は在職チェック免除」を導入する
   （推奨は (a)。未帰属は営業成績の未帰属行と R1 編集UIで解消できる導線が既にある）
2. WHEN ユーザーを無効化・削除・ロール変更（営業→他ロール）する場合、THE `UserApiController` SHALL 当該ユーザーが現任担当（`t_engineer_sales.released_at IS NULL`）を持つならエラーで拒否し、メッセージに担当要員数を含める（先に担当の解除・付け替えを促す）。
3. THE 上記ガード SHALL 過去実績（`released_at` 設定済みの履歴、契約の `sales_user_id`）には影響しない。

### S2. 要員削除時の担当営業割当の解放

#### Acceptance Criteria
1. WHEN 要員を削除する場合、THE `EngineerServiceImpl.removeById` SHALL 同一トランザクションで当該要員の現任割当（`released_at IS NULL`）すべてに `released_at = 今日` を設定する（履歴保全のため物理削除・論理削除はしない——`engineer-sales-commission` R1-6 と同方式）。
2. THE `countActivePrimaryGroupBySalesUser` SHALL `t_engineer` を join し `deleted_flag=0` の要員のみ数える（1. の遡及漏れ・過去データへの防御）。

### S3. 通知リンクとページルートの整合

#### Acceptance Criteria
1. THE `INVOICE_OVERDUE` 通知リンク SHALL `/invoice` へ、`PROPOSAL_STALE` 通知リンク SHALL `/proposal/kanban` へ修正される。
2. THE テスト SHALL `NotificationGenerateService`・`ContractRenewalServiceImpl`・`WorkRecordServiceImpl` 等が publish する全リンクが実在のページルート（`*PageController` の `@RequestMapping`＋`@GetMapping` 合成パス）に解決されることを検証する（リンク文字列の一覧をテストで列挙し、MockMvc で 200/302 を確認する方式で可）。

### S4. 稼動終了間近通知と契約更新の連動

#### Acceptance Criteria
1. WHEN 契約に自動更新ドラフト（`renewed_from_contract_id = 当該契約ID` の契約）が存在する場合、THE `contractEnding()` SHALL 当該契約の CONTRACT_END 通知を発行しない（更新手続きが進行中のため）。
2. THE `auto_renew=1` だがドラフト未生成の契約 SHALL 引き続き通知対象とする（生成は日次バッチのため遅延がありうる）。
3. THE 判定基準 SHALL `engineer-availability-visualization` spec の「まもなく空き」判定（実装時）と同一の後続契約判定を共有する（README セクション0 の申し送りの解消）。

### S5. 要員ステータス手動編集の整合性ガード

#### Acceptance Criteria
1. WHEN `PUT /api/engineers` でステータスを「稼動中」へ変更する場合、THE システム SHALL 当該要員に稼動中契約が存在することを要求する（無ければ `BusinessException`）。
2. WHEN ステータスを「Bench」へ変更する場合、THE システム SHALL 稼動中契約が存在しないことを要求する。
3. THE 「提案中」「退場予定」への手動変更 SHALL 制限しない（提案中はオープン提案の有無が UI 以外の経路でも変動するため、退場予定は運用判断のため）。
4. THE ステータス変更を伴わない通常の項目編集 SHALL 影響を受けない（ガードは status が変化する場合のみ発動）。

### S6. 契約削除時の要員ステータス再計算

#### Acceptance Criteria
1. WHEN 契約を削除する場合、THE `ContractServiceImpl.removeById` SHALL 削除後に当該契約の `engineer_id` へ `releaseIfIdle` を呼び、オープン提案も稼動中契約も無ければ Bench へ戻す。
2. THE 既存の削除ガード（稼動中は削除不可・実績ありは削除不可） SHALL 変更しない。

### S7. 工数入力 API の契約期間・状態検証（縦深防御）

#### Acceptance Criteria
1. WHEN `saveHours` の `workMonth` が契約期間（`start_date`〜`end_date`、`end_date` NULL は無期限）と重ならない場合、THE システム SHALL `BusinessException` で拒否する。
2. WHEN 対象契約のステータスが `準備中` または `解約` の場合、THE システム SHALL 同様に拒否する（勤怠グリッドの表示条件 `稼動中/終了` と同一基準に揃える）。
3. THE 既存の勤怠グリッド経由の入力 SHALL 影響を受けない（グリッドは元々この条件で契約を列挙している）。
