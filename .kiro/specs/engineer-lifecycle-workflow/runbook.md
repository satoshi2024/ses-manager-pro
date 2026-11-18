# 運用手順書 (Runbook) : ライフサイクルワークフロー & 退社統制ゲート

## 1. 概要とアーキテクチャ概要

本手順書は、SES Manager Pro における要員ライフサイクルワークフロー（入社・配属・異動・休職・復職・退社）および退社統制ゲート（Resignation Gate）の運用、障害発生時の切り分け、およびリカバリ手順を定義する。

---

## 2. 退社統制ゲート（10項目）の運用基準

退社案件（`RESIGNATION`）の完了確定（`POST /api/lifecycle/cases/{id}/complete`）は、以下の10項目がすべて充足されている場合にのみ実行可能となる。

| No | 項目コード | 項目名 | 自動/手動 | 判定基準・アクション |
|:---|:---|:---|:---|:---|
| 1 | `USER_DEACTIVATION` | ログインアカウント無効化 | 自動実行 | 案件完了時に `sys_user.status = 0` に自動更新。アカウント未連携の場合は対象なし（自動PASS）。 |
| 2 | `SESSION_REVOCATION` | 全セッション強制失効 | 自動実行 | `PersistentSessionService` による内部セッションの即時破棄（Fail-Closed）。ポータルセッションは10の紐付け済みユーザーだけを対象にする。 |
| 3 | `PORTAL_UNLINK` | 要員ポータル連携解除 | 自動実行 | `EngineerAccountLinkService.unlinkByEngineerId` による連携解除。ポータル連携なしの場合は対象なし（自動PASS）。 |
| 4 | `SALES_RELEASE` | 担当営業割当の解除 | 自動実行 | `EngineerSales` の全アクティブ割当の `released_at` に基準日を設定。割当なしの場合は対象なし（自動PASS）。 |
| 5 | `ORG_ASSIGNMENT_CLOSE` | 組織所属の終了 | 自動実行 | `OrganizationService.closeAssignmentsForUser` による組織配属終了。 |
| 6 | `ASSET_RETURN` | 貸与資産の返却 | 手動タスク | `RESIGN_ASSET_RETURN` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 7 | `UNSETTLED_EXPENSE` | 立替経費・精算確認 | 手動タスク | `t_expense_request` で対象要員の未精算件数を確認。未精算0件 → 自動PASS。未精算あり → `RESIGN_EXPENSE_SETTLE` タスクが承認済み `WAIVED` でなければFAIL。 |
| 8 | `DOCUMENT_RETENTION` | 退職関連文書の保管確認 | 手動タスク | `RESIGN_DOC_RETENTION` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 9 | `ACTIVE_CONTRACT` | 稼働中契約の終了確認 | 手動確認 | `t_contract` で対象要員の `status='稼動中'` の件数を確認。残存している場合はゲートFAIL（契約を終了または解約してから退社を完了）。 |
| 10 | `PORTAL_ACCOUNT_LINK` | ポータルアカウントの紐付け確認 | 自動実行 | 管理者が登録した紐付け（V188、5.5）のあるポータルユーザーだけ `PortalSessionService` でセッションを失効させる。emailが一致しても紐付けの無いポータルユーザーは自動処理せず「要確認」（`pendingConfirmation`・`pendingPortalUserIds`）として表示し、ブロックはしない。紐付けのtenant不整合はFAILで、完了確定も409になる。 |

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
| `ORG_UNIT_TENANT_NULL` / `ORG_UNIT_TENANT_MAPPING` | DBA・Security | 組織の数値tenantと文字列tenantの対応を決める。V60の `LEGACY` 組織だけなら新規導入として許容してよい（数値→文字列の自動変換はしない）。V189（epoch A）の適用は止めない（組織epochはtenantに依存しない。5.6） |
| `TENANT_PARENT_CANDIDATES` | DBA | 親行からtenantを一意に決められない行を確認し、対応付けを決める |
| `LIFECYCLE_DUAL_TASK_UNFINISHED` | HR | 未終結の二者確認タスクを免除（承認済み例外）・再提出のどちらで扱うか決める（5.4） |
| `LIFECYCLE_EVIDENCE_VERSIONLESS` | 文書owner | 版を固定していない既存証跡の扱いを決める（5.4） |
| `LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE` | HR | **0件にするまでV187を適用しない**（5.3） |
| `PORTAL_ENGINEER_LINK_CANDIDATES` | 管理者 | 旧email照合なら退社時に自動処理されていたが紐付けの無いポータルユーザー。V188適用後にポータル管理で紐付けを登録するか、紐付けない（要確認で個別対応する）ことを記録する（5.5） |
| `PORTAL_ENGINEER_LINK_SCOPE_MISMATCH`（FAIL） | 管理者・DBA | tenant不整合・参照先欠落の現行紐付け。退社完了が409で止まるため、紐付けを解除して正しいtenantで登録し直す（5.5） |
| `AUTHORIZATION_EPOCH_COLUMN` / `AUTHORIZATION_GLOBAL_EPOCH` / `AUTHORIZATION_EPOCH_APPLICATION`（FAIL） | DBA | V189（epoch A）の前提と適用状態。FAILは同名CHECK制約の衝突、列定義・CHECKの不一致、全体epoch行の欠落・複数行、一部だけの適用（失敗したmigrationの残骸）のいずれか。DBを手で直さず、新規導入はschemaを作り直す（5.6） |

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
- **二者確認（DUAL_CONFIRMATION）**: CONFIRM公開前は、DUAL_CONFIRMATIONタスクを含むテンプレートの新規作成・改定を `400 error.lifecycle.dualConfirmationDisabled` で拒否する（表記揺れ・前後空白も拒否）。公開前から残るDUALタスクは通常の完了経路では完了できない（409）ため、preflightの `LIFECYCLE_DUAL_TASK_UNFINISHED` を見てHRが免除または再作成を決める。

