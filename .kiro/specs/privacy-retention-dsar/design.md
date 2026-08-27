# privacy-retention-dsar 設計（Discovery / Dry-run increment）

## 1. 設計方針

この設計はNF-07の候補要求を、DG-07未完了の安全境界内で観測可能にするためのものだ。今回の成果物は `pii-inventory.md` と offline dry-run のみで、Spring bean、controller、mapper、migration、scheduler、storage writer、外部HTTP clientを追加しない。

既存実装の再利用候補は以下のとおりである。

- `FileReferenceProvider` と `FileScopeValidationService`: file/objectの参照元とscopeを漏れなく列挙する基盤。ただし現状のcleanupには物理削除経路があるため、NF-07のDSAR処分へ直接接続しない。
- `DocumentRetentionJob`、`t_document`、`t_document_version`、`t_document_disposal_request`: 法定文書の候補抽出・承認モデル。`retention_until IS NULL`、hold、原本/版/hashを維持し、別のDSAR処分経路で上書きしない。
- `ApiAuditFilter`、`t_audit_log`、`t_document_access_log`、`t_portal_access_log`: auditはappend-only/保持対象としてinventoryする。auditを本人請求の通常削除対象にしない。
- `g10-allowlist.json`、`t_ai_recommendation_run`、legacy `t_ai_log`: AI送信/保存のallow-listと二重保存を別要素としてinventoryする。GATE-S17-G10-PROD未完了のため実provider呼出しはしない。
- `DataScopeService`、`OrganizationScopeService`、`FileScopeValidationService`: subject×operation×scopeを共通解決する候補。list/detail/count/export/download/notification/scheduler/async/cache/providerを同一scopeに揃える。

## 2. inventoryの正規形

各行は次のschemaで管理する。raw値ではなく、field名、固定ID、hash、状態だけを扱う。

```text
dataElementId
storageKind: DB_COLUMN | FILE_OBJECT | AI_PAYLOAD | LOG_OR_BACKUP
storageRef: table.column / provider.object-kind / payload.field
dataClass
ownerState / ownerRef
purpose
collectionTrigger
retentionState / retentionRule / retentionUntilSource
holdState / blockerState
dispositionMethod
dsarProvider
policyStatus: CONFIRMED_TECHNICAL | PROVISIONAL | UNKNOWN
evidence
```

`CONFIRMED_TECHNICAL` はコード/schemaに存在する事実であり、法的保存義務の承認を意味しない。owner、purpose、retention、triggerのどれかがDG-07で未承認なら `UNKNOWN` または `PROVISIONAL` とする。

## 3. 将来F1のcatalog/policy/hold/request/job（今回は未実装）

requirements/designの候補には次のテーブルを置くが、今回のbranchにはDDLを追加しない。

| 候補テーブル | 責務 | fail-closed条件 |
|---|---|---|
| `m_pii_data_element` | DB/file/AI要素とprovider、scope、owner、purposeのcatalog | source/provider未登録、owner未確定 |
| `m_retention_policy` | versioned policy、trigger、期間、approved decision | policy未承認、trigger計算不能、期限NULL |
| `t_legal_hold` | holdの対象、開始/解除、authority、根拠 | hold状態不明、解除権限/二者承認不明 |
| `t_privacy_request` | request/case、本人確認、期限、owner、decision、delivery | 本人確認未完、期限/owner不明 |
| `t_privacy_request_subject_link` | 1 requestとverified subjectの明示link | 同姓同名/複数候補/人のresolutionなし |
| `t_privacy_action` | search/export/restrict/anonymize/disposeのclaimとevidence | scope外、第三者redaction不能、CAS失敗 |
| `t_disposition_job` / `t_disposition_item` | batch、再送、部分失敗、誤対象取消、flag/approval | idempotencyなし、対象snapshotなし、flag OFF |

物理backup、read replica、filesystem quarantine/published、外部providerの二次コピーは、DB catalogの子行ではなく独立providerとして列挙し、scope確認不能なら呼び出さない。

## 4. 時間とas-of（platform-invariants準拠）

