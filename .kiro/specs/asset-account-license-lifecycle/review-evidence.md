# NF-09 独立Review提出用証跡

## 1. 対象とHead

- **Feature**: `asset-account-license-lifecycle` / NF-09
- **Approved scope**: 資産台帳・貸与event/履歴・棚卸し・外部account reference・license管理、所有法人・状態・期間重複・秘密非保存・退社blocker、MDM/IdP正本境界、NF-01 link contract。
- **Owner**: `PROJECT_OWNER`（プロジェクト責任者）
- **Worktree**: `C:\work\ses-asset-account-license-lifecycle`
- **Branch**: `codex/asset-account-license-lifecycle`
- **Base branch**: `origin/main`
- **Base**: `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` (`origin/main`)
- **検証対象実装Head**: `f9dd290ec6dedc8efd46ea77c1bfdd883c5c3255`（実装・テスト修正push後。対象suite 66/66とMySQL 3/3はこの内容で検証）。
- **Remote確認**: 実装修正push直後に `git ls-remote origin refs/heads/codex/asset-account-license-lifecycle` で同じ `f9dd290ec6dedc8efd46ea77c1bfdd883c5c3255` を確認。文書のみの最終push後は、Review開始時に同コマンドで提出Headを再固定する。
- **PR**: 実装対話では未作成。独立ReviewのPLAN/IMPLEMENTATION双方PASS後にのみ作成する。

## 2. DG-09 / NF-01 決定事項

- DG-09 は `DG-09-SCOPE-APPROVAL-20260828-01` で承認済み。リンク契約は未決定ではないため、spec/discovery止まりの条件には該当しない。
- `owner_company_id` は `m_organization_unit.legal_entity_id`。新しい `m_company` は作成していない。
- MDMを端末状態、IdP/SaaSを外部アカウント失効状態の正本とし、DBは参照・要求・確認・再試行の証跡正本とする。
- `RESIGN_ASSET_RETURN` で未返却端末・未失効アカウント・未解放ライセンスをblockし、`LIFECYCLE_EXCEPTION` の承認済み例外だけをWAIVED扱いとする。
- password/token/recovery codeはDB列・Entity/DTO・HTML input・JS payload・ログ・監査payloadへ保存しない。

## 3. 検証結果

### 3.1 NF-09専用Fast suite

実行コマンド:

```text
.\apache-maven-3.9.6\bin\mvn '-Dtest=AssetEntityMapperTest,AssetApiControllerTest,AssetApiRoleScopeIntegrationTest,AssetAssignmentConcurrencyTest,AssetAlertServiceTest,DocumentApiControllerTest,MyAssetApiControllerTest,AssetServiceTest,AssetSecretFieldScanTest,AssetOffboardingServiceTest,AssetComprehensiveSecretScanTest,AssetBoundaryAndLifecycleIntegrationTest,ScheduledMethodsHaveSchedulerLockTest,ActionPermissionResolverTest' test
```

結果: **66/66 PASS, Failures=0, Errors=0, Skipped=0**。

内訳の重要assertion:

- 同一assetの4並行貸与は成功1・拒否3、返却直後の再貸与は成功。
- 法人A/B、営業担当範囲、マネージャー組織範囲、要員本人、空集合fail-closedを確認。
- 実在 `t_document` の `ASSET_ASSIGNMENT` linkについて、無関係要員のdetail/downloadを貸与中・返却後とも403。旧assignmentの本人だけは履歴文書へアクセス可能。
- `AssetComprehensiveSecretScanTest`: **4/4 PASS**。全 `src/main/java` を対象に、multilineのログ・例外・監査payloadを含む未マスク値を検査。
- 退社3大blocker、承認済み例外、棚卸し確定後更新拒否・二重確定拒否、資産・アカウント・ライセンスのsoft-delete安全条件を確認。
- `ScheduledMethodsHaveSchedulerLockTest`: **1/1 PASS**。

### 3.2 MySQL 8 gate

実行コマンド:

```text
.\apache-maven-3.9.6\bin\mvn '-Dtest=AssetMySqlIntegrationTest' test -Pmysql-tests
```