### 5.5 ポータルユーザーと要員の紐付け（N1-4 / V188）

- 規則（決定 B-NF01-8・Q-10）: 退社完了時にポータルセッションを失効させるのは、管理者が登録した紐付け（`t_portal_user_engineer_link`）があるポータルユーザーだけ。emailでは照合しない。紐付け・ポータル組織・要員はすべて同じtenantであること。
- 紐付けの無いポータルユーザーは自動処理しない。内部アカウントとemailが一致する同tenantのポータルユーザーは、退社ゲート（`GET /api/lifecycle/cases/{id}/gate`）の `PORTAL_ACCOUNT_LINK` に「要確認」としてIDだけ表示される（emailは出さない。ゲートはブロックしない）。
- **登録・解除（管理者のみ）**: ポータル管理画面 `/portal-admin` の「組織」タブでユーザー一覧を開き、「要員紐付け」から要員IDを入力して登録する。解除は同じ画面の「解除」。API は次のとおりで、`SecurityConfig` が管理者に静的限定し、serviceも管理者・tenantを再判定する（営業は portal-admin menuを持つが操作できない）。
  - `GET /api/portal-admin/users/{portalUserId}/engineer-links`（現行と解除済みの履歴）
  - `POST /api/portal-admin/users/{portalUserId}/engineer-links`（body: `{"engineerId": n}`）
  - `POST /api/portal-admin/engineer-links/{linkId}/release`
- 1ポータルユーザーの現行紐付けは1件まで（V188の一意制約）。別の要員へ付け替える場合は解除してから登録する（重複は409）。解除は行を残して `released_at` / `released_by` を記録する。他tenantの要員・ポータルユーザーは存在しないものと同じ404。
- **監査**: 登録は `PORTAL_ENGINEER_LINK_REGISTERED`、解除は `PORTAL_ENGINEER_LINK_RELEASED` として `t_audit_log`（`reference_type = PORTAL_USER_ENGINEER_LINK`、`reference_id` = 紐付けID、HUMAN / MANUAL_API）へ同一transactionで必須記録する。記録できなければ登録・解除も取り消される。
  ```sql
  SELECT created_at, username, application_code, reference_id, before_state, after_state
    FROM t_audit_log WHERE reference_type = 'PORTAL_USER_ENGINEER_LINK' ORDER BY id DESC;
  ```
