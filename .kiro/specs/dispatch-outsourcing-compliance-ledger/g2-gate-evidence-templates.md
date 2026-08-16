# G2 gate証跡 記録様式テンプレート（証跡1/2）・改訂版

- **作成**: 2026-08-12 / 実装AI（2026-08-14改訂: 直接SQL例を廃止しUI/API/domain event経由へ）
- **目的**: T066 MのG2 gate証跡1（COMPLIANCE_RESPONSIBLE runtime assignment）・証跡2（実actor承認event）を、V102 schema（`t_compliance_responsible_assignment`・`t_compliance_mapping_approval_event`）と整合する形式で記録するための様式。
- **改訂理由（R10 2026-08-14指摘）**: 直接SQL例は正式手順としない。UI/API/domain event経由で記録し、DB rowはイベントの結果としてのみ確認する。
- **参照**: `field-mapping.md` §2.3（コンプライアンス責任者 role と承認状態）、V102 DDL、`reviewer-verification-decision-delta-r23-p1-01.md` §3〜§5

## 記録時の共通注意

- 自然人はspec/seedへ事前固定しない。実在するsys_user.id・表示名snapshotを記録する。
- mapping version/hashは承認時点の固定値を記録する（現在のfield-mappingはPROVISIONAL_REVIEWED凍結中。FM-C-28追加（P1-1）の版管理判断後にhashを再固定する）。
- 架空資格・推測値の記録は禁止（§3.1 架空資格禁止）。未確定項目はNULLのまま提出せず、取得後に記録する。
- idempotency_key/correlation_id/event_chain_id/operation_id は一度記録した値を再使用しない（V102 UNIQUE/一意制約）。
- **記録はすべてアプリケーションAPI経由**（`/api/compliance-gate/**`）。DBへ直接SQLで書き込まない。DB rowはAPI応答と照合する確認手段としてのみ用いる。
- 証跡取得手順は実装済みUI（`/compliance-gate`）とAPIの両方が利用可能。UI操作とAPI応答の両方を記録に残す。

## 証跡1: COMPLIANCE_RESPONSIBLE assignment（管理者・人間が記録）

### 手順（API経由・人間操作）

1. 管理者が `/compliance-gate` の Assignment tab（または `POST /api/compliance-gate/assignments`）で、対象workplace×実在社内ユーザー（role=HR/法務等の適格者）を指名する。
2. システムが `t_compliance_responsible_assignment` へ半開区間（effective_from <= now < effective_to）・active_slot=1で記録する。
3. 管理者はAPI応答（assignment id・effective_from・active_slot）と画面表示を目視確認する。

### 様式（API応答に基づく記録）

| 項目 | 値 | 記入者 |
|---|---|---|
| tenant_id | `default` | — |
| workplace_id | 対象事業所のid（m_workplace） | 管理者 |
| user_id | 指名する社内ユーザーのid（sys_user、role=HR/法務等の適格者） | 管理者 |
| role_code | `COMPLIANCE_RESPONSIBLE`（固定） | — |
| effective_from | 任命開始日時（DATETIME(6)） | 管理者 |
| effective_to | 交代時までNULL（open）、交代時は終了日時 | 管理者 |
| active_slot | open時 `1`、終了時 NULL | 管理者 |
| assigned_by | 任命者（管理者本人のsys_user.id） | 管理者 |
| ended_by / end_reason | 交代時のみ記録 | 管理者 |
| API証跡 | `POST /api/compliance-gate/assignments` のリクエスト/応答JSON・HTTP status | 管理者 |

### 確認クエリ（記録確認のみ・書き込みに使用しない）

```sql
SELECT id, tenant_id, workplace_id, user_id, role_code, effective_from, effective_to, active_slot, assigned_by
FROM t_compliance_responsible_assignment
WHERE tenant_id = 'default' AND user_id = <実ユーザーid> ORDER BY effective_from DESC LIMIT 1;
```

## 証跡2: 実actor承認event（対象mapping version/hashへの承認・人間が記録）

### 手順（API経由・人間操作）

1. 被指名actor（証跡1で指名された本人）が `/compliance-gate` の Internal Approval tab（または `POST /api/compliance-gate/approvals`）で、対象mapping version/hashへ承認を実行する。
2. システムが `t_compliance_mapping_approval_event` へ記録する。actor_idはセッションから取得され、serviceがcurrent assignment.user_idとの一致を検証する（§5: 管理者・HR・マネージャーはapproval画面へ入れるが、current assignment.user_id == currentUserId必須）。
3. 実actorはAPI応答（event id・mapping hash・occurred_at）と画面表示を目視確認する。

