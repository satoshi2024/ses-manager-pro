# Implementation Plan — 人員ライフサイクル×状態連動の整合性（lifecycle-status-consistency）

レーン構成: **A（営業・ユーザー系）→ C（状態・勤怠系）**、**B（通知系）は完全並行可**。
A と C は `EngineerServiceImpl` / `ContractServiceImpl` で同一ファイルに触れるため、
並行させる場合は A→C の順でマージするか同一セッションで実施する。
i18n 4ファイルは全レーンが追記（キー追加のみ、マージ順を決めること）。
詳細は design.md を参照。

- [x] 0. spec ドキュメント
  - **Objective**: 本ディレクトリの requirements / design / tasks 3ファイル（2026-07-17 第3次調査結果の整理）。
  - **Demo**: レビュー可能な状態でコミットされている。

- [x] A1. 成約時の退職主担当フォールバック（S1-1）
  - **Objective**: 主担当営業が退職済みでも提案の成約が失敗しないようにする（未帰属ドラフト生成）。
  - **実装ガイダンス**: design.md S1-1。`EngineerSalesService.isActiveSalesUser` 新設、
    `ContractServiceImpl.validate` の在職判定も同メソッドへ一本化、
    未帰属時は `notification.msg.CONTRACT_DRAFT_UNATTRIBUTED`（4言語）で通知。
  - **テスト要件**: `ContractServiceImplTest` — 退職主担当→sales_user_id NULL でドラフト生成成功 /
    在職主担当→従来どおり設定 / validate の一本化後も既存の在職チェックテストが通る。
  - **Demo**: 主担当営業を無効化 → 当該要員の提案を成約 → ドラフトが未帰属で生成され、
    営業成績の未帰属行と通知に現れることを確認。

- [x] A2. ユーザー無効化・削除・ロール変更の担当残存ガード（S1-2）
  - **Objective**: 現任担当を持つ営業ユーザーのライフサイクル操作を先回りで拒否し、担当の付け替えを促す。
  - **実装ガイダンス**: design.md S1-2。`guardNoActiveSalesAssignments`、フックは3地点:
    `delete` 常時 / `updateStatus`（専用エンドポイント `PUT /{id}/status`）は status→0 時 /
    `update` はロール離脱時のみ（旧ロールは getById で取得）。
    `error.user.hasActiveSalesAssignments`（{0}=担当要員数）4言語。
  - **テスト要件**: `UserApiControllerTest` — 担当ありの delete / 無効化 / ロール変更が 4xx（件数入り）/
    担当なしは成功 / 担当ありでも他項目編集は成功。
  - **Demo**: 要員に主担当を割当 → 当該営業ユーザーを削除しようとするとエラー →
    要員詳細で担当解除 → 削除が通る。

- [x] A3. 要員削除時の割当解放（S2）
  - **Objective**: 要員削除で現任割当を released_at 設定により解除し、営業成績の担当要員数から除外する。
  - **実装ガイダンス**: design.md S2。`releaseAllByEngineerId` 新設＋`removeById` から呼ぶ。
    `countActivePrimaryGroupBySalesUser` に `t_engineer` join を追加（過去残骸への防御）。
  - **テスト要件**: `EngineerSalesServiceImplTest` — 削除後 released_at 設定 /
    H2 統合で count SQL が削除済み要員を除外。
  - **Demo**: 割当あり Bench 要員を削除 → 営業成績の担当要員数が即時减る。

- [x] B1. 通知リンク修正＋ルート整合テスト（S3）
  - **Objective**: リンク切れ2件の修正と、通知リンク⇔ページルートの恒常的な整合検証。
  - **実装ガイダンス**: design.md S3。`NotificationLinks` 定数クラス新設・全 publish 呼び出しを
    定数参照へ置換・`NotificationLinkRouteTest` はリフレクションで全定数を列挙し MockMvc GET 200 を確認。
  - **テスト要件**: `NotificationLinkRouteTest` グリーン（`/invoice/list`・`/proposal` のままだと赤になること）。
  - **Demo**: 支払期限超過の請求書を作り、ベルの通知クリックで請求書画面（404でなく）へ遷移する。

- [x] B2. CONTRACT_END と更新ドラフトの連動（S4）
  - **Objective**: 更新ドラフト生成済み契約への「稼動終了間近」通知を止める（README セクション0 の申し送り解消）。
  - **実装ガイダンス**: design.md S4。`renewed_from_contract_id` の Set で除外。
  - **テスト要件**: ドラフト生成済み契約→通知なし / auto_renew=1 未生成→通知あり / ドラフト論理削除→通知再開。
  - **Demo**: auto_renew 契約の終了30日前 → バッチ実行 → ドラフト生成後は CONTRACT_END が出ないことを確認。
    あわせて `.kiro/specs/README.md` セクション0 の該当申し送りを「確認済み・対応済み」へ更新。

- [x] C1. 要員ステータス手動編集ガード（S5）
  - **Objective**: 稼働率 KPI の土台である要員ステータスと契約事実の乖離を編集時に防ぐ。
  - **実装ガイダンス**: design.md S5。サービス層 `updateWithStatusGuard`、ガードは
    status 変化時のみ・稼動中/Bench のみ対象。CSV インポートは新規登録のみで更新経路なし
    （確認済み、考慮不要）。メッセージ2本×4言語。
  - **テスト要件**: design.md のテスト方針表 S5 の4ケース。
  - **Demo**: 契約のない要員を編集で「稼動中」にしようとするとエラー。ステータス以外の編集は従来どおり。

- [x] C2. 契約削除時の releaseIfIdle（S6）
  - **Objective**: 契約削除後に要員が「提案中」「稼動中」のまま取り残されないようにする。
  - **実装ガイダンス**: design.md S6。削除成功後に `releaseIfIdle`。
  - **テスト要件**: `ContractServiceImplTest` — 準備中契約削除で releaseIfIdle が呼ばれる /
    engineerId null では呼ばれない。
  - **Demo**: 成約→ドラフト生成→ドラフト削除 → 要員が Bench に戻る。

- [x] C3. saveHours の期間・状態検証（S7）
  - **Objective**: API 直叩きによる契約期間外・非稼動契約への実績登録（→請求計上）を遮断する。
  - **実装ガイダンス**: design.md S7。判定は勤怠グリッドの WHERE と同一。
    `error.workRecord.contractNotBillable` / `error.workRecord.invalidMonth` 4言語。
  - **テスト要件**: design.md のテスト方針表 S7 の3系統。
  - **Demo**: curl で契約期間外の月に saveHours → 4xx。グリッドからの通常入力は従来どおり。

- [x] M. 統合回帰
  - **Objective**: 全レーン統合後の回帰確認とドキュメント更新。
  - **テスト要件**: `mvn test` 全件グリーン（Docker あり環境では smoke test 含む）。
  - **Demo**: A1/A2 の営業ライフサイクル一連（無効化拒否→担当解除→無効化→成約→未帰属解消）を通しで確認。
    `.kiro/specs/README.md` のディスパッチ表に本 spec の状態を反映。
