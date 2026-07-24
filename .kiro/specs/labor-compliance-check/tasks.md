# Tasks — 偽装請負・多重派遣リスクチェック（FR-10）

ルール範囲は着手前に発注者合意（requirements ※要確認）。

- [x] F1. 設定＋必要な軽い列
  - **Objective**: `compliance.*`（max-tier, 各ルール有効フラグ）を `m_system_config` に、必要なら `Contract.direct_command_flag` を V4x で追加。
  - **Demo**: system-config で閾値/有効を設定できる。
  - **実装メモ**: V50 で `t_contract.direct_command_flag` 追加＋`compliance.max-tier`/`compliance.rule.*.enabled` を seed。既存の system-config 画面(`SystemConfigServiceImpl.SCHEMAS`)にキー追加のみで編集UIは汎用のため変更不要。

- [x] A. ルールエンジン
  - **Objective**: `LaborComplianceService.check(contract)` で findings。
  - **実装ガイダンス**: design 1章。段数/偽装請負/二重派遣/実態不整合。設定で有効・閾値。
  - **テスト要件**: `LaborComplianceServiceTest`（各ルール該当/非該当/無効化）。
  - **Demo**: 該当契約で findings が返る。
  - **実装メモ**: `service/compliance/LaborComplianceService`+`Impl`。段数は `t_bp_payment.layer_order` の最大値(work_record経由)を導出、新規テーブルなし。`LaborComplianceServiceImplTest` で10ケース検証済み。

- [x] B. 呼び出し・記録・締め連携
  - **Objective**: 契約登録/更新で警告＋AuditLog、月次締めチェックリストに項目追加。
  - **実装ガイダンス**: design 2章。ブロックしない。
  - **テスト要件**: 更新時に finding が AuditLog へ、締めで未確認提示。
  - **Demo**: リスク契約保存で警告バナー＋監査記録。
  - **実装メモ**: `ContractServiceImpl.saveWithBusinessRules/updateWithBusinessRules` が findings を返し、該当時は `AuditLogService.record(...)`（applicationCode=`compliance:<CODE,...>`, successFlag=false）で記録。`ContractApiController` が message+data(complianceFindings) で返し、契約フォームに警告バナー(`#complianceWarning`)を表示。`MonthlyClosingSummaryDto.complianceFindings/complianceCount` を追加し締め一覧に「コンプライアンスリスク」カードを追加（締めはブロックしない）。

- [x] C. 管理者リスク一覧画面
  - **Objective**: `/api/compliance/findings` と一覧UI。管理者/マネージャー限定。
  - **Demo**: リスク該当契約が一覧表示。
  - **実装メモ**: `ComplianceApiController`(`/api/compliance/findings`) + `/compliance` ページ(`templates/compliance/list.html`, `compliance.js`)。`m_menu`(`compliance`)を管理者・マネージャーのみ`t_role_menu`に割当て、既存の`MenuPermissionFilter`で他ロールを403遮断（追加のコード不要）。

- [x] M. i18n・仕上げ（5ロケール、全量緑）。
  - **実装メモ**: `messages.properties`(ja)/`messages_en`/`messages_ko`/`messages_zh_CN` にキー追加（`MessageBundleConsistencyTest` で整合性を確認、全テストグリーン）。

## 完了条件
- 契約構造がルールに触れると警告＆監査記録され、締めチェックリストに現れる。
- 各ルールの該当/非該当/無効化テストが緑。ブロックはしない。
</content>
