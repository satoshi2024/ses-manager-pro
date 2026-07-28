# Review Ledger — 企業認証・セキュリティ（S03）

## 2026-07-28 前提修正ゲート

S03の開始前レビューで検出された前提実装のP1を修正中。V61/V62はorganization-management-accountingで既に使用済みのため、S03の予約migrationはV63とする。S03自身のOIDC/MFA/permission/file-scan実装は未着手であり、この台帳は開始ゲートと先行差分の検証を追跡する。

| task | requirements | 変更file | test | Demo | 状態 | risk / rollback |
|---|---|---|---|---|---|---|
| pre-S03 P1-1 | R3.1〜R3.3 | `SystemConfigApiController`、`ScopeChangeInvalidator`、scope回帰test | commit/rollback generation、false→true→false | H2で世代遷移を確認 | FIX・定向test済み、pushed branch tip | 設定変更を戻し、generation依存キャッシュを再生成 |
| pre-S03 P1-2 | R2.2、R4 | V62、`EngineerAccountingHistory`、`EngineerMapper`、H2 schema | closed history MySQL fixture、direct-org優先、UNKNOWN未配賦、集計SQL | Docker smokeでV61→V62を再現 | FIX・Docker fixture済み、pushed branch tip | V62適用前バックアップへ戻す。UNKNOWN行は推測補完しない |
| pre-S03 P1-3 | R1.2〜R1.4 | `OrganizationServiceImpl`、組織サービス回帰test | 部分重複、未来開始、有限validTo、属性分段、同日開始 | H2で統合前後の期間を確認 | FIX・定向test済み、pushed branch tip | merge transactionをロールバックし、sourceを無効化のみで保持 |
| pre-S03 P1-4 | migration ledger | `enterprise-identity-security/tasks.md`、中央conversation/ledger | V63 grep整合 | 台帳の予約番号照合 | 修正済み | V62以降の予約番号を再利用しない |

## 判定（2026-07-28 更新）

先行差分のP1=0を定向38/38・全量869/0/0/1、最終修正影響単体7/0/0/0、Node 1/0/0/0・Docker 5門禁0 skipで確認した（レポート合算870/0/0/1）。V63のS03予約はtasks/spec-start/spec-review/copyable/中央台帳で一致しているため、ユーザーの開始指示を受けS03をREADYとする。修正差分は pushed branch tip にcommit済みだが、gh未導入のためpush/PR mergeとmerge後独立Reviewは未完了であり、S03のPASSとは記録しない。desktop/390pxブラウザDemoは本番前硬门禁として未実施のまま管理する。
