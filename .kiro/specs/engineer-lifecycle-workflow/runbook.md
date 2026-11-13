# 運用手順書 (Runbook) : ライフサイクルワークフロー & 退社統制ゲート

## 1. 概要とアーキテクチャ概要

本手順書は、SES Manager Pro における要員ライフサイクルワークフロー（入社・配属・異動・休職・復職・退社）および退社統制ゲート（Resignation Gate）の運用、障害発生時の切り分け、およびリカバリ手順を定義する。

---

## 2. 退社統制ゲート（9項目）の運用基準

退社案件（`RESIGNATION`）の完了確定（`POST /api/lifecycle/cases/{id}/complete`）は、以下の9項目がすべて充足されている場合にのみ実行可能となる。

| No | 項目コード | 項目名 | 自動/手動 | 判定基準・アクション |
|:---|:---|:---|:---|:---|
| 1 | `USER_DEACTIVATION` | ログインアカウント無効化 | 自動実行 | 案件完了時に `sys_user.status = 0` に自動更新。アカウント未連携の場合は対象なし（自動PASS）。 |
| 2 | `SESSION_REVOCATION` | 全セッション強制失効 | 自動実行 | `PersistentSessionService` および `PortalSessionService` による全セッション即時破棄（Fail-Closed）。 |
| 3 | `PORTAL_UNLINK` | 要員ポータル連携解除 | 自動実行 | `EngineerAccountLinkService.unlinkByEngineerId` による連携解除。ポータル連携なしの場合は対象なし（自動PASS）。 |
| 4 | `SALES_RELEASE` | 担当営業割当の解除 | 自動実行 | `EngineerSales` の全アクティブ割当の `released_at` に基準日を設定。割当なしの場合は対象なし（自動PASS）。 |
| 5 | `ORG_ASSIGNMENT_CLOSE` | 組織所属の終了 | 自動実行 | `OrganizationService.closeAssignmentsForUser` による組織配属終了。 |
| 6 | `ASSET_RETURN` | 貸与資産の返却 | 手動タスク | `RESIGN_ASSET_RETURN` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 7 | `UNSETTLED_EXPENSE` | 立替経費・精算確認 | 手動タスク | `t_expense_request` で対象要員の未精算件数を確認。未精算0件 → 自動PASS。未精算あり → `RESIGN_EXPENSE_SETTLE` タスクが承認済み `WAIVED` でなければFAIL。 |
| 8 | `DOCUMENT_RETENTION` | 退職関連文書の保管確認 | 手動タスク | `RESIGN_DOC_RETENTION` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 9 | `ACTIVE_CONTRACT` | 稼働中契約の終了確認 | 手動確認 | `t_contract` で対象要員の `status='稼動中'` の件数を確認。残存している場合はゲートFAIL（契約を終了または解約してから退社を完了）。 |

---

## 3. 例外免除フロー（ApprovalEngine 連携）

業務上のやむを得ない理由（例: PCの郵送返却待ち、役員特例承認など）により阻害タスクを一時的に免除する場合：

1. **申請手順**:
   - 画面または API より例外申請（`RequestType = "LIFECYCLE_EXCEPTION"`）を起票。
   - **必須入力項目**:
     - `reason`: 免除理由（詳細）
     - `remedy_deadline`: 是正完了期限（将来日付）
     - `risk_owner`: リスク所有者・承認責任者（氏名/役職）
2. **承認権限**:
   - HR責任者または役員ロールによる多段承認。
3. **自動反映**:
   - 承認完了時、`LifecycleExceptionApprovalAdapter.applyApproved` が自動呼出され、該当タスクが `WAIVED` に遷移し `approval_request_id` が記録される。
   - ※ 直接 API による WAIVE はシステム的に遮断（SoD 担保）。

---

## 4. 障害時の切り分けとリカバリ手順

### 4.1 案件完了時の 400 Bad Request（ゲート未充足）
- **原因**: 必須タスクの未完了、未精算経費の存在、または稼働中契約の残存。
- **対応**:
  1. `/lifecycle/{id}` の退社ゲートチェックリストを確認。
  2. 警告が出ている手動タスク（資産返却・経費精算・書類保管）の進捗を確認し、完了報告または例外承認申請を実施。

### 4.2 案件完了時の 409 Conflict（楽観ロック競合）
- **原因**: 他の管理者が同時にタスク進捗や案件状態を更新した。
- **対応**:
  1. 画面を再読み込み（F5）し、最新状態を取得。
  2. ゲート項目を再確認した上で、再度「完了確定」ボタンを押下。