| 対象 | 正規化 | 判定規則 |
|---|---|---|
| request/action timestamp | `Instant` | API/auditの実時刻。画面表示はtenant timezoneへ変換 |
| retention trigger/date | `LocalDate` | tenant/business timezoneでinclusiveに計算し、policy versionとsnapshotする |
| dry-run | 明示入力の `asOf` | current clockを使わず再現可能にする。`retentionUntil <= asOf` のみを期限到来候補とする |
| policy effective range | `valid_from` / `valid_to` | 境界を含む。複数policyが重なる場合は一意に解決できずUNKNOWN |
| `retention_until = NULL` | unknown | 文書archiveの既存規約どおり、期限未確定として候補にしない |

## 5. subject × operation × scope

| subject/operation | scope source | 許可条件 | unknown/blocked時 |
|---|---|---|---|
| 本人確認 | verified credential + human resolution | requestのsubject linkが一意 | 同姓同名/未確認はBLOCKED。自動統合しない |
| DB search | table provider + `DataScopeService` | request subjectかつprovider in-scope | provider未登録/scope不明はUNKNOWN、scope外はBLOCKED |
| file search/download/export | `FileReferenceProvider` + `FileScopeValidationService` | registered object、scan clean、業務link scope許可 | unknown reference、scan未完、第三者混在はBLOCKED |
| AI payload search | G10 catalog + run/log provider | allow-list fieldとapproved request scope | raw prompt/外部providerは送信しない。legacy不明はUNKNOWN |
| export | subject-specific projection + third-party redactor | 必須redaction成功、監査可能 | redaction不能はBLOCKED。第三者を出さない |
| disposition | policy/hold/audit/business resolver | approved policy、期限到来、holdなし、二者承認、flag ON | 今回は常に実行なし。gate未完はBLOCKED |

同一subjectでも、一覧・詳細・count・export・download・通知・scheduler・async/cache・backup restore後の再計算に異なる母集団を作らない。scopeを作れない処理は安全側で空集合/blockedにする。

## 6. state / competition

| 状態 | 遷移条件 | 競合/再送規則 |
|---|---|---|
| `RECEIVED` | request受付 | external provider呼出し前にscopeを固定 |
| `IDENTITY_PENDING` | 本人確認/人のresolution待ち | 同姓同名は候補を結合せず、reviewerの明示選択が必要 |
| `SCOPED` | subject/provider/scope snapshot確定 | snapshot hashを保持し、変更後に再利用しない |
| `DRY_RUN` | read-only判定完了 | 再実行可能。sourceへwriteしない |
| `CANDIDATE` | policy/期限/blocker checkが全てPASS | 処分許可ではない。approval/claim前提 |
| `BLOCKED` | hold/legal/audit/business/scope/identity等を検出 | blocker解除までは再試行してもwriteしない |
| `UNKNOWN` |必要な状態・policy・providerが未確定 | 人の確認またはpolicy登録後に再計算 |
| `APPROVAL_PENDING` | 将来、二者承認へ進む | requesterとapproverを分離し、CASで一度だけ進める |
| `ACTION_SUCCEEDED` / `PARTIAL_FAILURE` | 将来の対象単位action | item idempotency、再送、取消、backup/evidence確認 |

`CANDIDATE` と `APPROVAL_PENDING` を混同しない。現在は `CANDIDATE` の表示までで停止する。

## 7. dry-run入力・出力契約

入力は `tools/privacy-retention-dsar/dry-run-fixture.json` のようなredacted snapshotだけとする。最低限、次を含む。

- 固定 `asOf`、request/scope ID、provider scope。
- owner/purpose/trigger/policyの確認状態。
- `retentionUntil`、hold、legal retention、audit、active business blocker。
- identity resolution（`VERIFIED` / `AMBIGUOUS` / `UNVERIFIED`）。
- candidateのdataElementIdと、処分方式のラベル。raw値は不可。

出力はsummaryと各elementの`candidateKey`、`dataElementId`、status、理由、providerCallCountだけとし、個人値を出さない。出力先はstdoutのみで、レポートファイルを作らない。

## 8. 今回の非採用事項

- F1 DDLやMyBatis entity/mapper。
- provider interfaceの本番接続、外部AI/CloudSign/freeeへの検索。
- dashboard/approval UI、request受付、export ZIP/PDF。
- anonymize/delete/restrict writer、batch scheduler、backup restore操作。
- 法務、HR、税務、外部専門家の判断を代替するルール。

これらはDG-07と外部/社内gateがPASSし、approved scope/policyが実値になった後に、別task・別commit・別Reviewで再計画する。
