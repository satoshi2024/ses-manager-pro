# Review Fixes — 実装レビュー指摘と修正ガイド（lifecycle-status-consistency）

対象: コミット `d4e37b1`（S1〜S7 実装、ブランチ `claude/lifecycle-status-consistency`）に対する
コードレビュー（2026-07-17、effort=high）の指摘事項と修正材料。
**本ドキュメントは指摘の記録のみで、修正は未実施**。対応する AI セッションは各節の修正ガイダンスに従い、
修正とテストを同一コミットに含め、完了した節の見出しへ `[対応済み]` を追記すること。

レビュー時の確認済み事項:

- S1〜S7 すべて実装済み。design.md への準拠度は高い。特に以下を実コード照合で確認:
  - S1-1: `isActiveSalesUser` への在職判定一本化（`validate` 側も置換済み）、未帰属時の
    `CONTRACT_DRAFT_UNATTRIBUTED` 通知分岐
  - S1-2: 3フック（delete 常時 / updateStatus の status→0 / update のロール離脱、旧ロールは getById）
  - S2: `releaseAllByEngineerId`（released_at 設定・primary_flag 不変）＋ count SQL の `t_engineer` join
  - S3: `NotificationLinks` 定数集約。`NotificationLinkRouteTest` は design の「MockMvc で 200」より
    優れた `RequestMappingHandlerMapping` によるルート解決検証を採用し、パラメータ付きリンク
    （`/engineer/detail?id=`・`/customer/{id}`）もカバー——design からの良い逸脱として承認
  - S4: `renewed_from_contract_id` の Set 除外（`hasExistingDraft` と同一基準）＋ README 申し送り更新
  - S5: サービス層 `updateWithStatusGuard`（status 変化時のみ・稼動中/Bench のみ）、
    `StatusConstants.ENGINEER_ACTIVE/ENGINEER_BENCH` の実在確認
  - S6: 削除成功後の `releaseIfIdle`
  - S7: 勤怠グリッドと同一の期間・状態判定＋ `invalidMonth` の 400 化
- i18n 6キー×4言語すべて追加済み（`MessageBundleConsistencyTest` でキー同期も担保）。
- `mvn test` を本環境で再実行し **486 tests / failures 0 / errors 0 / skipped 2** を確認
  （skip は Docker 必須の smoke test 等、従来どおり）。

指摘は 4 件。**ブロッカーなし**（マージを止める深刻度のものは無い）。G1・G2 は次の実装サイクルでの
対応を推奨、G3・G4 は任意。

---

## G1.【中・APIバイパス】汎用 `PUT /api/users` に status を含めると S1-2 の無効化ガードを迂回できる

- **場所**: `controller/api/UserApiController.java` の `update`（`@PutMapping`）
- **内容**: 無効化ガードは専用エンドポイント `PUT /{id}/status` にのみ実装された。しかし汎用 update は
  受信した `SysUser` をそのまま `updateById` へ渡すため、payload に `"status": 0` を含めれば
  **現任担当を持つ営業ユーザーをガードなしで無効化できる**。UI（`user.js`）の編集フォームは
  status を送らないため通常操作では起きないが、API 直叩き・将来のフロント変更で成立する
  （契約 API が「状態は専用 API の状態機械を経由させる」ため update で `setStatus(null)` している
  防御と非対称）。
- **修正ガイダンス**: 契約 API と同型に揃えるのが最小かつ一貫:

  ```java
  // update() 冒頭。ステータス変更は専用エンドポイント(/{id}/status)の状態ガードを経由させる。
  sysUser.setStatus(null);
  ```

  ロール変更ガードは既に update 内にあるため、これで「update=属性編集、status=専用API」の
  責務分離が完成する。
- **テスト**: `UserApiControllerTest` — 担当ありユーザーへ `{"status":0,...}` を含む PUT →
  200 だが status が変更されない（`updateById` に渡る引数の status が null）ことを verify。

## G2.【中低・業務詰み】解約で期間短縮された契約の実績が「編集不可・グリッド不可視・自動再確定」の三すくみになる

- **場所**: `service/impl/WorkRecordServiceImpl.java` の `saveHours`（S7 新ガード）×
  `confirmMonth` × `selectMonthlyGrid` の相互作用
