# NF01 Production Readiness 独立Review

- Reviewer: `astra_review_baseline` / gpt-6-astra
- 固定Head: `8ad0c34eacbb04a031667934f313a2b6965583f1`。read-only静的Review、試験未実行。
- 総括による日本語転記。歴史上のCOMPLETEを削除せず、現行基線の再判定を記録する。
- PLAN: 歴史上のPASS維持 / IMPLEMENTATION: **FAIL** / PRODUCTION: **BLOCKED**。

## OPEN finding

| ID | 優先度 | 位置（src/main/java/com/ses以下） | 問題・必須回帰 |
|---|---|---|---|
| NF01-R-01 | P1 | service/lifecycle/impl/LifecycleCaseServiceImpl.java:64,156,254,498 / LifecycleScopeService.java:82,125 / impl/LifecycleSlaServiceImpl.java:40 | tenant条件なしの案件CRUD、admin/HR無条件許可、create時tenant未設定。actor/case/engineer/current tenant一致、SQL tenant条件、NULL拒否。同庫A/Bでlist/detail/hold/complete/taskを拒否し、新案件が所属tenant SLA対象となること |
| NF01-R-02 | P1 | service/lifecycle/impl/LifecycleTaskServiceImpl.java:64-69 | startTaskがCOMPLETED/WAIVEDもIN_PROGRESSへ戻す。許可元状態だけ遷移。終端start拒否、証跡不変、IN_PROGRESS再送は認可後に冪等成功 |
| NF01-R-03 | P1 | 同上:118-145 / dto/lifecycle/CompleteLifecycleTaskCommand.java | DUAL_CONFIRMATIONのverifier/SoD検証なし。検証済み別主体の独立確認、権限、証跡、同時再送を強制。任意client verifierを信用しない |
| NF01-R-04 | P1 | service/lifecycle/impl/LifecycleCaseServiceImpl.java:343,349-353 | 営業へ非公開タスクevent、本人へ非公開predecessor codeが漏れる。全roleのtask可視集合でevent/dependency投影。非公開ID/code/name/commentがresponseのどこにもないこと |
| NF01-R-05 | P1 | controller/api/LifecycleApiController.java templates POST/PUT/toggle-status / service/lifecycle/impl/LifecycleTemplateServiceImpl.java:147,178,233 | 営業/managerにmenu権限があり、template mutationのadmin/HR制限なし。controller/service境界で拒否しDB不変、admin/HR正常 |
| NF01-R-06 | P2 | service/lifecycle/LifecycleScopeService.java:104-105,144-145,205-211 | 旧起票者/旧assigneeが現在の担当scopeに優先する。担当解除/組織変更後は旧権限を継承しない。例外は明示・取消可能なgrantがある場合のみ |

## NF09連動・証拠

- ResignationGateChecker.java:137-158 と ExternalAccountReferenceMapper.java:47 は非REVOKEDをblockerとする。
  PENDING/UNKNOWNでwaiverがなければfinalizationを許さない。既知mock問題は重複計上しない。
- `triggerOffboardingRevocations` は定義のみでNF01からの呼出なし。
  退社完了の阻断check後へ呼出を追加せず、撤销要求と確認待ちを独立段階として設計・検証する。
- DOCUMENT_LINKは存在性確認と任意documentVersionId受理に留まる（TaskServiceImpl:129-136）。
  歴史ledgerの未閉鎖ACL項目として残す。本Reviewは文書読み出しchain未検証のため新規漏洩findingとはしていない。
- 実provider、実撤権、production演習は未完了。

## 実行分割

- R01a: tenant/current scope、終端状態、template mutation、可視投影を先に修復する（01/02/04/05/06）。
  既存schemaを使う。NULL tenantを推定で埋めない。既存不明行は運用repair対象として残す。
- R01b: DUAL_CONFIRMATIONの二段階確認、文書ACL/version、NF09撤销要求段階を設計しAstraで確認後に実装する。
  必要なmigrationは総括採番とし、R01aで旧migrationを変更しない。
- R01aだけのPASSをNF01全体のPASS/COMPLETEとしない。