- **退社完了済みの要員への紐付け**: 拒否はしない（再入社等で正当な場合がある）。ただし完了時の自動処理は遡って行われないため、登録応答の `engineerResigned=true` で画面に警告を出し、監査の `after_state` に `engineerResigned=true` を残す。管理者はポータルユーザーの停止（「停止」ボタン）を個別に判断する。
- **lock順**: 登録・解除は対象要員の `t_engineer` 行を `FOR UPDATE` してから紐付け行を書く（NF01細粒度epoch計画 §3.1 の 順7 ENGINEER → 順12 PORTAL_ENGINEER_LINK → 葉の監査）。W3-16b以降、退社完了は要員行（順7）を含むlock planを取得した後に紐付けを読み直して失効対象を決める（§6.6）。完了と並行した紐付けの登録・解除は、どちらかが先に確定してから他方が進むため、完了の直前に登録された紐付けも失効対象に入る。
- **公開前の準備（データのある環境）**:
  1. V188適用前にpreflightの `PORTAL_ENGINEER_LINK_CANDIDATES` を確認する（旧email照合の対象だったポータルユーザーの件数）。
  2. V188適用後、管理者が該当するポータルユーザーの紐付けを登録する。要員本人ではないポータルユーザー（共有アドレス等）は紐付けず、その判断を5.2の手順で記録する。
  3. preflightを再実行し、`PORTAL_ENGINEER_LINK_CANDIDATES` が0件（または記録済みの許容分だけ）で、`PORTAL_ENGINEER_LINK_SCOPE_MISMATCH` が `PASS` であることを確認してから機能スイッチを検討する。
- **ゲートが `PORTAL_ACCOUNT_LINK` のFAIL（tenant不整合）で止まった場合**: 監査SQLで経緯を確認し、ポータル管理で不整合の紐付けを解除して正しいtenantの要員へ登録し直してから、再度完了確定する。DBの直接更新はしない。

### 5.6 細粒度認可epoch（V189 / epoch A spine）

- 内容（NF01細粒度epoch計画 §2.2・§2.3）: 全体epoch表 `t_authorization_global_epoch`（1行、初期値 `singleton_id=1, epoch=0`）と、既存の業務行のepoch列 `sys_user.actor_authz_epoch`・`t_engineer.engineer_scope_epoch`・`m_organization_unit.org_scope_epoch`・`t_document.evidence_eligibility_epoch`・`t_lifecycle_task.task_generation`（いずれも BIGINT NOT NULL DEFAULT 0、負値はCHECKで拒否）。あわせて二者確認coordinatorの表（W3-18）が参照する複合一意key `uk_lifecycle_case_tenant_id`・`uk_lifecycle_task_case_id`・`uk_document_version_tenant_doc_id` を追加する（`sys_user (tenant_id, id)` はV165の `uk_sys_user_tenant_id` を使う）。
- 適用前の確認: preflightの `AUTHORIZATION_EPOCH_COLUMN`・`AUTHORIZATION_GLOBAL_EPOCH`・`AUTHORIZATION_EPOCH_APPLICATION` が `PASS`（`state=NOT_APPLIED`）であること、`UNIQUE_KEY_PRECONDITION` が `PASS` であること。epoch列はDEFAULT 0で追加するため、既存行のbackfillやtenant対応付けは要らない（`ORG_UNIT_TENANT_MAPPING` の `BLOCKED_MAPPING` は本migrationを止めない）。
- 適用後の確認: 同じ3項目が `PASS`（`state=APPLIED`）。全体epoch行が無い・複数ある状態ではCONFIRMが `409 error.authorization.epochUnavailable` で止まる（fail-closed）。
- **epoch列を手で書き換えない・表単位でrestoreしない**: epoch列は entity に写像せず、`AuthorizationEpochMapper` の専用SQLだけが進める（値を戻す操作は無い）。anchor表（`sys_user`・`t_engineer`・`m_organization_unit`・`t_document`・`t_lifecycle_task`）だけを古い行へ戻すとepochが巻き戻り、失効済みの提出が復活し得る（計画 §9 RR-13）。DB全体のPITRは前後で一貫するため問題ない。
- W3-10の時点では部品（`AuthorizationEpochFence`・`AuthorizationEpochReader`・`LifecycleLockExecutor`）だけで、業務経路はepochを進めない。writerの接続はW3-15（全体・利用者）、W3-16a/b（要員・組織・タスク世代）、W3-17（文書）で行う。

