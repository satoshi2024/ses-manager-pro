# G2 gate証跡 記録様式テンプレート（証跡1/2）

- **作成**: 2026-08-12 / 実装AI
- **目的**: T066 MのG2 gate証跡1（COMPLIANCE_RESPONSIBLE runtime assignment）・証跡2（実actor承認event）を、V102 schema（`t_compliance_responsible_assignment`・`t_compliance_mapping_approval_event`）と整合する形式で記録するための様式。G2 service/APIフェーズ実装後は本様式の値がそのままINSERTされる。
- **参照**: `field-mapping.md` §2.3（コンプライアンス責任者 role と承認状態）、V102 DDL

## 記録時の共通注意

- 自然人はspec/seedへ事前固定しない。実在するsys_user.id・表示名snapshotを記録する。
- mapping version/hashは承認時点の固定値を記録する（現在のfield-mappingはPROVISIONAL_REVIEWED凍結中。FM-C-28追加（P1-1）の版管理判断後にhashを再固定する）。
- 架空資格・推測値の記録は禁止（§3.1 架空資格禁止）。未確定項目はNULLのまま提出せず、取得後に記録する。
- idempotency_key/correlation_id/event_chain_id/operation_id は一度記録した値を再使用しない（V102 UNIQUE/一意制約）。

## 証跡1: COMPLIANCE_RESPONSIBLE assignment（管理者・人間が記録）

### 様式（`t_compliance_responsible_assignment` 準拠）

| 列 | 値 | 記入者 |
|---|---|---|
| tenant_id | `default` | — |
| workplace_id | 対象事業所のid（m_workplace） | 管理者 |
| user_id | 指名する社内ユーザーのid（sys_user、role=HR/法務等の適格者） | 管理者 |
| role_code | `COMPLIANCE_RESPONSIBLE`（固定） | — |
| effective_from | 任命開始日時（DATETIME(6)） | 管理者 |
| effective_to | 交代時までNULL（open）、交代時は終了日時 | 管理者 |
| active_slot | open時 `1`、終了時 NULL（CHECK制約: open=(effective_to IS NULL AND active_slot=1 AND ended_by IS NULL AND end_reason IS NULL)） | 管理者 |
| assigned_by | 任命者（管理者本人のsys_user.id） | 管理者 |
| ended_by / end_reason | 交代時のみ記録 | 管理者 |

### 例

```sql
INSERT INTO t_compliance_responsible_assignment
  (tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by)
VALUES ('default', 1, 42, 'COMPLIANCE_RESPONSIBLE', '2026-08-12 09:00:00.000000', 1, 1);
```

## 証跡2: 実actor承認event（対象mapping version/hashへの承認・人間が記録）

### 様式（`t_compliance_mapping_approval_event` 準拠）

| 列 | 値 | 記入者 |
|---|---|---|
| tenant_id | `default` | — |
| mapping_id | 対象mapping versionのid（m_compliance_mapping_version） | 実actor |
| mapping_version | `MAPPING-2026-07`（FM-C-28版管理判断後の新version表記に従う） | 実actor |
| mapping_hash | **§6.2のcanonical mapping/source payloadのSHA-256（64 hex）**。canonicalizer（G2 service）実装後にDB rowから算出する。**現状はcanonicalizer未実装のため記録不可（fail-closed）** | G2 service実装後 |
| review_policy_hash | 同様に§6.3 canonical payloadのSHA-256（64 hex）。canonicalizer実装後に記録 | G2 service実装後 |
| assignment_id | 証跡1で記録したassignmentのid | 実actor |
| workplace_id_snapshot | 対象事業所のid | 実actor |
| actor_id | 承認者のsys_user.id | 実actor |
| actor_display_name_snapshot | 承認者の表示名（記録時点のsnapshot） | 実actor |
| actor_role_snapshot | 承認時のrole（例: `HR`） | 実actor |
| action | `APPROVE`（/`REJECT`/`REVOKE`） | 実actor |
| event_chain_id | UUID（chain識別子） | 実actor |
| supersedes_event_id | 取消/差戻し時の対象event id | 実actor |
| occurred_at | 承認日時（DATETIME(6)） | 実actor |
| reason | 承認理由（法的根拠・確認内容） | 実actor |
| evidence_document_id / version / hash | 根拠資料（外部Review結果・省令/通知等）のdocument archive参照。**Markdown blob hash（40 hex）は`evidence_document_hash`等のprovenance欄へ記録し、`mapping_hash`と混同しない** | 実actor |
| operation_id | UUID（操作識別子） | — |
| correlation_id | UUID（追跡ID） | — |
| idempotency_key | 一意キー（`MAPPING-2026-07:APPROVE:{mapping_hash}:{actor_id}` 等） | — |

### 例（canonicalizer実装後。現状はmapping_hash/review_policy_hash欄を記録しない）

```sql
INSERT INTO t_compliance_mapping_approval_event
  (tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id,
   workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot,
   action, event_chain_id, occurred_at, reason, operation_id, correlation_id, idempotency_key)
VALUES ('default', 1, 'MAPPING-2026-07', '<64hex-SHA256>', '<64hex-SHA256>', 1,
        1, 42, '山田 太郎', 'HR',
        'APPROVE', '<uuid>', '2026-08-12 10:00:00.000000', '外部専門家Review（資格保有者）の確認済み',
        '<uuid>', '<uuid>', 'MAPPING-2026-07:APPROVE:<64hex>:42');
```

## 補足

- 証跡3（外部専門家Review）は**資格保有者（社労士/弁護士）の実在Review**が別途必須（現状はAI一次照合=条件付き確認）。その結果（承認/指摘対応）をevidence_documentとして証跡2へ紐付ける。
- 証跡4（PDF目視）・証跡5（T066-HISTORY可否＋P1-1版管理）の手順は `t066-m-acceptance-checklist.md` を参照。
- 本様式の記録が揃った後、R10がM PASS判定を行う。
