# Review Ledger — 企業認証・セキュリティ（S03）

## 前提修正ゲート（履歴）

S03の開始前レビューで検出された前提実装のP1を修正中。V61/V62はorganization-management-accountingで既に使用済みのため、S03の予約migrationはV63とする。S03自身のOIDC/MFA/permission/file-scan実装は未着手であり、この台帳は開始ゲートと先行差分の検証を追跡する。

| task | requirements | 変更file | test | Demo | 状態 | risk / rollback |
|---|---|---|---|---|---|---|
| pre-S03 P1-1 | R3.1〜R3.3 | `SystemConfigApiController`、`ScopeChangeInvalidator`、scope回帰test | commit/rollback generation、false→true→false、並行commit/rollback | H2で世代・cache遷移を確認 | FIX・定向test済み | rollback callbackはcacheを書き戻さず、後続commitを保持 |
| pre-S03 P1-2 | R2.2、R4 | V62、`EngineerAccountingHistory`、`EngineerMapper`、H2 schema | closed history MySQL fixture、direct-org優先、UNKNOWN未配賦、集計SQL | Docker smokeでV61→V62を再現 | 継続済み、既存回帰維持 | V62適用前バックアップへ戻す。UNKNOWN行は推測補完しない |
| pre-S03 P1-3 | R1.2〜R1.4 | `OrganizationServiceImpl`、組織サービス回帰test | 主所属順序、部分/前段重複、未来開始、有限validTo、同日開始 | H2で統合前後の期間を確認 | FIX・定向test済み | sourceを先に閉鎖/削除し、targetへ未被覆区間だけを移す |
| pre-S03 P1-4 | R1.4、R2.2、R4 | `OrganizationServiceImpl`、`EngineerAccountingHistoryMapper`、回帰test | 同日開始履歴、valid_from <= valid_to | H2で履歴整合性を確認 | FIX・定向test済み | 同日/未来開始は原地更新、過去開始だけ分割する |
| pre-S03 migration ledger | migration ledger | `enterprise-identity-security/tasks.md`、中央conversation/ledger | V63 grep整合 | 台帳の予約番号照合 | 修正済み | V62以降の予約番号を再利用しない |

## 最新判定（2026-07-29 第十八次Review対応）

最新独立Reviewの対象は`origin/main=fb91943`（PR #42 merge済み）で、P0=0/P1=4/P2=3だった。P1-1（cache失効原子性）、P1-2（manager全経路scope）、P1-3（混在組織請求書）、P1-4（NULL業務通知）を`90f50c0`で修正した。定向・全量は883/0/0/1、Node/JS syntax実行済み、Flyway fresh・legacy V60・repair・V62 closed fixture・migration integrity・ConcurrentUpdateは0 skippedで実行済み。混在請求書SQLとNULL通知のlist/count/visible/read-allに実行級回帰を追加した。

`90f50c0`は修正ブランチ上で未mergeであり、merge後独立Reviewは未実施のため、S02は`FIX/REVIEW`、S03は`NOT READY`。次の解放条件は、fix commitのPR merge、merge済みHeadでの独立Review P0=0/P1=0/PASS、中央台帳の再同期である。desktop/390px実ブラウザDemoはS03 blockerではないが、本番前硬门禁として未実施のまま保持する。