## 6. 外部アカウント失効要求のoutbox（W3-11 / Q-6）

- 対象は案件tenant内の要員に紐付く全法人の未失効アカウント。所有法人が分かる行は要求登録可能として退社ゲートを通す。法人がNULLまたはEXCEPTION_HOLDの行は登録不能として止め、保留を自動解除しない。資産返却とライセンス解放の条件は残る。資産・ライセンスの承認済み免除があっても、所有法人不明・例外保留の外部アカウントは完了を止める。
- 完了transactionはengineer行を先にロックし、各account行をID順にロックして要求と履歴を登録する。同じcommitに案件完了が入り、provider I/Oはcommit後だけ行う。定期回収はNF01スイッチとShedLockで制御する。
- 状態は REVOKE_REQUESTED → REVOKE_SENDING → REVOKE_SENT。明確な不受理はREVOKE_SEND_FAILEDとして指数的に再試行し、待機上限は1440分。通信結果不明・ACK前停止では、providerに同じkeyの冪等再送保証が無ければUNKNOWN / REVOKE_SEND_UNKNOWNとし、無条件に再送しない。未送信の3状態は確認pollの対象外。
- keyはlifecycle-resign:{caseId}:{accountId}:g{n}。UNKNOWNを管理者が再要求するとg{n+1}となる。旧keyの自動確認は新しい要求を確定できない。登録後は識別子・権限・状態を直接変更しない。状態とkeyの変更はt_asset_eventに追記する。人間が登録・再要求した場合はHUMAN/MANUAL_API、workerはSYSTEM/SCHEDULER_POLLまたはPROVIDER/PROVIDER_SYNCとする。outbox履歴のdetailsとidempotency_key列はいずれもkeyのSHA-256で、要求の原keyは参照台帳に保持する。適用済みV129の列コメントは変更せず、新値の定義はentityと本節を正本とする。
- 退社後の新規登録は、最新の終結案件が完了済みRESIGNATIONで、後続にJOIN/REINSTATEMENT案件が無い場合だけ自動登録する。後続の再入社案件はDRAFTでも自動失効を止める。CANCELLEDのJOIN/REINSTATEMENTは最新終結・後続再入社の両判定から除く。前後関係は案件IDの作成順であり、完了時刻順ではない。再入社起票とaccount登録は同じengineer行ロックで直列化する。
- 登録の新規transactionは互換入口を含めREAD_COMMITTEDとする。外側transactionに参加する場合も、最初のscope読取前に実connectionの分離レベルを確認し、READ_COMMITTED以外は409で拒否する。既存のREPEATABLE_READ transactionから登録する呼出元は境界を分離して再設計する。

### 6.1 送信失敗・結果不明の対応

1. 管理者・HRは通知を確認し、外部アカウント画面で「失効要求の未処理一覧（全法人）」を選ぶ。APIはGET /api/external-accounts/pending-revocations（最大100行）。識別子はマスクされる。reference IDと法人で対象を特定する。
2. 当日中にHRが担当を割り当てる。送信失敗・結果不明が1件でもあれば通知とERRORログで検知し、解消まで毎営業日確認する。大量障害時はNF01を停止して外部システム側を先に確認する。停止中の要求は台帳に残る。
3. provider=noneでは自動失効しない。外部システムの管理者が対象accountの無効化、既存session、offline session、tokenの失効を個別に実施・確認し、証跡を保管する。NF09の既存一覧・登録応答・CSVには従来どおり権限内の原識別子を表示する。担当者は現在法人の一覧でreference IDを照合し、承認済みの安全な経路で連携先管理者へ伝える。NF01の追加の未処理一覧・通知・ログだけを共通maskIdentifierでマスクし、通知やチケット本文へ原値を転記しない。SESの「失効確認」またはPOST /api/external-accounts/{id}/confirm-revokeで人間の確認主体を記録する。
4. 実状態を確認できない場合はUNKNOWNを維持する。再要求が必要なlifecycle形式のkeyに限り、現在の法人scopeを選んだ管理者がPOST /api/external-accounts/{id}/re-request-revokeを使う。legacy形式のkeyは手動確認で閉じる。provider callbackもlifecycleの現行要求keyを提示できない場合は確認を拒否する。
5. ライセンスは完了前に1件ずつPOST /api/licenses/assignments/{id}/releaseで解放する。releasedDateは処理日でなく案件の退社基準日を指定する。1件の409で残りの解放を止めず、当該IDを再読込してから再実行する。旧triggerOffboardingRevocationsは基準日を持たず同期送信する入口だったため、409で拒否し使用しない。
6. ACCOUNTING_TENANT_IDSには1つのtenantだけを指定する。workerは行ごとの法人とrunWithTenantのtenantを使い、SecurityContextの操作者法人を参照しない。複数tenantの本番運用は別承認まで行わない。