### 4.3 自動クリーンアップ処理の例外発生（Fail-Closed）
- **動作**: セッション失効や組織閉鎖で予期せぬエラーが発生した場合、トランザクション全体がロールバックされ、案件は `ACTIVE` のまま維持される（不整合防止）。
- **対応**:
  1. 監査ログ（`/audit-log`）およびシステムエラーログを確認。
  2. 関連するユーザーアカウントやポータルユーザーの整合性を修復後、再度完了確定を実行。

---

## 5. NF01 公開前の検査と運用（N1-12）

NF01（機能スイッチ `FEATURE_NF01_ENABLED`、既定OFF）はCONFIRM完成後に公開する（決定 B-NF01-1）。本章は公開前の読取検査（read-only preflight）の読み方と、V187（L7 重複退社の一意制約）・証跡の版指定（N1-3）・二者確認テンプレートの拒否（N1-2）の運用手順を定める。

### 5.1 read-only preflight の実行と判定

- 実行: `scripts/nf01-readonly-preflight.ps1`（手順・環境変数は `scripts/nf01-readonly-preflight.md`）。SELECTとmetadataだけを読み、DBを変更しない。読取専用accountで実行する。
- 結果は `nf01-preflight.json` / `.md` の `status` で判定する。

| status | exit code | 意味 |
|:---|:---|:---|
| `PASS` | 0 | 全検査で阻害なし |
| `BLOCKED_MAPPING` | 2 | データ不整合ではないが、人の判断（対応付け・移行方針・終結させる案件の選択）が要る項目がある |
| `FAIL` | 2 | データ不整合・schema不足・読取失敗。`BLOCKED_MAPPING` より優先して表示される（FAILを隠さない） |

- **exit code は `BLOCKED_MAPPING` と `FAIL` で同じ 2 になる**。CIやjobはexit codeだけで成否を判断せず、必ずjsonの `status` と各 `findings[].status` を読む。

### 5.2 データのある環境では BLOCKED_MAPPING（exit 2）になる

業務データ（組織所属・既存案件・証跡）を持つ環境では、preflightはほぼ確実に `BLOCKED_MAPPING`（exit 2）になる。空DBへの新規導入直後でも、V60が作る移行組織 `LEGACY`（`m_organization_unit.tenant_id` がNULL）と、その所属行によって `ORG_UNIT_TENANT_NULL` / `ORG_UNIT_TENANT_MAPPING` が `BLOCKED_MAPPING` になる。**exit 2 自体を障害として扱わず、次の手順で項目ごとに判断を記録する。**

1. jsonの `findings` から `status` が `FAIL` の項目を先に抽出する。1件でもあれば作業を止め、DBAとデータ修復方針を決める（`BLOCKED_MAPPING` の判断より先）。
2. `BLOCKED_MAPPING` の各項目について、下表の担当者が対応を決める。samplesは表名と主キーから作った固定の仮名（SHA-256由来）であり、実キーはDBAが読取専用SQLで別途確認する。
3. 対応を実施したら preflight を再実行し、残った `BLOCKED_MAPPING` が「判断済みで許容する項目」だけであることを確認する。許容した項目は、checkId・rowCount・判断者・日付・reportの `sourceSha256` を変更記録に残す。
4. DBの手修正はしない（アプリの操作・承認済みmigrationだけで直す）。

| checkId | 担当 | 対応 |
|:---|:---|:---|
| `ORG_UNIT_TENANT_NULL` / `ORG_UNIT_TENANT_MAPPING` | DBA・Security | 組織の数値tenantと文字列tenantの対応を決める。V60の `LEGACY` 組織だけなら新規導入として許容してよい（数値→文字列の自動変換はしない） |
| `TENANT_PARENT_CANDIDATES` | DBA | 親行からtenantを一意に決められない行を確認し、対応付けを決める |
| `LIFECYCLE_DUAL_TASK_UNFINISHED` | HR | 未終結の二者確認タスクを免除（承認済み例外）・再提出のどちらで扱うか決める（5.4） |
| `LIFECYCLE_EVIDENCE_VERSIONLESS` | 文書owner | 版を固定していない既存証跡の扱いを決める（5.4） |
| `LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE` | HR | **0件にするまでV187を適用しない**（5.3） |

### 5.3 L7: 同一要員の未終結退社案件は1件まで（V187）

