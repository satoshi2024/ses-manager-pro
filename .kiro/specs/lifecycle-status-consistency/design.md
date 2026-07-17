# Design — 人員ライフサイクル×状態連動の整合性（lifecycle-status-consistency）

対象コード全体像（新規テーブル・新規マイグレーションなし。すべて既存クラスへの追記）:

- 営業ライフサイクル: `ContractServiceImpl.createDraftFromProposal` / `UserApiController` / `EngineerSalesService(Impl)`
- 要員削除: `EngineerServiceImpl.removeById` / `EngineerSalesMapper`
- 通知: `NotificationGenerateService` / 新規テスト
- 要員ステータス: `EngineerApiController` or `EngineerService(Impl)`（設計判断: サービス層に置く）
- 契約削除: `ContractServiceImpl.removeById`
- 勤怠: `WorkRecordServiceImpl.saveHours`

---

## S1. 営業ライフサイクル×成約フロー

### S1-1. 成約時の退職主担当フォールバック（方式(a)を採用）

`ContractServiceImpl.createDraftFromProposal`:

```java
Long primaryId = engineerSalesService.findPrimarySalesUserId(proposal.getEngineerId());
// 主担当が退職済み(無効/論理削除)なら未帰属でドラフト生成し、後続の担当設定に委ねる。
// ここで validate に落とすと成約遷移ごとロールバックし業務が止まるため(在職チェックは insert 経路で有効)。
if (primaryId != null && !engineerSalesService.isActiveSalesUser(primaryId)) {
    primaryId = null;
}
contract.setSalesUserId(primaryId);
```

- `EngineerSalesService` に `boolean isActiveSalesUser(Long userId)` を追加
  （`sys_user` を `selectById`（論理削除考慮）→ 非null かつ `role='営業'` かつ `status=1`。
  `ContractServiceImpl.validate` の在職判定と同一条件。判定ロジックはこのメソッドに一本化し、
  `validate` からも同メソッドを呼ぶよう置換して二重定義を避ける）。
- 未帰属になった場合は既存の `CONTRACT_DRAFT` 通知に加えて担当設定を促す必要があるが、
  通知種別を増やさず **`CONTRACT_DRAFT` のメッセージ末尾に「担当営業は未設定です」を付ける方式は
  i18n JSON 配列の引数追加で対応**（`notification.msg.CONTRACT_DRAFT_UNATTRIBUTED` を新設し、
  未帰属時のみこちらのキーで publish する）。4言語追加。

### S1-2. ユーザー無効化・削除・ロール変更時の担当残存ガード

`UserApiController` のフック地点は **3つ**（実コード確認済み——無効化は汎用 update ではなく専用エンドポイント）:

- 対象操作の事前に `engineerSalesMapper.selectCount(released_at IS NULL AND sales_user_id = #{id})` を確認し、
  0 でなければ `BusinessException.of("error.user.hasActiveSalesAssignments", count)`。
- 発動条件:
  - `DELETE /{id}`（`delete`）: 常に
  - `PUT /{id}/status`（`updateStatus`）: `status=0`（無効化）にする場合のみ
  - `PUT`（`update`）: `role` を `営業` から他へ変更する場合のみ。旧ロールの判定には対象ユーザーを
    `getById` でロードして比較する（現実装は自己変更チェック以外で旧値をロードしていないため追加が必要）
- 既存の自己変更ガード（`guardNotSelf`）と同列のプライベートメソッド `guardNoActiveSalesAssignments(id)` として実装。
- メッセージ `error.user.hasActiveSalesAssignments`（{0}=担当要員数）を4言語追加。
- UI: `user.js` はエラーメッセージをそのまま表示するだけでよい（既存パターン）。

## S2. 要員削除時の割当解放

`EngineerServiceImpl.removeById`（既存ガードの後、`super.removeById` の前）:

```java
// 現任の担当営業割当を解除する(履歴保全のため released_at 設定。論理削除はしない)
engineerSalesService.releaseAllByEngineerId(engineerId);
```