### 様式（API応答に基づく記録）

| 項目 | 値 | 記入者 |
|---|---|---|
| tenant_id | `default` | — |
| mapping_id | 対象mapping versionのid（m_compliance_mapping_version） | 実actor |
| mapping_version | `MAPPING-2026-07`（FM-C-28版管理判断後の新version表記に従う） | 実actor |
| mapping_hash | **canonical mapping/source payloadのSHA-256（64 hex）**。canonicalizerがDB rowから算出しAPI応答へ含める | 実actor |
| review_policy_hash | canonical review policy payloadのSHA-256（64 hex）。canonicalizerが算出 | 実actor |
| assignment_id | 証跡1で記録したassignmentのid | 実actor |
| workplace_id_snapshot | 対象事業所のid | 実actor |
| actor_id | 承認者のsys_user.id（セッション由来） | 実actor |
| actor_display_name_snapshot | 承認者の表示名（記録時点のsnapshot） | 実actor |
| actor_role_snapshot | 承認時のrole（例: `HR`） | 実actor |
| action | `APPROVE`（/`REJECT`/`REVOKE`） | 実actor |
| event_chain_id | UUID（chain識別子） | 実actor |
| supersedes_event_id | 取消/差戻し時の対象event id | 実actor |
| occurred_at | 承認日時（DATETIME(6)） | 実actor |
| reason | 承認理由（法的根拠・確認内容） | 実actor |
| evidence_document_id / version / hash | 根拠資料のdocument archive参照。**exact version id・SHA-256・scan=CLEANをevidence picker経由で解決**（§4-5/6） | 実actor |
| operation_id | UUID（操作識別子） | — |
| correlation_id | UUID（追跡ID） | — |
| idempotency_key | 一意キー（`MAPPING-2026-07:APPROVE:{mapping_hash}:{actor_id}` 等） | — |
| API証跡 | `POST /api/compliance-gate/approvals` のリクエスト/応答JSON・HTTP status | 実actor |

### 確認クエリ（記録確認のみ・書き込みに使用しない）

```sql
SELECT id, tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash,
       assignment_id, actor_id, action, occurred_at, evidence_document_id, evidence_document_version_id
FROM t_compliance_mapping_approval_event
WHERE tenant_id = 'default' AND actor_id = <実actor id> ORDER BY occurred_at DESC LIMIT 1;
```

## 証跡3（外部専門家Review）の様式

証跡3は `reviewer-verification-decision-delta-r23-p1-01.md` §3.2〜§3.4 のevent順序契約（SUBMITTED→verification→adoption）に従い、以下のAPIで記録する。

| 手順 | API | 記録内容 |
|---|---|---|
| SUBMITTED登録 | `POST /api/compliance-gate/external-reviews` | mapping/group/type・実在資格保有者名・組織・credential（AES-GCM暗号化）・chain id |
| 本人性確認 | `POST /api/compliance-gate/verifications`（kind=IDENTITY） | reviewer subject id・fingerprint snapshot・checked_by（別の人間確認者）・official source・method・exact evidence |
| 資格有効性確認 | 同（kind=QUALIFICATION・frozen flag=trueの時のみ必須） | 同上・registration identifier（暗号化） |
| 業務状態確認 | 同（kind=ACTIVE_STATUS・frozen flag=trueの時のみ必須） | 同上 |
| Review作成者確認 | 同（kind=REVIEW_AUTHORSHIP） | mapping/policy/hash・review chain binding列 |
| adoption | `POST /api/compliance-gate/submitted-reviews/{id}/adoptions/approve` | identity/authorship（＋条件付きqualification/active_status）・exact CLEAN evidence・adopted_at,id |

**確認者は「別の人間確認者」**（Review作成者本人とは別）。資格保有者本人のReview作成・人間確認者による確認はAIが代替しない（§7）。

## 補足

- 証跡4（PDF目視）・証跡5（T066-HISTORY可否＋P1-1版管理）の手順は `t066-m-acceptance-checklist.md` を参照。
- 本様式の記録が揃った後、R10がM PASS判定を行う。
- 新規テンプレート（official source/manual check記録・Phase A/B screenshot manifest・exact evidence記録）は `g2-gate-evidence-templates-r23.md` を参照。
