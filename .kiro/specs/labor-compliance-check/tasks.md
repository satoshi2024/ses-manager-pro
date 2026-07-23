# Tasks — 偽装請負・多重派遣リスクチェック（FR-10）

ルール範囲は着手前に発注者合意（requirements ※要確認）。

- [ ] F1. 設定＋必要な軽い列
  - **Objective**: `compliance.*`（max-tier, 各ルール有効フラグ）を `m_system_config` に、必要なら `Contract.direct_command_flag` を V4x で追加。
  - **Demo**: system-config で閾値/有効を設定できる。

- [ ] A. ルールエンジン
  - **Objective**: `LaborComplianceService.check(contract)` で findings。
  - **実装ガイダンス**: design 1章。段数/偽装請負/二重派遣/実態不整合。設定で有効・閾値。
  - **テスト要件**: `LaborComplianceServiceTest`（各ルール該当/非該当/無効化）。
  - **Demo**: 該当契約で findings が返る。

- [ ] B. 呼び出し・記録・締め連携
  - **Objective**: 契約登録/更新で警告＋AuditLog、月次締めチェックリストに項目追加。
  - **実装ガイダンス**: design 2章。ブロックしない。
  - **テスト要件**: 更新時に finding が AuditLog へ、締めで未確認提示。
  - **Demo**: リスク契約保存で警告バナー＋監査記録。

- [ ] C. 管理者リスク一覧画面
  - **Objective**: `/api/compliance/findings` と一覧UI。管理者/マネージャー限定。
  - **Demo**: リスク該当契約が一覧表示。

- [ ] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 契約構造がルールに触れると警告＆監査記録され、締めチェックリストに現れる。
- 各ルールの該当/非該当/無効化テストが緑。ブロックはしない。
</content>