### 6.2 実provider公開前に残る接続プールの制約

現在のafterCommitは計画§5.13に従ってNOT_SUPPORTED dispatcherを同期呼出しする。即時送信は本transactionで実際に新規登録したIDだけを対象とし、tenant全体の積残しは60秒周期のschedulerだけが回収する。next_retry_atの保存値・lease・比較nowはDATETIME(0)に揃えて秒へ切り捨てる。Springはcallbackが戻るまで完了transactionのconnectionを保持するため、claim用の別connectionが必要になり、provider I/O中も元のconnectionを占有し得る。同時完了数がpool上限へ近付くと枯渇する可能性がある。provider=noneを維持し、実provider公開前に、受付数を制限した非同期dispatchとdrainの設計・独立レビューを別途行う。既存taskExecutorにはCallerRunsPolicyがあるため、単に@Asyncを付けても呼出元threadで実行される可能性があり、対策とはみなさない。

### 6.3 Runner・運用者の確認項目

- [ ] 退社完了commit前の送信0回、rollback時の要求0件を確認する。
- [ ] 別法人のaccountも各行の法人で登録され、同一要求の送信競合は1回となる。
- [ ] REQUESTEDの停止回収、SENDINGの非冪等providerでのUNKNOWN、未送信行へのpoll更新0件を確認する。
- [ ] 退社後登録は自動要求になり、後続JOIN/REINSTATEMENT後の登録は通常accountとして残る。
- [ ] UNKNOWNのg1→g2と履歴、通知・一覧の識別子マスクを確認する。
- [ ] NF01 OFFで送信を開始しないこと、ShedLockと既存scheduler停止手順を確認する。新しい常駐threadやexecutorは追加しない。

本節は実装と確認手順であり、本番有効化の承認ではない。epoch/CONFIRMを含む残りの条件と独立レビュー・Runnerが完了するまで本番はNO-GOとする。

### 6.4 リリースゲート（G4r独立レビューの残項目）

