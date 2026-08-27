# OIDC binding 隔離カットオーバーと再承認（REV-P0-001 / REV-P1-010）

本 runbook は、V110（外部 ID 隔離 + 管理者硬境界; lifecycle は V109）を本番へ載せるときの
**切り替え条件**と、隔離後の **再承認手順**を定義する。

旧 jar は `review_status` を見ない。ローリング中に旧ノードが OIDC を受けると
パッチ前のアカウント乗っ取り経路が再発する。したがって本切り替えは
**IdP/OIDC 停止 → 旧ノード排空 → V110 成功 → 新バージョンのみ起動**の順で行う。

## 0. 禁止事項

- pre-fix（V110 未満 / `review_status` 非対応）jar へのロールバック禁止
- OIDC を有効にしたまま新旧混在で受けること禁止
- inventory 未署名のまま「全 binding を一括 APPROVED」すること禁止

## 1. 切り替え前ゲート

1. 修正 commit が CI（Temurin 21）で fast / mysql / performance / browser / backup を通過していること
2. `FlywayV110AdminBoundaryUpgradeSmokeTest` が緑であること
3. 本番 DB のフルバックアップと binlog 位置を記録していること
4. break-glass 管理者（ローカルログイン）が有効で、OIDC 無しでも管理コンソールへ入れること
5. IdP 側で client を一時 disable、またはロードバランサで `/oauth2/**` `/login/oauth2/**` を遮断できること

## 2. カットオーバー手順

```text
1. IdP disable または OIDC 入口遮断
2. 全アプリノードを停止（インフライトリクエスト排空）
3. 新 jar をデプロイし、Flyway で V110 まで migrate（成功のみ前進）
4. 検証 SQL（下記）を実行し、live binding が inventory に揃い全て QUARANTINED であることを確認
5. 新ノードのみ起動（旧 jar を残さない）
6. ローカル管理者（または break-glass）でログインし、再承認を開始
7. 必須 binding の再承認が終わってから IdP / OIDC 入口を復帰
```

### 必須の機械可読エビデンス（完了条件に添付）

切り替え完了時に、次を **同一ディレクトリへ保存し SHA-256 を記録**する。checkbox だけでは閉じない。

| ファイル（例） | 内容 |
|---|---|
| `artifact-digest.txt` | デプロイした jar / image の digest（例: `sha256:...`）と git commit |
| `lb-backends.txt` | LB / service discovery の backend 一覧（ホスト・ポート・target group） |
| `node-inventory.txt` | 実行中アプリノード一覧と起動中の artifact digest |
| `old-nodes-zero.txt` | 「旧 digest を持つノード数 = 0」の照会出力 |
| `autoscaling-disabled.txt` | autoscaling / 自動 rollback controller の停止確認 |
| `runtime-probe.json` | `/actuator/info` または同等の実行中 version / commit プローブ |
| `approval-drill.log` | ローカル管理者で 1 件以上の再承認 API が成功し、`OIDC_BINDING_APPROVED` が残った記録 |

### 検証 SQL（例）

```sql
-- APPROVED なのに reviewer が空の行が無いこと（CHECK とアプリの二重確認）
SELECT COUNT(*) AS non_approved
FROM t_user_external_identity
WHERE deleted_flag = 0 AND review_status = 'APPROVED'
  AND (reviewed_at IS NULL OR reviewed_by IS NULL);
-- 期待: 0

-- live binding と inventory の突き合わせ（deleted_flag=0 で揃える。総数の単純比較はしない）
SELECT COUNT(*) AS live_missing_inventory
FROM t_user_external_identity e
LEFT JOIN t_oidc_binding_review_inventory i ON i.binding_id = e.id
WHERE e.deleted_flag = 0
  AND i.binding_id IS NULL;
-- 期待: 0

SELECT COUNT(*) AS quarantined_live
FROM t_user_external_identity e
WHERE e.deleted_flag = 0
  AND e.review_status = 'QUARANTINED';
```

## 3. 再承認手順（オペレーション）

UI の inventory 画面は本バッチ範囲外。当面は inventory + API で実施する。

1. ローカル管理者でログインする（OIDC はまだ遮断、または未復帰）
2. inventory をエクスポートする:

```sql
SELECT i.binding_id, i.tenant_id, i.provider_id, i.subject_sha256, i.user_id, i.user_role,
       i.linked_at, e.review_status, i.inventory_reason, i.created_at
FROM t_oidc_binding_review_inventory i
JOIN t_user_external_identity e ON e.id = i.binding_id
WHERE e.deleted_flag = 0
ORDER BY i.tenant_id, i.user_role, i.binding_id;
```

3. 対象ユーザーの **正しい OIDC subject** を IdP 側で確認する（email 自動 link 禁止）
4. 管理者が明示承認する:

```http
POST /api/identity-providers/{providerId}/external-identities
Content-Type: application/json
X-XSRF-TOKEN: <token>

{
  "userId": <sys_user.id>,
  "subject": "<IdP subject>",
  "emailSnapshot": "<optional>"
}
```

5. 成功後、次を確認する:
   - `t_user_external_identity.review_status='APPROVED'` かつ `reviewed_by` / `reviewed_at` 非 NULL
   - `t_audit_log.application_code='OIDC_BINDING_APPROVED'` が **追記**されている（上書きではない）
   - URI に binding ID / subjectSha256 / reviewerId / from→to が含まれる
6. チェックリストへ署名（tenant / binding_id / subject_sha256 / user_id / reviewer / 時刻）を残す

## 4. break-glass 経路

OIDC 全隔離後も管理者が入れるよう、切り替え中は **ローカル管理者または break-glass** を使う。

- `app.security.local-login-enabled` / break-glass 設定が prod で意図どおり有効か事前確認
- break-glass は最小 action（dashboard 等）に限定されている場合、identity-provider 承認に足りないことがある
  → その場合は通常のローカル `管理者` を使う（break-glass の scope を安易に広げない）

## 5. ロールバック方針

- **アプリのみ**の問題で DB が V110 済みなら、同じ V110 対応 jar のホットフィックスへ前進する
- pre-V110 jar へのダウングレードは禁止（隔離状態を無視して再ログイン可能になる）
- DB を V108.3 相当へ戻す必要がある場合は、フルバックアップからの復元 drill（`ops/backup/runbooks/restore-cutover.md`）に従う。V110 の down migration は存在しない

## 6. 完了条件

完了は checkbox だけでは閉じない。下記 **7 ファイル全部** と `evidence-manifest.sha256` が同一ディレクトリに揃い、実行人と独立したレビュー担当がそれぞれ署名するまで HOLD。

- [ ] OIDC 入口遮断中に V110 migrate 成功
- [ ] 必須7ファイルが同一ディレクトリに保存済み:
  - `artifact-digest.txt`
  - `lb-backends.txt`
  - `node-inventory.txt`
  - `old-nodes-zero.txt`
  - `autoscaling-disabled.txt`
  - `runtime-probe.json`
  - `approval-drill.log`
- [ ] `evidence-manifest.sha256` を生成済み（各ファイルの SHA-256 を列挙。生成例: `Get-FileHash` / `sha256sum`）
- [ ] 実行人と独立したレビュー担当が、manifest と7ファイルを突合して署名
- [ ] live binding がログイン可能になるのは `APPROVED` + reviewer 付きのみ
- [ ] 必須ユーザーの再承認と `OIDC_BINDING_APPROVED` 追記監査、署名済み inventory が保管済み
- [ ] IdP 復帰後、隔離中 subject はログイン拒否、再承認済みのみ成功