- 規則: 同一tenantの同一要員について、未終結（`COMPLETED` / `CANCELLED` 以外）かつ未削除の退社（`RESIGNATION`）案件は1件だけ。V187の生成列 `active_resignation_engineer_id` と一意index `uk_lifecycle_case_active_resignation (tenant_id, active_resignation_engineer_id)` で保証する。
- アプリの起票前確認（`LifecycleCaseServiceImpl.createCase`）はlocking readではないため、並行起票は最後にDBの一意制約で止まり、どちらの経路でも `400 error.lifecycle.duplicateActiveCase` を返す。2件目を起票したい場合は、先の案件を完了または中止する。
- **適用前（データのある環境）**:
  1. preflightの `LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE` を確認する。比較は一意indexと同じく列のcollationで行う（大文字小文字・末尾空白を畳む）。
  2. 0件でなければ、DBAが読取専用SQLで重複組（tenant・要員・案件ID・状態）を一覧し、HRが残す案件を1件選ぶ。
     ```sql
     SELECT tenant_id, engineer_id, GROUP_CONCAT(id ORDER BY id) AS case_ids, GROUP_CONCAT(status ORDER BY id) AS statuses
       FROM t_lifecycle_case
      WHERE lifecycle_type = 'RESIGNATION' AND status NOT IN ('COMPLETED', 'CANCELLED') AND deleted_flag = 0
      GROUP BY tenant_id, engineer_id HAVING COUNT(*) > 1;
     ```
  3. 残さない案件は画面またはAPI（`POST /api/lifecycle/cases/{id}/cancel`、理由を記入）で中止する。DBの直接更新はしない。
  4. preflightを再実行し、`LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE` が `PASS`（0件）になってからmigrationを適用する。
- **重複が残ったまま適用した場合**: V187は重複エラー（MySQL 1062）で失敗し、Flywayに失敗行が残る（InnoDBのALTERは原子的で、列もindexも追加されない）。手順2〜4で重複を解消し、変更承認を得てから `flyway repair` で失敗行を消して再適用する。新規導入（空DB）の場合は deploy-runbook §3.5 に従いschemaを作り直す。
- 本番は空DBへの新規導入のため、適用時の重複は0件である。

### 5.4 証跡の版指定（N1-3）と二者確認テンプレート（N1-2）

- **版の指定は必須**: タスク完了時に証跡文書を付ける場合、版ID（`documentVersionId`）が必須で、版を固定しない（versionless）証跡は `400 error.lifecycle.evidenceVersionRequired` で拒否する。版はactorのtenantかつ同じ文書の版で、scan結果が `CLEAN` のものに限る（`CLEAN` 以外は `400 error.lifecycle.evidenceVersionNotClean`）。
- **画面**: 完了モーダルで文書IDを入力して「版を読み込む」を押すと、その文書の `CLEAN` 版が版番号の降順で表示され、選んだ版のIDを送る。一覧APIは `GET /api/lifecycle/tasks/{id}/evidence-versions?documentId=` と本人用 `GET /api/my/lifecycle/tasks/{id}/evidence-versions?documentId=`。完了操作と同じtenant・権限・本人scopeで判定し、他tenant・存在しない文書は区別せず404、版ID・版番号・登録日時以外（元ファイル名・storage key・hash）は返さない。
- **拒否を今から有効にした理由**: 決定は「CONFIRM公開時にversionlessを拒否」である。NF01はCONFIRM完成後に公開し（B-NF01-1）、機能スイッチも既定OFFのため、公開前に拒否を始めても利用者の業務は止まらない。既存テストの業務フロー（退社ゲートの文書保管タスク等）は版を選ぶ形に置き換えるだけで成立することを確認した。公開時に切り替えるための別スイッチを持たないことで、切替漏れのまま公開する危険を無くす。
- **既存のversionless証跡**: preflightの `LIFECYCLE_EVIDENCE_VERSIONLESS` が件数を示す。証跡linkを書き換えるAPIは無いため、文書ownerが「当該タスクを訂正記録（`POST /api/lifecycle/tasks/{id}/correct`）で版を追記する」か「現状のまま許容する」かを決め、5.2の手順で記録する。
- **二者確認（DUAL_CONFIRMATION）**: CONFIRM公開前は、DUAL_CONFIRMATIONタスクを含むテンプレートの新規作成・改定を `400 error.lifecycle.dualConfirmationNotAvailable` で拒否する（表記揺れ・前後空白も拒否）。公開前から残るDUALタスクは通常の完了経路では完了できない（409）ため、preflightの `LIFECYCLE_DUAL_TASK_UNFINISHED` を見てHRが免除または再作成を決める。