- **内容**: S7 のガードは新規・既存を問わず「契約が 稼動中/終了 かつ 対象月が期間内」を要求する。
  一方 money-flow-consistency で導入された解約は `end_date` を解約日へ短縮する。この組み合わせで:
  1. 実績入力済み（または確定済み）の月を持つ契約が、その月より前の日付で解約される
  2. 当該月の実績を直したくて `reopenMonth` する（reopen 自体は契約状態を見ないため成功）
  3. `saveHours` が S7 ガード（解約状態 or 期間外）で**編集を拒否**
  4. しかも `confirmMonth` は契約状態を見ずに月内の全「入力中」を再確定するため、
     古い金額のまま**自動で再確定される**（グリッドには解約契約が出ないため画面からは見えない）
- **修正ガイダンス**（推奨案）: S7 ガードの趣旨は「**新規に**期間外・非稼動の実績を作らせない」
  縦深防御なので、**既存レコードの更新時はガードを免除**する:

  ```java
  // saveHours 内: record(既存行)が null のとき=新規作成のときだけ期間・状態ガードを適用する
  if (record == null && (!inPeriod || !statusOk)) {
      throw BusinessException.of("error.workRecord.contractNotBillable");
  }
  ```

  併せて `confirmMonth` 側は現状維持でよい（既存実績は救済・確定できるべきという同じ趣旨）。
  代替案（より厳格）: 解約 API 側で「解約日以降に実績（入力中/確定）が存在する場合は解約日を拒否」
  — ただし過去月の遡及解約という運用が塞がるため推奨しない。
- **テスト**: `WorkRecordServiceImplTest` — 解約済み契約の既存「入力中」レコードは saveHours で
  更新できる / 解約済み契約への新規作成は引き続き拒否される、の2ケース。

## G3.【低】`EngineerServiceImpl.removeById` が割当解除を削除成功の前に実行する

- **場所**: `service/impl/EngineerServiceImpl.java`（`releaseAllByEngineerId` の呼び出し位置）
- **内容**: `releaseAllByEngineerId(engineerId)` → `super.removeById(id)` の順のため、
  `removeById` が **false を返す**（並行削除等で対象行が既に無い）場合でも割当解除だけが
  コミットされる（false は例外でないため `@Transactional(rollbackFor=Exception)` は巻き戻さない）。
- **修正ガイダンス**: 呼び出し順を入れ替える:

  ```java
  boolean removed = super.removeById(id);
  if (removed) {
      engineerSalesService.releaseAllByEngineerId(engineerId);
  }
  return removed;
  ```

- **テスト**: `removeById` が false のとき `releaseAllByEngineerId` が呼ばれないことを verify。

## G4.【低・任意】`releaseAllByEngineerId` の wrapper 更新は `updated_at` 自動フィルが効かない

- **場所**: `service/impl/EngineerSalesServiceImpl.java` の `releaseAllByEngineerId`
- **内容**: エンティティを介さない `LambdaUpdateWrapper` 更新のため MyBatis-Plus の
  `MetaObjectHandler`（updated_at 自動設定）が発火せず、`released_at` だけ変わって
  `updated_at` が古いままになる（監査・変更追跡上の軽微な不整合）。
- **修正ガイダンス**: `.set(EngineerSales::getUpdatedAt, LocalDateTime.now())` を1行追加するか、
  「一括解除は updated_at を更新しない」ことをメソッド Javadoc に明記して許容する（どちらでも可）。

---

## レビューで問題なしと確認した設計上の論点（対応不要・記録のみ）

- `releaseAllByEngineerId` が `primary_flag` を触らない → 履歴として当時の主担当が残る。意図どおり。
- S1-1 の冪等経路（生成済みドラフトを返す場合）は旧データの退職担当をそのまま返す → 帰属保持の仕様どおり。
- S5 ガードは「提案中」「退場予定」への手動変更・status 不変の編集を制限しない → requirements S5-3/S5-4 どおり。
- `contractEnding` の除外 Set は論理削除済みドラフトを自動的に含めない（wrapper が除外）→
  ドラフト破棄で通知が再開する正しい挙動。
- `NotificationLinkRouteTest` の `@SpringBootTest` はやや重いが、ルート解決には全 PageController の
  スキャンが必要なため妥当。

## 進め方

- 推奨順序: **G1 → G2**（いずれもテスト込み・小粒）→ G3 → G4（任意）。
- すべて本ブランチ（`claude/lifecycle-status-consistency`）上で修正し、完了後に `mvn test` 全緑を
  再確認、本ドキュメントの各節へ `[対応済み]` を追記すること。