| 指摘 | 有効化の前提・担当 |
|---|---|
| P3-1（NF01 ON前） | **W3-16bで実装済み（§6.6）。残りはRunnerの実測。** completeCaseは連携ユーザー（rank 3）を要員（rank 7）より先に、案件・タスク・所属・紐付け・担当営業・外部アカウントまでを1つのlock planで1回だけ取る（3 S{操作者}・X{連携ユーザー} → 4 → 5 → 7 → 8 → 9 → 10 → 14 → 葉）。旧実装のengineer X（rank7）→連携sys_user X（rank3）と既存L5（4→3）は消えた。変更申請の承認（2→3→7）、紐付け解除（3 S→7 X）、要員削除（3 X→7 X）、所属writer・統合・利用者の無効化/削除（3 X→7 X）と同じ向きになる。M6（`ResignationCompletionLockOrderMySqlTest`、shard-3）で、これらと退社完了を両順序で実行して1213/1205が出ず、後続側が先行側の確定を待って成功するか依存先の変化を409で返すこと、外部アカウントの失効要求登録が完了と同じcommitに入ることを検証する（未実行。NF01 ON前にRunnerが5回以上・skip 0で実行する）。 |
| P3-2（実provider前） | 本transactionのID限定で積残しの同期処理は解消したが、callback終了まで元connectionを保持する制約は残る。有界非同期dispatch/drain、受付上限、停止時回収、pool飽和時の挙動を設計し独立レビューする。CallerRunsPolicyの既存executorへ単純移管するだけでは承認しない。 |
| P3-9(4)（NF01 ON前） | Runner/DB担当がInnoDbRecordLockWaitsで「登録が退社完了のengineer Xを待ち、commit後に完了済み案件を読み要求登録する」両順序を実MySQLで検証する。LOCK_DATAの値に依存しない。**RB-NF01でM10（`ResignationRevocationOutboxMySqlTest`、shard-2）に両順序の試験を追加した**: 退社完了が先なら登録は要員行で待ち、完了のcommit後に失効要求（g1）を同じ登録で作る。登録が先なら退社完了は要員行で待ち、登録のcommit後は依存先（未失効の外部アカウント）の変化で `409 error.common.optimisticLock`、再実行で登録済みの口座も失効要求に含める。M10と既存AssetMySqlIntegrationTestをそれぞれ5回以上、skip0で実行する（Runnerの実測は未取得）。stagingの実環境での実測は人が行う（未取得）。 |
| P3-12（実provider前） | SREが100行×provider timeoutの最悪時間とShedLock PT10Mを再評価する。batch件数・総実行期限・HTTP timeout・lease・停止drainを一体で制限し、lock期限切れで重複schedulerが起動しても安全なことを実測して承認する。 |
| P3-5（継続運用前） | HRが案件ID作成順を時系列の基準として確認する。遡及起票・完了順での判定が必要なら、信頼できる時刻列を用いる別設計を承認する。本修正では時刻の意味を推測しない。 |

### 6.5 通知頻度・機能OFF・scope

- P3-3/P3-8: 送信失敗・確認待ちの通知は最初のretryと1440分backoffへ到達したretry（9回目）だけ。毎回の無限通知は行わず、以後も未処理一覧に残す。UNKNOWNへの遷移は直ちに通知する。本文はnotification.msgキーとマスク済み引数のJSON配列で保存する。provider=noneの手動失効運用では、HRが一覧を毎営業日確認する。
- P2-1: REVOKE_SEND_UNKNOWNはpollのPENDING・timeout・例外でもUNKNOWNのままbackoffだけを更新する。CONFIRMEDのみ失効済みに移し、それ以外ではgN+1の管理者再要求を維持する。REVOKE_SENTの確認待ちも一覧に残す。
- P3-10/P3-11: gateは案件tenantを明示した専用照合を使い、完了時に登録する件数と登録済み・確認待ち件数を区別する。本人のNF09画面は従来の現在法人scopeを維持する。明示tenantが実行contextと異なる場合は拒否する。
- P3-13: NF01 OFFでもNF09登録APIは利用でき、完了済み退社の条件を満たす追加accountは同じtransactionでREVOKE_REQUESTEDになる。dispatcher/schedulerはOFF中に送信せず、ON後に回収する。OFFは要求の取り消しを意味しない。運用者は未処理一覧で確認し、必要なら既存の人間確認APIで閉じる。
- P3-4: EXCEPTION_HOLDは要求登録不能として退社完了を止める。HRが根拠を確認し通常account状態へ戻すか、外部側で失効を確認して人間確認する。outboxは保留を自動解除しない。

上記gateの証拠とOwner/Security/SREの承認が揃うまで、本番はNO-GOのままとする。

- P3-10補足: 既存の非NULLかつ空白だけのkeyはCASのNULL条件を満たさないため登録不能とする。HR/DBAが由来と監査を確認して承認済みのデータ修復を別途行う。mapperのCASを緩和して黙って上書きしない。登録済み件数は登録可能かつ非空keyだけを数え、保留行との重複を避ける。

### 6.6 退社完了のlock順とタスク世代（W3-16b）

NF01細粒度epoch計画 §3.4「completeCase」・§4.2・§7.5。本節は実装と残りのリスクの記録であり、本番有効化の承認ではない。