結果: **3/3 PASS, Failures=0, Errors=0, Skipped=0**。MySQL 8 TestcontainersでFlyway V129/V130、`FOR UPDATE`、CAS、貸与履歴を実行。`MySqlTestShardInventoryTest`のshard登録整合も確認済み。

### 3.3 リポジトリ全体Fast gateの扱い

`mvn test` は実行したが、全体PASSではない。NF-09対象テストは通過している一方、次の既存/環境側テストに失敗・エラーが残ったため、CR-06のリポジトリ全体Fast gateは **未PASS** と記録する。

- `ControllerTransactionalBanTest`
- `TransactionalRollbackForAuditTest`
- `ProductionSecurityConfigurationTest`
- `PrometheusScraperLabE2ETest`（loopback接続環境エラー）
- `CapacityBaselineScriptTest`
- `ProjectSkillServiceImplTest`（random order下の固定H2 ID競合）
- `WebhookNotifierLoopbackIntegrationTest`

この結果をNF-09対象suiteのPASSに混入させず、独立Reviewでベースライン差分として確認する。

## 4. Reconciliation / 未返却一覧

### 4.1 テストfixtureの照合値

`AssetBoundaryAndLifecycleIntegrationTest` の棚卸し証跡:

```text
totalAssets=3, matched=1, discrepancy=0, missing=2
```

これはH2 test fixtureの結果であり、本番件数ではない。本番リリース前は [runbook.md](runbook.md) の読み取り専用SQLで状態別件数、未返却、失効未確認、未解放ライセンスを再照合する。

### 4.2 Reviewが再実行する未返却一覧

```sql
SELECT a.id, a.asset_tag, a.asset_name, a.status,
       aa.assignee_type, aa.assignee_id, aa.start_date,
       aa.expected_return_date, aa.actual_return_date
FROM m_asset a
JOIN t_asset_assignment aa ON aa.asset_id = a.id
WHERE a.deleted_flag = 0
  AND aa.deleted_flag = 0
  AND aa.status IN ('ACTIVE', 'OVERDUE')
  AND aa.actual_return_date IS NULL
ORDER BY aa.expected_return_date, a.asset_tag;
```

件数照合は `m_asset` の有効状態別合計と上記未返却一覧を突合し、差異があれば棚卸し差異として解消するまで廃棄・再貸与・退社完了を止める。

## 5. 外部失効と履歴保全

- `revoke_requested_at` と `revoke_confirmed_at` を別々に保持し、要求送信のみで `REVOKED` にしない。
- `FAILED_OR_TIMEOUT` は `PENDING_CONFIRMATION` のまま `next_retry_at` によるポーリングへ送り、確認成功時だけ `REVOKED` とする。
- 資産の移管・返却・紛失・廃棄は `t_asset_event` へINSERT-onlyで追記する。貸与・アカウント・ライセンスの履歴も状態を上書きせず、論理削除は未完了状態を回避してから行う。
- 外部プロバイダ呼出しはDBトランザクション外。要求記録と確認結果の間で障害が発生しても、未確認を成功として扱わない。

## 6. Secret scan

`AssetComprehensiveSecretScanTest` **4/4 PASS**。検査対象はEntity/DTO、V129/V130/V1のDDL、HTML、JS、`src/main/java` 全Javaのログ・例外・監査payload。アカウント識別子は必要箇所でマスクし、password/token/recovery code用のcolumn/DTO/log値は検出なし。

## 7. Rollback / 運用手順

- 運用手順、初期移行、reconciliation、未返却一覧、外部失効timeout、棚卸し差異、backup/restore、前方互換移行、隔離検証DB限定のDDL削除境界は [runbook.md](runbook.md) に記載。
- 本番通常ロールバックでDROPせず、書き込み停止・バックアップとFlyway履歴保存・別名DBリストア検証・承認済み切替を行う。
- `t_asset_event` 等の監査履歴をロールバックで物理削除しない。

## 8. Review状態

本ファイル、`requirements.md`、`design.md`、`inventory.md`、`tasks.md`、`review-ledger.md`、`runbook.md` を同一branchで提出する。独立Review（PLAN / IMPLEMENTATION）は実装エージェントでは実施しておらず、現在 **PENDING**。PASS判定前のPRは作成していない。