- `EngineerSalesService` に `void releaseAllByEngineerId(Long engineerId)` を追加:
  `UPDATE t_engineer_sales SET released_at = CURRENT_DATE WHERE engineer_id=#{id} AND released_at IS NULL AND deleted_flag=0`
  相当を `LambdaUpdateWrapper` で実装（`primary_flag` は触らない——履歴として当時の主担当が残る）。
- `EngineerSalesMapper.countActivePrimaryGroupBySalesUser` に `INNER JOIN t_engineer e ON es.engineer_id = e.id AND e.deleted_flag = 0`
  を追加（過去に残った残骸への防御）。

## S3. 通知リンク修正＋ルート整合テスト

1. `NotificationGenerateService`: `"/invoice/list"` → `"/invoice"`、`"/proposal"` → `"/proposal/kanban"`。
2. 新規テスト `NotificationLinkRouteTest`（`@WebMvcTest` 全 PageController or `@SpringBootTest`+MockMvc）:
   - 検証対象リンクは本体コードを Grep するのではなく、**定数クラス化**する:
     `common/constant/NotificationLinks.java` に `INVOICE = "/invoice"` 等を集約し、
     publish 側はこの定数を参照。テストは定数クラスの全 public static String フィールドを
     リフレクションで列挙し、認証付き MockMvc GET で 200 を確認する。
   - 以後リンクを追加する際は定数クラスに置く運用（コメントで明記）。

## S4. CONTRACT_END と更新ドラフトの連動

`NotificationGenerateService.contractEnding()`:

```java
// 自動更新ドラフト生成済みの契約は更新手続きが進行中のため通知しない
Set<Long> renewedFromIds = contractMapper.selectList(new QueryWrapper<Contract>()
        .isNotNull("renewed_from_contract_id")
        .select("renewed_from_contract_id"))
        .stream().map(Contract::getRenewedFromContractId).collect(Collectors.toSet());
contracts.removeIf(c -> renewedFromIds.contains(c.getId()));
```

- ドラフト有無の判定は `ContractRenewalServiceImpl.hasExistingDraft` と同じ
  `renewed_from_contract_id` 基準（後日 `engineer-availability-visualization` 実装時も
  この基準を共有すること——requirements S4-3 の申し送り）。
- 論理削除されたドラフトは wrapper が除外するため、ドラフトを削除すれば通知が再開する（正しい挙動）。

## S5. 要員ステータス手動編集ガード

置き場所はサービス層: `EngineerServiceImpl` に `updateWithStatusGuard(Engineer engineer)` を追加し、
`EngineerApiController.update` をこれに差し替える（コントローラに業務ロジックを置かない層規約に従う）。

```java
Engineer old = getById(engineer.getId());
if (old != null && engineer.getStatus() != null && !engineer.getStatus().equals(old.getStatus())) {
    long active = contractMapper.selectCount(... status='稼動中' AND engineer_id=...);
    if ("稼動中".equals(engineer.getStatus()) && active == 0) {
        throw BusinessException.of("error.engineer.statusActiveNoContract");
    }
    if ("Bench".equals(engineer.getStatus()) && active > 0) {
        throw BusinessException.of("error.engineer.statusBenchHasContract");
    }
}
return updateById(engineer);
```

- 「提案中」「退場予定」への変更・ステータス不変の編集はガード対象外（requirements S5-3/S5-4）。
- ステータス定数は既存の文字列リテラル運用に合わせる（`StatusConstants` に要員ステータスが
  未定義なら追加してから使う——実装時に確認）。
- メッセージ2本×4言語追加。
- CSV インポート（`EngineerCsvServiceImpl`）は **新規登録（`save`）のみで更新経路を持たない**ことを
  確認済みのため、本ガードの考慮は不要（将来 CSV 更新を追加する際は `updateWithStatusGuard` を通すこと）。

## S6. 契約削除時の releaseIfIdle

`ContractServiceImpl.removeById` の `super.removeById(id)` 成功後:

```java
boolean removed = super.removeById(id);
if (removed && target.getEngineerId() != null) {
    // 削除で稼動中契約・オープン提案が無くなった場合は Bench へ戻す(成約済み提案は既にクローズ)
    engineerStatusService.releaseIfIdle(target.getEngineerId());
}
return removed;
```

- `releaseIfIdle` は「オープン提案なし かつ 稼動中契約なし」のときだけ Bench 化するため、
  他契約が稼動中なら何もしない（安全）。
- 稼動中契約は元々削除不可なので、`onContractActive` 側の考慮は不要。

## S7. saveHours の期間・状態検証

`WorkRecordServiceImpl.saveHours` の契約取得直後:

```java
YearMonth ym = YearMonth.parse(workMonth);
LocalDate monthStart = ym.atDay(1);
LocalDate monthEnd = ym.atEndOfMonth();
boolean inPeriod = contract.getStartDate() != null && !contract.getStartDate().isAfter(monthEnd)
        && (contract.getEndDate() == null || !contract.getEndDate().isBefore(monthStart));
boolean statusOk = "稼動中".equals(contract.getStatus()) || "終了".equals(contract.getStatus());
if (!inPeriod || !statusOk) {
    throw BusinessException.of("error.workRecord.contractNotBillable");
}
```

- 判定条件は勤怠グリッド（`selectMonthlyGrid` の WHERE）と同一に揃える。
- `workMonth` の形式不正（`YearMonth.parse` 失敗）は `DateTimeParseException` →
  既存の `GlobalExceptionHandler` が 500 で拾うため、`error.workRecord.invalidMonth` で
  明示的に 400 系へ変換する。
- メッセージ2本×4言語追加。

---

## 実装レーン分割（並行可能性）

- **レーンA（営業・ユーザー系）**: S1 + S2。担当: `ContractServiceImpl.createDraftFromProposal` /
  `EngineerSalesService(Impl)` / `EngineerSalesMapper` / `UserApiController` / `EngineerServiceImpl`。
- **レーンB（通知系）**: S3 + S4。担当: `NotificationGenerateService` / 新規 `NotificationLinks` 定数 /
  新規 `NotificationLinkRouteTest`。
- **レーンC（状態・勤怠系）**: S5 + S6 + S7。担当: `EngineerApiController` / `EngineerService(Impl)` の
  ガードメソッド / `ContractServiceImpl.removeById` / `WorkRecordServiceImpl.saveHours`。

**競合**: A と C が `EngineerServiceImpl`（A=removeById、C=updateWithStatusGuard）と
`ContractServiceImpl`（A=createDraftFromProposal、C=removeById）で同一ファイルに触れる。
メソッド単位では交差しないが、**並行させる場合は A→C の順でマージする**か同一セッションで実施する。
B は独立・完全並行可。i18n 4ファイルは全レーンが追記（キー追加のみ、マージ順を決める）。

## テスト方針まとめ

| 対象 | テストクラス | 主ケース |
|---|---|---|
| S1-1 | `ContractServiceImplTest` | 退職主担当→未帰属でドラフト生成成功＋専用通知 / 在職主担当→従来どおり設定 |
| S1-2 | `UserApiControllerTest` | 現任担当ありユーザーの delete/無効化/ロール変更が 4xx / 担当なしは成功 |
| S2 | `EngineerSalesServiceImplTest` + H2統合 | removeById 後に released_at 設定済み / count SQL が削除要員を除外 |
| S3 | `NotificationLinkRouteTest`（新規） | 定数クラス全リンクが 200 |
| S4 | `NotificationGenerateService` のテスト | ドラフト生成済み契約は通知されない / 未生成 auto_renew は通知される |
| S5 | `EngineerServiceImplTest`（新規 or 追記） | 稼動中契約なしで稼動中へ→拒否 / ありで Bench へ→拒否 / 退場予定へ→許可 / status不変→許可 |
| S6 | `ContractServiceImplTest` | 準備中契約削除→releaseIfIdle 呼び出し / engineerId null→呼ばれない |
| S7 | `WorkRecordServiceImplTest` | 期間外月→拒否 / 準備中・解約契約→拒否 / 稼動中・期間内→従来どおり |