- **処理順**（`LifecycleCaseServiceImpl.completeCase`、単一の `REQUIRED`・`READ_COMMITTED` transaction。CONFIRMのcoordinatorは使わない）:
  1. discovery: lockを取らない通常読取で、案件の状態・操作者の権限・阻害タスク・退社ゲートを確かめる。完了できない要求はlockを取らずに400等で返す。続けて完了処理が書く行を列挙する（`LifecycleCompletionLockPlanner`）。
  2. lock: 3 S{操作者}・X{連携ユーザー}（ID昇順）→ 4 X{案件} → 5 X{全タスク} → 7 X{要員} → 8 X{連携ユーザーの所属行と、連携ユーザーを上長とする他人の所属行} → 9 X{紐付け} → 10 X{現任の担当営業} → 14 X{未失効の外部アカウント（全法人）} を `LifecycleLockExecutor` で1回だけ取る。
  3. lock後: 同じ照会で依存先を列挙し直し、違えば途中でlockを足さず `409 error.common.optimisticLock` で全体をrollbackする。前提（状態・権限・阻害タスク・ゲート）もlock後の現在値で判定し直す。
  4. 書込み: 取得済みの行（停止 → 所属の終了・上長解除 → 紐付け解除 → 担当営業の解除 → 外部アカウントの失効要求登録 → 要員のBench化 → 案件COMPLETED）と葉（event、内部session・ポータルsessionの失効、commit後の通知と失効要求の送信）だけ。
- **epoch**: 連携ユーザーの停止は status 列が変わった時だけactor epochを1進める（所属の終了による増分とは同じtransactionで1回にまとまる）。要員のengineer epochは所属の終了・紐付け解除・担当営業の解除が進め、合わせて1回になる。操作者のepochは変えない。
- **操作者自身に連携された要員**の退社案件は `400 error.lifecycle.selfResignationCompletion` で拒否する（操作の途中で自分のアカウントを停止しないため。別の管理者またはHRが完了する）。権限不足ではなく業務規則の拒否のため400とし、画面に案内文言を出す（403は画面の共通処理が固定文言へ置き換える）。従来は完了でき操作者自身のアカウントが停止されていたため、リリースノートに挙動の変化として記載する。
- **409が返る場面（運用）**: 完了の確認から実行までの間に、対象要員の紐付け解除・要員削除・所属の登録/異動、担当営業の割当、外部アカウントの登録が確定した場合。画面を再読み込みして退社ゲートを確認し直し、再度「完了確定」を押す。自動再試行はしない（計画 §9 RR-9）。
- **1213・1205の応答**: deadlock（MySQL 1213）・lock待ちtimeout（1205）はAPI全体で `409`（`error.common.optimisticLock` の文言、ログは `errorCode=LOCK_CONFLICT`・WARN）として返す（従来は500）。再読み込みして再試行すれば通常は成功する。`LOCK_CONFLICT` が継続して出る場合はlock順の不備を疑い、ログの例外型と時刻から経路を特定する。DBAは `innodb_print_all_deadlocks=ON` でdeadlockの待機graphをerror logへ出して確認する。5xx・ERRORだけを見る監視ではdeadlockを検知できなくなるため、SREは `LOCK_CONFLICT` の発生率を監視・通知の対象に加える（計画 §10）。
- **PWAのオフライン送信（`/api/my/pwa/**`）は例外として503で返す**: オフラインqueue（`pwa-queue.js`）は409を「人の判断が要る競合」（CONFLICT表示）、5xxを「一時的な失敗」（queueに残して自動再送）として扱う。lockの競合は一時的な失敗であり、commandはledgerのclaimと一緒にrollbackされて同じclientRequestIdで再送できるため、`PwaMutationApiController` だけは `503 error.pwa.lockConflictRetry`（ログは `errorCode=LOCK_CONFLICT`）で返し、端末に入っている既存のqueueのまま自動再送させる。
- **タスク世代**: 案件の保留（終端でないタスク）・取消（取り消したタスク）・タスクの担当変更（担当が変わった時）・免除で、該当タスクの `task_generation` を1つ進める（案件X → タスクX の後）。再開では戻さない。上限（`Long.MAX_VALUE`）では `409 error.authorization.epochUnavailable` で業務更新ごと止まる。担当変更は新担当の利用者行を共有lock（rank 3 S）してから案件・タスクをlockする。
- **要員の所属組織を書く更新・休暇申請**（`EngineerServiceImpl.updateWithStatusGuard`・`updateById`、`LeaveServiceImpl.apply`）は、外部キー検査の参照先の組織行（rank 6）を要員行（rank 7）より先に共有lockする（W3-16a後半 review P3-1。組織統合・所属writerの 6 X → 7 X との循環を作らない）。実MySQLの証拠はM4（`EngineerScopeFenceMySqlTest`、shard-2）の「要員の所属変更 × 組織統合（同じ要員・同じ統合先）」の両順序（RB-NF01、W3-16b review P3-5）: 統合が先なら所属変更は統合先の組織行の共有lockで待ち、要員行のlockを持たず、統合のcommit後に版の競合で409になる。所属変更が先なら統合は統合先の組織行で待ち、commit後に両方成功する（Runnerの実測は未取得）。
- **休暇申請**（`LeaveServiceImpl.apply`、要員本人）は、さらに申請者本人の利用者行（rank 3）を最初に共有lockする（3 S → 6 S → 7 X）。申請行の `created_by` と承認申請の `applicant_id` は利用者行への外部キーで、要員行のX lockの後のinsertでその検査が本人の利用者行へS lockを付けると、退社完了（3 X{連携ユーザー} → 7 X{要員}）と循環し得た（W3-16b review P3-1）。

残りのリスク（NF01 ON前の確認事項）:

| # | 内容 | 担当・扱い |
|---|---|---|
| R-1 | 実測済み（W3-16b 統合後の 57ca6c3f の Runner）: M6・`LifecycleLockOrderMySqlTest`・`ResignationRevocationOutboxMySqlTest`・`EngineerScopeFenceMySqlTest` を合計5回、他の mysql 16類を1回実行し、skip 0 で PASS した。以後の PR #121 の CI（16c6130e まで）の mysql shard でも PASS している。`LifecycleMySqlIntegrationTest` を3回以上というRunnerの指定は、57ca6c3f の1回と CI の各回の合計で満たす。FINAL-FIX で M6 にポータル紐付け登録のcaseを加えたため、そのcaseを最終SHAで再び実測する。 | Runner: 最終SHAで shard-3 のM6を5回以上、skip 0（他は CI の mysql shard） |
| R-2 | 上長が互いの所属行の上長になっている2人について、利用者の無効化・削除（所属の一括終了と上長解除）と退社完了、または無効化・削除どうしを同時に実行すると、上長解除（clearManager）が相手の所属行を待ち合い1213になり得る（既存。W3-16a後半 review P3-2）。2人の退社完了どうしは、上長解除の対象行も rank 8 で同じ順にlockするため直列化される。今回からAPIは409で返す。 | 運用: 互いを上長にする所属を作らない。発生時は片方ずつ再実行する |
| R-3 | 外部キー検査の共有lock: 要員の所属組織を書く更新と休暇申請は参照先の組織行（6 S）を、休暇申請は申請者本人の利用者行（3 S）も、要員行より先に取るようになった。利用者行への外部キー（`created_by`・`applicant_id` 等）も rank 3 の暗黙のS lockである。本人自助のwriterで要員行（7 X）を取ってから本人の利用者行を参照する行をinsertするものは、休暇申請のほかに見つからなかった（PWAの変更申請は要員行の後に利用者行を参照しない）。HR・管理者の操作（スキルの置換等）が自分に連携された要員に対して同じ形になる場合と、組織を参照する行をinsertする他の業務writerは未対応。 | 計画 §9 RR-15。1213は409で返る |
| R-4 | 退社完了はlock前とlock後の2回、退社ゲートを判定する（読取の増加）。完了は管理操作で頻度が低いため許容する。 | 性能gateで確認 |
| R-5 | 1つのtransactionで退社完了を2回呼ぶと、2回目のlock planが1回目より前のrankを取ることになりexecutorが拒否する（業務経路は要求ごとのtransaction）。fast testで複数の完了を1つのテストtransactionにまとめる場合は、1回目がlock前の判定で失敗する形に限る。 | 開発規約 |
| R-6 | H2では共有lockを排他lockで代える（`SharedLockSupport`）。共有lockの意味（readerやwriter同士を待たせないこと）はMySQLの試験だけで確かめる（計画 RR-12）。 | 既知 |
