# NF01 細粒度epoch・CONFIRM初回slice 実施計画（W3-01 NF01-P）

- 作成日: 2026-11-14（r2: 同日、Plan Review `a9e08002` のREQUEST CHANGESを反映。対応表は§13）
- コード調査基準HEAD: `1be03bea7d0780481f4b91be432c766c9340fd69`（`codex/snf-w3-nf01p-20261114`）。本書の`ファイル:行`は、特記がない限りこのHEADの行番号である。W3-02の最終SHA `9f6d7c0e`（`codex/snf-w3-nf01-0-20261114`）で確認した事実は「W3-02（9f6d7c0e）」と明記する。W3-02の統合後、後続バッチは行番号を統合後の状態で引き直すこと。本書のdoc-only commitは基準HEADを更新しない。
- 状態: PLAN（Plan Review r2待ち）。コード・migration・H2 schemaの変更を含まない。Maven/java/dockerは実行していない。本書の事実はすべて静的確認であり、MySQLでの挙動はM系テストで実証するまで推定として扱う。
- 入力: 現行契約 `.kiro/roadmap/2026-10-18-snf-nf01b-execution-contract.md`（以下「現行契約」）、`.kiro/specs/engineer-lifecycle-workflow/**`、利用者決定記録（2026-11-13、末尾の第3波決定を含む）、第3波計画（§0(b)(e)(f)、§1 NF01、§4）、W1-B4 NF01 batch 0 r2 報告、Plan Review `nf01-plan-a9e08002-review.md`、R2レビュー `w3-r2-nf05-08-09-1be03bea-review.md` §4.2（W3-11への注意事項）。
- 対象バッチ: W3-10 epoch A spine、W3-11 外部アカウント失効outbox、W3-15 F1、W3-16 F2、W3-17 F3、W3-18 B0、W3-21 B。
- migration番号: 本書では `V?` と書く。番号はDDL laneが割当時に決める。V1と既存migrationは変更しない（XB-6）。
- 非対象: 本番承認・本番有効化、実在のHR/管理者による二者確認、外部provider接続、NF01以外のNF。

## 0. 本計画で置き換える節

現行契約は変更しない。次の節・文は本計画の該当節で置き換える。対照の詳細は§8。

| 現行契約の節 | 本計画の置換先 |
|---|---|
| §2 第3段落（認可世代tuple `globalAuthorizationEpoch, tenantAuthorizationEpoch, submitterActorEpoch`） | §2 |
| §2 第4段落のうち「証跡候補をdocumentType=`SKILL_SHEET`かつ単一`ENGINEER` linkに限定し、起点writerを`EngineerChangeRequestServiceImpl.confirmSkillSheet`→`DocumentServiceImpl.registerGenerated/link`→`DocumentMetadataCommitService.commitNew`に閉じる」 | §5.1（起点writerの限定は「条件を満たす任意のSKILL_SHEET」へ緩和し、SKILL_SHEETの生成者をguardで固定する。§4.5 U4c） |
| §2 第4段落のうち「公開confirmation routeはこのsliceでは作らず」「全document ACL predicateと全attachment writerの閉包…までCONFIRMを有効化せず」 | §5.1、§5.11 |
| §3 第1段落のうち「`DOCUMENT_LINK`だけでなく、…全evidence typeが同じfacadeを通る。evidence type別の認可迂回を認めない」 | §5.1、§9 RR-8（初回sliceでfacadeを通るのはDUAL_CONFIRMATIONだけ） |
| §4 第1段落の最終文「通常証跡を含む非DUALの文書証跡も新facadeを経由させる」 | 同上 |
| §4 第3段落の保存項目のうち tenant epoch | §5.12 |
| §6 全体（lock順(1)〜(8)、表の global／tenant authorization epoch／active actor／actor authorization epoch／sales DataScope／manager・engineer・asset scope／typed target 各行） | §2、§3、§4 |
| §7 の Epoch fence、Current reads/lock plan、末段のCONFIRM解放gate | §5、§6 |
| §8 後続NF01→NF09 durable revoke request | §5.13（account generation・送信状態・ACK前crashの採否は§8の該当行） |
| §9.1 表の `t_authorization_tenant_epoch`・`t_actor_authorization_epoch` 行、tenant epoch backfill段落 | §2.3 |
| §9.2 表の global DataScope config・tenant scope・actor status/role・lifecycle generation 各行 | §4 |
| §9.3 第2段落のlock順とepoch照合項目 | §3.4、§5.5 |
| §9.5 batch 1〜6 | §7 |

置き換えずに引き続き有効とする部分: §1の事実記述、§3のうちexact version・CLEAN・storage検証・漏えい禁止・latest fallback禁止（第1段落の上記の文を除く）、§4の状態・冪等性・不変証跡の原則（第1段落の上記の文を除く）、§5のgeneration/current selectionの原則、§9.1のhistory表のFK方針（tenant複合FK、親削除cascadeにしない）、§9.3のcoordinator境界（`NEVER` + 毎回 `REQUIRES_NEW` + `READ_COMMITTED`、最大3回）、§9.4。

## 1. 結論の要約

1. tenant単位のepochとtenant root lockを廃止する（B-NF01-6）。代わりに global（role menuと認可config、Q-3）、actor（`sys_user`行）、engineer（`t_engineer`行）、組織部分木（`m_organization_unit`行）、document（`t_document`行）、task generation の6種を持つ。global以外は既存の業務行そのものをlock anchorにし、epochはその行の新列（DEFAULT 0）に置く。tenant epoch表・actor epoch表・tenant provision手順は不要になる。
2. CONFIRMのscope述語がENGINEER対象で読む行は `t_engineer_sales`、`t_engineer_account_link`、組織（所属・組織階層・要員の組織帰属）と連携ユーザーの `sys_user` だけである（DataScopeServiceImpl.java:115-136,281-290、LifecycleScopeService.java:78-282、DocumentServiceImpl.java:1481-1484、EngineerAccountLinkMapper.java:24-61）。したがってfenceするwriterはこれらと sys_user・権限割当・文書・lifecycle自身に閉じる。契約・提案・案件・営業活動・顧客・資産は述語に入らないため対象外とする（§4.4）。
3. lock順は現行の `LifecycleLockPlan.Rank`（LifecycleLockPlan.java:16-38）を基に、tenant/actor epoch rankを削除し、承認申請をsys_userより前に置き、scope子行・portal link・外部アカウント参照のrankを追加する（§3.1）。batch 0のL1〜L4の順序はそのまま保持し、L5（completeCase倒置）はW3-16bで並べ替える。
4. CONFIRMの初回sliceは `SKILL_SHEET` × 単一 `ENGINEER` link（caseの要員）× 1提出1文書だけを受け付け、他の文書種別・link形・evidence_typeは明示的に拒否する（§5.1、Q-2）。確認者は管理者とHRで提出者と別人（B-NF01-5）。1213/1205は3回の試行予算に含め、epoch不可用は409とする。冪等性の判定はtask状態の判定より前に置く（§5.5）。
5. 退社完了では、要員に割り当てられた未失効の外部アカウントを全法人から列挙し、各行自身の法人で同一transaction内に失効要求として登録する。gateは「登録済み・登録可能」をブロックしない（Q-6）。登録と送信を別状態にし、poll対象から未送信行を外す。keyにはgenerationを入れ、UNKNOWN後の再要求を可能にする（§5.13）。
6. 実装はW3-10 → {W3-15, W3-16a, W3-17} → W3-16b → W3-21 の順。W3-15（F-1を含む）はW3-18・W3-21aより前に統合する。W3-16とW3-21はa/bに分割する（§7）。

## 2. 粒度設計

### 2.1 前提: CONFIRMの述語が読む行

確認時に再評価する述語と、その述語が読む行を次に整理する。epochは「この行の変化を、提出から確認までの間に見逃さない」ための世代であり、述語自体の再評価を置き換えない。

| 述語 | 読む行（現行コード） | 守るepoch |
|---|---|---|
| (a) 主体の有効性 | `sys_user` の `status=1`・`deleted_flag=0`・`tenant_id`・`role`（LifecycleScopeService.java:37-54） | actor |
| (b) role→menu | `t_role_menu` と `m_menu`（tenant列なし、全tenant共通）。管理者はMenuPermissionFilterを迂回 | global |
| (c) action権限 | `t_user_permission_group`、権限group・action（AuthorizationServiceImpl.java:41-93）。group・action定義の実行時writerは存在しない（migration seedのみ） | actor（割当） |
| (d) case/task | `t_lifecycle_case`（tenant・status）、`t_lifecycle_task`（assignee_user_id・assignee_role・is_engineer_visible・status） | case/task行のX lock、task generation |
| (e) lifecycle scope | 管理者/HR: tenant一致のみ（LifecycleScopeService.java:126-128,166-168,243-245）。マネージャー: `t_engineer.organization_id ∈ 子孫(P)`、Pは本人の有効主所属（同:136-144、OrganizationScopeServiceImpl.java:103-158、OrganizationServiceImpl.java:360-）。営業: `t_engineer_sales(released_at IS NULL)`（LifecycleScopeService.java:147-154）。要員: `t_engineer_account_link`（同:170-173）と担当task（同:248-254） | engineer、組織、actor |
| (f) 文書ACL（SKILL_SHEET、ENGINEER link） | DocumentServiceImpl.java:1422-1534 のENGINEER分岐（:1481-1484）→ DataScopeServiceImpl.assertAllowedEngineer → allowedEngineerIds（DataScopeServiceImpl.java:115-136）。要員=account link（:138-146）、組織scope対象=EngineerAccountLinkMapper.java:15-61（`t_engineer_accounting_history`→`t_engineer.organization_id`→連携ユーザーの主所属、`manager_user_id` 経由の直属ユーザー、および連携ユーザーの `sys_user.deleted_flag`・`tenant_id`（:29,40））、営業（`scope.sales-own-data-only=true`時、DataScopeServiceImpl.java:97-101）=`t_engineer_sales` のみ（:281-290） | engineer、組織、actor、global（config） |
| (g) 文書の利用可否 | `t_document`（type・status・legal_hold_flag・tenant・deleted）、`t_document_link`、`t_document_disposal_request`、`t_document_version`（scan_status・sha256・storage_key） | document |
| (h) 確認者 | (a)(b)(c)、roleが管理者またはHR、提出者と別ID。文書ACLは管理者=全許可、HRはSKILL_SHEETがHR拒否種別（DocumentServiceImpl.java:1438-1442）外かつDataScope非対象のため許可 | actor、global |

結論: ENGINEER対象のscope（(e)(f)）が読むのは `t_engineer_sales`、`t_engineer_account_link`、組織（`t_user_organization`、`m_organization_unit`、`t_organization_relation_history`、`t_engineer.organization_id`、`t_engineer_accounting_history`）、連携ユーザーの `sys_user` に限られる。DataScopeServiceImpl の computeContractIds・computeProposalIds・computeProjectIds・computeCustomerIds は allowedEngineerIds から呼ばれない。

### 2.2 epoch一覧

| epoch | 格納先 | lock anchor | tenantとの関係 | reader（CONFIRM attempt） | writer | 増分する変化 | 増分しない変化 |
|---|---|---|---|---|---|---|---|
| global | `t_authorization_global_epoch(singleton_id=1).epoch`（新表） | 同行 | 持たない（Q-3で全体失効を受容） | `FOR SHARE` | 最初のlockとして `FOR UPDATE` | role menuの集合変化、認可configキーの値変化（§4.1） | 非認可キーの更新、同一内容の再保存 |
| actor | `sys_user.actor_authz_epoch`（新列） | `sys_user`行 | `id + tenant_id` で読む。tenant移動は禁止（§11 F-1） | `FOR SHARE`（提出者・確認者） | `FOR UPDATE` | status・role・deleted_flag・tenant_idの変化、権限group割当集合の変化、本人の `t_user_organization` 行の変化、本人を上長とする所属行の `manager_user_id` 解除（clearManager） | password、real_name、email、failed_count、locked_until、password_change_required |
| engineer | `t_engineer.engineer_scope_epoch`（新列） | `t_engineer`行 | `id + tenant_id` | `FOR SHARE` | `FOR UPDATE` | `t_engineer_sales`・`t_engineer_account_link`・`t_engineer_accounting_history` の行変化、`t_engineer` の organization_id・tenant_id・legal_entity_id・deleted_flagの変化、連携ユーザーの `t_user_organization` 行の変化 | status・skill等の他列（UPDATEのX lockで直列化だけ行われる） |
| 組織部分木 | `m_organization_unit.org_scope_epoch`（新列） | `m_organization_unit`行 | 依存しない（文字列tenantなし。数値 `tenant_id` は使わない） | `FOR SHARE`（提出者がマネージャーの場合の有効主所属組織の集合） | `FOR UPDATE`（旧・新の祖先鎖をid昇順） | parent_id・status・valid_from・valid_to・merged_into・deleted_flagの変化、`t_organization_relation_history` の追記・更新、統合。変化した組織自身と、旧・新の祖先鎖（自身を含む）を各1回 | name・code・type等の表示属性 |
| document | `t_document.evidence_eligibility_epoch`（新列、現行契約§5と同名） | `t_document`行 | `id + tenant_id` | `FOR SHARE` | `FOR UPDATE` | link集合の変化、legal_holdの設定・解除、disposal requestの状態変化、DISPOSED、deleted、tenant変化、版のscan状態変化（現行writerなし） | 版追加、DRAFT→CONFIRMED、CONFIRMED→AMENDED、link付帯情報（skill_sheet_confirmed_*） |
| task generation | `t_lifecycle_task.task_generation`（新列、現行契約§9.1と同名） | `t_lifecycle_case`行（→task行） | caseの tenant_id | case `FOR UPDATE` → task `FOR UPDATE` | 同左 | 案件保留、案件取消、task担当変更、task免除 | 着手、再開、非DUALの完了、訂正、後続昇格 |

組織部分木epochの意味: 組織PのepochはPを根とする部分木（子孫集合と各組織の有効性）が変わるたびに増える。子孫集合の変化は、変化した組織の祖先すべての子孫集合を変えるので、writerは旧・新の祖先鎖の全組織を増やす。readerは提出者の主所属組織だけをlockすればよい。

連携ユーザーの所属変化を engineer epoch に含める理由: 要員の組織帰属は、会計履歴も `t_engineer.organization_id` も無いときに連携ユーザーの主所属へフォールバックし（EngineerAccountLinkMapper.java:34-37,44）、マネージャーの直属ユーザー経路（`manager_user_id`、同:49-52）も連携ユーザーの所属行を読む。いずれもその要員に対する述語だけを変えるので、所属行のwriterが連携要員のengineer epochを増やせば足りる。例外として `UserOrganizationMapper.clearManager`（OrganizationServiceImpl.java:643、UserOrganizationMapper.java:68-70）は他人の所属行から上長Uを外すが、影響はU自身（上長としての直属経路）の述語だけであり、呼出元の `closeAssignmentsForUser` がUのactor epochを増やすので覆われる。

連携ユーザーの `sys_user`（`deleted_flag`・`tenant_id`、EngineerAccountLinkMapper.java:29,40）: 連携中ユーザーの削除は拒否され（SysUserServiceImpl.java:156-158）、tenant変更はF-1で拒否する。したがってengineer epochの増分契機に加えない。この2つの前提はU4aで固定する。

### 2.3 DDL草案（epoch A、番号なし `V?`）

```sql
-- 全体epoch（tenantを持たないwriter用。Q-3）
CREATE TABLE t_authorization_global_epoch (
  singleton_id TINYINT     NOT NULL PRIMARY KEY COMMENT '常に1',
  epoch        BIGINT      NOT NULL DEFAULT 0 COMMENT '全体認可世代（単調増加）',
  updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新日時',
  CONSTRAINT ck_authz_global_singleton CHECK (singleton_id = 1),
  CONSTRAINT ck_authz_global_epoch_nonneg CHECK (epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全体認可epoch';
INSERT INTO t_authorization_global_epoch (singleton_id, epoch) VALUES (1, 0);

-- 既存行をanchorにする細粒度epoch（全停止・空DB新規導入のためbackfillはDEFAULTで足りる）
ALTER TABLE sys_user
  ADD COLUMN actor_authz_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '利用者単位の認可世代',
  ADD CONSTRAINT ck_sys_user_actor_authz_epoch CHECK (actor_authz_epoch >= 0);
ALTER TABLE t_engineer
  ADD COLUMN engineer_scope_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '要員単位のscope世代',
  ADD CONSTRAINT ck_engineer_scope_epoch CHECK (engineer_scope_epoch >= 0);
ALTER TABLE m_organization_unit
  ADD COLUMN org_scope_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '組織部分木単位のscope世代',
  ADD CONSTRAINT ck_org_scope_epoch CHECK (org_scope_epoch >= 0);
ALTER TABLE t_document
  ADD COLUMN evidence_eligibility_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '証跡利用可否の世代',
  ADD CONSTRAINT ck_document_evidence_eligibility_epoch CHECK (evidence_eligibility_epoch >= 0);
ALTER TABLE t_lifecycle_task
  ADD COLUMN task_generation BIGINT NOT NULL DEFAULT 0 COMMENT 'タスク世代（旧提出の失効用）',
  ADD CONSTRAINT ck_lifecycle_task_generation CHECK (task_generation >= 0);
```

- 列にする理由: DEFAULT 0 により、要員・利用者・文書・組織を作るすべての既存経路（CSV取込、履歴書取込、BP空き取込、利用者作成、文書登録等）にseed義務を負わせずに済む。別表にすると全insert経路へのseed追加が必要になり、漏れがfail-closedの過剰拒否または欠落になる。
- tenant epoch表・actor epoch表・tenant registryは作らない。現行契約§9.1のtenant seed候補・`m_organization_unit` 数値tenantのmapping承認は、epoch Aの前提条件から外れる（preflightの `ORG_UNIT_TENANT_MAPPING` が `BLOCKED_MAPPING` でもepoch Aを止めない）。
- triggerは追加しない。B0の不変表のtriggerは§5.12で扱う。
- H2: 同じ表・列・CHECKを `src/test/resources/sql/engineer-schema-h2.sql`（sys_user、t_engineer、m_organization_unit）、`schema-document-archive-h2.sql`（t_document）、`schema-lifecycle-workflow-h2.sql`（t_lifecycle_task）へ追随させ、`application-test.yml` の `schema-locations` に新migrationまたはH2版（`ALTER ... ADD COLUMN ..., ADD CONSTRAINT` をH2が解釈できない場合）を登録する。

増分SQLの形（W3-10の `AuthorizationEpochMapper`）:

```sql
-- writer: 同一txで当該anchorを FOR UPDATE 済みであることが前提
UPDATE t_engineer
   SET engineer_scope_epoch = engineer_scope_epoch + 1, updated_at = updated_at
 WHERE id = #{id} AND tenant_id = #{tenantId} AND engineer_scope_epoch < 9223372036854775807;
-- reader: CONFIRM attempt内
SELECT id, tenant_id, deleted_flag, organization_id, engineer_scope_epoch
  FROM t_engineer WHERE id = #{id} AND tenant_id = #{tenantId} FOR SHARE;
```

### 2.4 増分の共通規則

- E-1 同一transaction: 述語を変える書込みと増分は同じDB transactionで行う。afterCommitの `ScopeChangeInvalidator`・`MenuCacheService.invalidate()` は代替にならない（既存のcache失効は残す）。
- E-2 anchor先行: writerはanchor行を `FOR UPDATE` で取ってから旧値をlocking readで読み、変更し、増分する。
- E-3 discoveryを伴うwriterは `READ_COMMITTED` に固定する。対象: 組織階層writer（save・updateOrganization・updateStatus・deactivate・removeById）、merge、所属writer（assignUser・updateUserOrganization・transferUser・releaseAssignment・closeAssignmentsForUser）、`EngineerServiceImpl.removeById`、`SysUserServiceImpl.updateUserStatus`・`deleteUser`（所属closeを内包する）、`LifecycleCaseServiceImpl.completeCase`。理由: RRでは最初のconsistent readでread viewが固定され、anchor取得後の通常SELECT（`validateOrganization`、`descendantIds`、`validateAssignment` 等のMyBatis-Plus読取）が古いsnapshotのまま祖先鎖や依存集合を「一致」と判定し、増分を漏らす。RCにすると `selectByUserForUpdate` 等のgap lockも取らない。discoveryの無いwriterはanchor取得を最初の文にする。H2の `@Transactional` テストでは外側transactionに参加して分離レベル指定が無視されるため、分離レベルは実行時assertではなく静的guard（§4.5 U15）で固定する。
- E-4 no-opは増やさない。1transactionで同じanchorは1回だけ増やす（`AuthorizationEpochFence` がtransaction-bound集合で重複を除く。例: updateUser→replaceAssignments の入れ子）。
- E-5 減らさない。regrant・解除・却下でも増やし、旧提出を復活させない。
- E-6 上限 `Long.MAX_VALUE` では増分せず、更新ごと409 `error.authorization.epochUnavailable` でrollbackする。
- E-7 entityにmapしない: epoch列とtask_generationは `SysUser`、`Engineer`、`OrganizationUnit`、`Document`、`LifecycleTask` entityに追加しない。追加すると `updateById(entity)` が読取時の値を書き戻して増分を巻き戻す。読書きは `AuthorizationEpochMapper`（および `LifecycleTaskMapper` の専用select）の明示SQLだけにする。
- E-8 `updated_at` を変えない（`updated_at = updated_at`）。MySQLは明示代入でON UPDATE CURRENT_TIMESTAMPを抑止する（sys_user.updated_atはON UPDATE付き、V1__create_tables.sql:102）。epochの増分で一覧の更新日時やcache versionが動かないようにする。
- E-9 `@Version` 列は触らない。楽観ロックとepochは独立。

### 2.5 読取方式

- readerはCONFIRMのsubmit/confirm attempt（`REQUIRES_NEW` + `READ_COMMITTED`）だけである。global・sys_user・組織・engineer・document・versionを `FOR SHARE`、case・taskを `FOR UPDATE` で取る。
- 子行（engineer_sales、account link、所属、会計履歴、relation history、document link、disposal request）はreaderがlockしない。anchorのS lockを保持している間、子行のwriterはanchorのXを取れないので、READ_COMMITTEDの通常読取で現在値が安定する。これがwriterに「anchor Xを先に取る」義務を課す理由であり、§4.5の静的guardで担保する。
- writerは `FOR UPDATE`。S→X昇格を避けるため最初からXを取る。
- H2: H2が `FOR SHARE` を解釈しないものとして扱う。reader SQLはMySQLテストだけで実行し、fast testはreaderをmockする。writer SQL（`FOR UPDATE`、UPDATE）はH2でも動くので、既存のH2 `@SpringBootTest` はwriter改修後も動く。

### 2.6 tenantとの関係

- tenant単位のepochとtenant root lockは使わない（B-NF01-6）。現行契約§6の「tenant root先行lock」、§9.1の「tenant epoch行のseed・新tenant provision手順」は不要になる。
- 各anchorは自身のtenant列で照合する（`id + tenant_id`）。行が無い、tenantが一致しない、deletedの場合はanchor不在としてfail-closedにする。
- globalはtenantを持たない（Q-3）。
- 組織はtenantに依存しない。tenantとの結び付きは提出者の主所属を `t_user_organization.tenant_id` で解決する時点で行う。`m_organization_unit.tenant_id`（nullable BIGINT、V1__create_tables.sql:547-）を文字列tenantへ変換しない（現行契約§9.1の禁止を維持）。tenant間で組織を共有している場合は過剰失効になるだけで、失効漏れは起きない。
- tenant移動: `t_engineer`・`t_engineer_account_link` のtenant補正は修復経路（OwnershipRepairServiceImpl.java:89-97）に限り、anchorをX lockして増分する。`sys_user` のtenantは作成時に操作者のtenantへ固定し、更新での変更を拒否する（§11 F-1、W3-15）。
- `/api/users` は一覧・取得・更新・削除がtenant境界を持たない（UserApiController.java:42-55,87、SysUserMapper.java:47-48）。本計画は単一tenant配備を前提とし、多tenant公開の前に横断バッチで `/api/users` をtenant化する（§9 RR-14、§10）。

## 3. lock順

### 3.1 完全なrank順

| 順 | rank（LifecycleLockPlan.Rank） | 行 | reader | writer | 備考 |
|---|---|---|---|---|---|
| 1 | GLOBAL_AUTHORIZATION_EPOCH | `t_authorization_global_epoch` | S | X（最初） | global writer以外は触らない |
| 2 | APPROVAL_REQUEST（新） | `t_approval_request` | – | X | 承認確定は申請行を最初にlockする（ApprovalEngineServiceImpl.java:419）。承認adapterがsys_userを書く経路（EngineerChangeRequestApprovalAdapter）があるため、sys_userより前に置く |
| 3 | SYS_USER | `sys_user`（actor epoch同居） | S | X | 旧 ACTOR_AUTHORIZATION_EPOCH を統合 |
| 4 | LIFECYCLE_CASE | `t_lifecycle_case` | X | X | |
| 5 | LIFECYCLE_TASK | `t_lifecycle_task` | X | X | 必ずcase X保持下。同case内はid昇順 |
| 6 | ORGANIZATION | `m_organization_unit` | S | X | 祖先鎖はid昇順 |
| 7 | ENGINEER | `t_engineer` | S | X | typed targetとscope根拠で同じ行は1回 |
| 8 | USER_ORGANIZATION（新） | `t_user_organization` | – | X | 子行 |
| 9 | ENGINEER_ACCOUNT_LINK（新） | `t_engineer_account_link` | – | X | 子行 |
| 10 | ENGINEER_SALES（新） | `t_engineer_sales` | – | X | 子行 |
| 11 | ENGINEER_ACCOUNTING_HISTORY（新） | `t_engineer_accounting_history` | – | X | 子行 |
| 12 | PORTAL_ENGINEER_LINK（新、W3-02の表） | portal利用者と要員のlink | – | X | 子行。epochなし（§5.14）。W3-02（9f6d7c0e）は 7 → 12 で実装済み |
| 13 | CUSTOMER → CONTRACT → PROPOSAL → PROJECT → SALES_ACTIVITY → ASSET → ASSET_ASSIGNMENT → ACCEPTANCE → SERVICE_REQUEST（既存） | 各業務親 | – | X | 初回sliceのreaderは取らない。資産は asset→assignment（AssetAssignmentServiceImpl の現行順） |
| 14 | EXTERNAL_ACCOUNT_REFERENCE（新、W3-11） | `t_external_account_reference` | – | X | 退社完了時・退社後登録時の失効要求登録 |
| 15 | DOCUMENT | `t_document` | S | X | |
| 16 | DOCUMENT_VERSION | `t_document_version` | S | X（insert） | |
| 17 | DOCUMENT_LINK | `t_document_link` | – | X | readerはdocument Sで保護 |
| 18 | DOCUMENT_DISPOSAL_REQUEST | `t_document_disposal_request` | – | X | 同上 |
| 葉 | B0の各表、`t_lifecycle_event`、`t_lifecycle_evidence_link`、`t_user_session`、portal session、通知、監査、`t_role_menu`、`m_system_config`、`t_user_permission_group` | – | 最後に書く | 葉の後にrank行を取らない |

削除するrank: TENANT_AUTHORIZATION_EPOCH（tenant epoch廃止）、ACTOR_AUTHORIZATION_EPOCH（sys_user行へ統合）。

### 3.2 batch 0のL1〜L7との対応

| ID | batch 0の状態（基準HEAD） | 本計画での位置付け |
|---|---|---|
| L1 | task経路を case X → task X に統一済み（LifecycleTaskServiceImpl.java:564-585） | 順4→5として維持 |
| L2 | 承認確定は 申請行 → case → task（LifecycleExceptionApprovalAdapter、AssetOffboardingServiceImpl.approveOffboardingWaiver :126-） | 順2→4→5。APPROVAL_REQUEST rankを新設して明文化 |
| L3 | 後続昇格は後続∪先行をcase lock下でid昇順（LifecycleTaskServiceImpl.java:507-562,587-599） | 順5内。CONFIRMのconfirm attemptでは後続・先行taskのIDもdiscoveryに含め、順5でまとめてlockする（順6以降を取った後で順5へ戻らない） |
| L4 | cancelCaseは case X → task id昇順、CAS 0件は409（LifecycleCaseServiceImpl.java:771-829） | 順4→5。W3-16bでtask generationの増分を追加 |
| L5 | completeCaseは case X の後に sys_user・所属・link・営業を更新（:669-734 → ResignationGateChecker.java:276-328）。倒置は未修正 | W3-16bで discovery → 順3（操作者S、連携ユーザーX）→4→5→7→8→9→10→12（読取）→14→葉 に並べ替える（§3.4） |
| L6 | portal利用者をcase tenantで照合（ResignationGateChecker.java:347-360） | W3-02（9f6d7c0e）でportal linkに置換済み（B-NF01-8、Q-10）。順7（engineer X）でlink集合を安定させる（§5.14） |
| L7 | 重複退社guardが非lock（LifecycleCaseServiceImpl.java:83-90） | W3-02が新migrationのUNIQUE制約（生成列）へ置換。DB制約なのでlock順に入らない |

### 3.3 LifecycleLockPlanの変更（W3-10）

- `Rank` を§3.1の並びへ変更する。既存テスト `LifecycleCanonicalizationTest.java:116-160` は新rankで更新する。
- `LockKey` に `Mode {SHARED, EXCLUSIVE}` を追加する。plan構築時に同じ `(rank, partition, id)` を1つに畳み、どれかがEXCLUSIVEならEXCLUSIVEにする（実行時の昇格を構造的に無くす）。`requireSameDependencies` はkeyとmodeの両方を比較する。
- `partition` はtenant行ではtenant_id、GLOBALとORGANIZATIONでは空文字。
- rankごとのlocking SQLを持つ `LifecycleLockExecutor`（新）を設ける。W3-10では定義とMySQLテストだけで、業務経路からは呼ばない。
- `LifecycleAuthorizationEpochs(global, tenant, submitterActor)`（LifecycleAuthorizationEpochs.java:4）を `LifecycleAuthorizationFence(global, submitterActor, engineer, SortedMap<組織ID, epoch> organizations)` に置き換える。文書のepochは既存の `LifecycleCanonicalization.DocumentVersionSnapshot.evidenceEligibilityEpoch` に残す。

### 3.4 経路別のlock列

| 経路 | lock列（順番号は§3.1） | 増分 |
|---|---|---|
| CONFIRM submit attempt | 1 S → 3 S{提出者} → 4 X{case} → 5 X{task} → 6 S{提出者の有効主所属（マネージャーかつ組織scope有効時）} → 7 S{case要員} → 15 S{文書} → 16 S{版} → link・disposalは通常読取 → 葉（B0） | なし |
| CONFIRM confirm attempt | 1 S → 3 S{提出者, 確認者}（id昇順）→ 4 X → 5 X{task, 後続, 後続の先行}（id昇順）→ 6 S{提出時の組織集合 ∪ 現在の組織集合} → 7 S → 15 S → 16 S → 葉（confirmation、projection、event、evidence link） | なし（task COMPLETEDはtask行の更新） |
| completeCase（RESIGNATION、W3-16b） | discovery → 3 S{操作者}・X{連携ユーザー}（id昇順、同一IDなら拒否）→ 4 X → 5 X{全task} → 7 X{要員} → 8 X{連携ユーザーの所属} → 9 X{account link} → 10 X{現任の営業割当} → 12 読取{portal link} → 資産貸与・license・経費は読取 → 14 X{外部アカウント（全法人）} → 葉（session失効、portal session失効、event、afterCommit通知、afterCommit失効要求送信） | actor（連携ユーザー）、engineer |
| holdCase / cancelCase | 4 X → 5 X{非終端task、id昇順} | task generation |
| resumeCase | 4 X | なし |
| reassignTask | 3 S{新担当（requestで既知）} → 4 X → 5 X | task generation |
| waiveTask（承認経由） | 2 X → 4 X → 5 X | task generation |
| 変更申請の承認（profile） | 2 X → 3 X{連携ユーザー、email列だけ} → 7 X{要員} → 11 | engineer（会計履歴・組織が変わった時） |
| role menu・認可config | 1 X → 葉 | global |
| 利用者の更新・状態・削除・権限割当 | 3 X{利用者} →（所属を閉じる場合）7 X{連携要員} → 8 X → 葉 | actor（＋engineer） |
| 営業割当・account link | 3 S{営業ユーザー/連携ユーザー} → 7 X → 9 または 10 | engineer |
| 要員の組織変更 | 7 X → 11 | engineer |
| 所属writer（assign・transfer・update） | 3 X{本人} → 6 X{対象組織（旧・新）} → 7 X{本人の連携要員} → 8 X | actor（本人）、engineer（連携要員） |
| 所属writer（release・closeAssignmentsForUser） | 3 X{本人} → 7 X{本人の連携要員} → 8 X（closeは上長として登録された他人の行のclearManagerを含む） | actor（本人）、engineer（連携要員） |
| 組織階層writer | 6 X{自身と旧・新祖先鎖、id昇順} → 葉（relation history） | 組織部分木 |
| 組織統合（merge） | 3 X{影響ユーザー} → 6 X{統合元・統合先・子・祖先鎖} → 7 X{統合元所属の要員・影響ユーザーの連携要員} → 8 X → 11 → 葉（原価部門・予算） | 組織部分木、actor、engineer |
| 外部アカウントの新規登録（W3-11） | 7 X{割当先要員} → 14 insert（退社完了済みなら同txで失効要求登録） | なし |
| 文書writer | （typed parentを持つ呼出元はそのrank）→ 15 X → 16/17/18 | document |

所属writerのうち組織行（6 X）を取るのは assign・transfer・update だけで、merge・updateStatusと直列化するためである。release・closeは対象組織を変えないので6を取らない。

### 3.5 共通規則

1. 上位rankを取った後に下位rankを取らない。不足に気付いたら途中で足さず、CONFIRMはrollbackして新attempt、writerは409で終える。
2. 同じ行は1transactionで1mode。writerは最初からXを取る。
3. 同rankは `(partition, id)` 昇順。task行は常にcase X保持下で取るので、同case内の順序は安全側（昇順）に揃える。
4. ENGINEERは同じ行を1回だけ取る。
5. 葉の後にrank行を取らない。
6. `NOT_SUPPORTED`/`REQUIRES_NEW` の内側呼出しの前にlockを取らない（suspendしてもlockは保持される。現行契約§9.2末段を維持）。
7. E-3（discoveryを伴うwriterはREAD_COMMITTED）。
8. 既知の例外（CONFIRMの述語外）: `AssetAssignmentServiceImpl.waiveAssignment` は asset → assignment → 承認申請（:215-240、:277）の順でlockし、順13の後に順2を取る。CONFIRM readerは承認申請を取らないのでCONFIRMとは循環しないが、他の承認経路との関係は未確認である（§11 F-9）。

## 4. writer一覧

各表の「lock列」は変更後の順序（§3.1の順番号）である。「増分」はそのwriterが同一transactionで進めるepoch。

### 4.1 F1: global・actor（W3-15）

| クラス.メソッド（ファイル:行） | 現在の処理 | 変更後のlock列 | 増分 | 備考 |
|---|---|---|---|---|
| `RoleMenuServiceImpl.replaceMenus`（src/main/java/com/ses/service/impl/RoleMenuServiceImpl.java:40-91） | 検証 → DELETE → saveBatch → cache失効 | 1 X を最初 → 旧集合のread → 置換 | global（集合が変わった時） | 呼出元 `RoleMenuApiController.update` |
| `SystemConfigServiceImpl.updateAll`（src/main/java/com/ses/service/impl/SystemConfigServiceImpl.java:272-308） | 旧値read → 各keyのput → afterCommit失効 | payloadに認可キーがあれば、旧値readの前に 1 X | global（認可キーの値が変わった時） | 認可キーの台帳 `AuthorizationConfigKeys`（新）。初期値は `scope.sales-own-data-only` だけ（DataScopeServiceImpl.java:40,100） |
| `SystemConfigServiceImpl.put`（同:202-265） | 単一keyのupsert | 認可キーなら「同一txで1 X取得済み」を必須にし、未取得は `IllegalStateException` | （updateAllでまとめて1回） | 認可キーを他経路でput・直接mapper更新することを禁止（§4.5） |
| `SysUserServiceImpl.createUser`（src/main/java/com/ses/service/impl/SysUserServiceImpl.java:47-71） | protectForCreate → save → replaceAssignments | bodyのtenantIdを無視して操作者のtenantへ設定 → insert → replaceAssignments内で 3 X | actor（1回） | `EntityProtectUtil.protectForCreate`（EntityProtectUtil.java:12-26）はtenantIdを消さない（§11 F-1） |
| `SysUserServiceImpl.updateUser`（同:73-125） | 検証 → `selectByIdForUpdate`（:95）→ updateById → role変更時にreplaceAssignments | 3 X を最初の文へ移す → bodyのtenantIdが旧値と異なれば400 → 更新 | actor（role・deletedの変化、入れ子のreplaceAssignmentsと合わせて1回） | §11 F-1 |
| `SysUserServiceImpl.updateUserStatus`（同:127-149） | 非lockで `updateById` → 所属close → session失効 | READ_COMMITTED。3 X → 所属close（F2の所属writerが7・8を取る）→ 葉 | actor（status変化時） | |
| `SysUserServiceImpl.deleteUser`（同:151-166） | guard → removeById → 所属close → session失効 | READ_COMMITTED。3 X → 同上 | actor | 連携済みユーザーの削除拒否（:156-158）は維持 |
| `PermissionGroupManagementServiceImpl.replaceAssignments`（src/main/java/com/ses/service/security/impl/PermissionGroupManagementServiceImpl.java:60-102） | `selectByIdForUpdate`（:71）→ delete/insert → session失効 → 監査 | 3 X（既存） | actor（割当集合が変わった時） | updateUser・createUserから入れ子で呼ばれても1回 |
| `EngineerChangeRequestApprovalAdapter.applyProfile`（src/main/java/com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java:160-184、sys_user更新 :172-175） | 連携ユーザーを非lockで `selectById` → `sysUserMapper.updateById(u)` で全列を書き戻す → `engineerService.updateWithStatusGuard` | （承認確定の2 X保持下）→ 3 X → email列だけの `UpdateWrapper` → 7 X（updateWithStatusGuard） | なし（emailは認可列ではない） | 全列の書戻しは、読取と書込みの間に管理者が行った無効化・role変更を黙って元に戻す（SysUserに `@Version` が無い）。batch 0がResignationGateCheckerで直した型と同じ（§11 F-7） |
| `ProfileApiController.changePassword` 相当（src/main/java/com/ses/controller/api/ProfileApiController.java:52-57） | sparse entityで `sysUserService.updateById(update)`（password・password_change_required） | `SysUserService` に列限定の専用メソッドを設けて呼ぶ | なし | 継承した `IService` の書込みをcontrollerから直接呼ぶ形を無くし、§4.5 U4aで検出可能にする |

reader側の部品もW3-15で作る: `ExplicitActorAuthorizationEvaluator`（新）。

- SecurityContextを使わず、DB上の明示actor（tenant、user ID、role、基準日）で (a)〜(f) を評価する。内容は LifecycleScopeService の assertCanEditTask・canViewCase・isTaskVisibleToUser、OrganizationScopeServiceImpl の allowedOrganizationIds・allowedDirectUserIds・allowedEngineerIds、DataScopeServiceImpl の allowedEngineerIds、menu（role→menu、管理者は迂回）、`AuthorizationService.isAllowed` の明示actor版（ActionPermissionResolver が提出routeから導く action key）である。
- configとmenuは、attempt内でglobal行のS lockを保持した状態で `m_system_config`・`t_role_menu`・`m_menu` から直接読む。`SystemConfigServiceImpl` のcache（afterCommitで失効、:241-256）、`MenuCacheService`、`ReportScopeContext`（OrganizationScopeServiceImpl.java:88-91,104-109）、DataScopeのrequest cacheを使わない。
- 既存サービスの判定と同じ結果になることと、cacheを経由しないことをparityテスト（U6）で固定する。既存サービス自体の挙動は変えない。

### 4.2 F2: scope（W3-16a 要員・組織、W3-16b lifecycle）

W3-16a（要員・組織）:

| クラス.メソッド（ファイル:行） | 現在の処理 | 変更後のlock列 | 増分 | 備考 |
|---|---|---|---|---|
| `EngineerSalesServiceImpl.assign`（src/main/java/com/ses/service/impl/EngineerSalesServiceImpl.java:52-84） | 営業ユーザーを非lock read → 現任一覧 → save | 3 S{営業ユーザー} → 7 X → 10 | engineer | 重複判定はanchor取得後に行う |
| `setPrimary`（同:86-97） | 現任降格 → 更新 | 7 X → 10 | engineer | primary_flagは述語に入らないが、子行の変化は一律に増やす（規則を単純に保つ） |
| `release`（同:99-111） | 更新 | 7 X → 10 | engineer | |
| `releaseAllByEngineerId(Long)` / `(Long, LocalDate)`（同:139-159） | wrapper更新 | 7 X（呼出元が保持済みなら同tx内でno-op）→ 10 | engineer | 退社時の `released_at = 退社基準日` を維持 |
| `EngineerAccountLinkServiceImpl.link`（src/main/java/com/ses/service/impl/EngineerAccountLinkServiceImpl.java:29-64） | user・engineerを非lock read → insert | 3 S{連携ユーザー} → 7 X → 9 | engineer | 連携ユーザーのS lockは、所属writerが連携要員をdiscoveryする際の安定化のため |
| `unlinkByEngineerId`（同:66-75） | tenant内のlinkを削除 | 3 S{連携ユーザー（discovery）} → 7 X → 9 | engineer | |
| `EngineerServiceImpl.updateWithStatusGuard`（src/main/java/com/ses/service/impl/EngineerServiceImpl.java:121-169） | 非lock read → `updateByIdForTenant` → recordAccountingHistory | 7 X（`EngineerMapper.selectByIdForUpdateForTenant`、EngineerMapper.java:201）→ 旧値比較 → 更新 → 11 | engineer（organization_id・legal_entity_id・会計履歴が変わった時） | |
| `EngineerServiceImpl.updateById`（同:186-204） | 同上（BatchOperationServiceImpl.java:135経由を含む） | 同上 | 同上 | |
| `EngineerServiceImpl.save`（同:170-185） | insert → 会計履歴 | insert後に自行を 7 X → 11 | なし（新規） | |
| `EngineerServiceImpl.removeById(id, version)`（同:70-119） | 削除 → 営業解除 → unlink → 連携ユーザーを非lock readして `updateById(user)` 全列（:106-112） | READ_COMMITTED。discovery{連携ユーザー} → 3 X{連携ユーザー} → 7 X → 9 → 10 → sys_userはstatus列だけ更新 | engineer、actor（連携ユーザー） | rank逆転とlost updateの解消（§11 F-5） |
| `EngineerServiceImpl.recordAccountingHistory`（同:214-251） | 会計履歴の版 | 呼出元の7 X配下 | （呼出元の1回に含む） | |
| `OwnershipRepairServiceImpl`（src/main/java/com/ses/service/security/impl/OwnershipRepairServiceImpl.java:89-97）のENGINEER・ENGINEER_ACCOUNT_LINK | tenant補正UPDATE | 7 X → UPDATE | engineer | |
| `OrganizationServiceImpl.save`（src/main/java/com/ses/service/impl/OrganizationServiceImpl.java:89-100） | insert → recordRelation | READ_COMMITTED。6 X{新親の祖先鎖} → insert → relation | 組織部分木（新親の祖先鎖） | |
| `OrganizationServiceImpl.updateById`（同:101-105、公開override） | `validateOrganization` → `super.updateById`。transactionなし、recordRelationなし、増分なし | 削除する（呼出元なし）。残す場合は updateOrganization と同じfenceを通す | 組織部分木（残す場合） | parent_id・statusを直接書ける公開入口（§11 F-8） |
| `updateOrganization`（同:108-130） | CAS → recordRelation | READ_COMMITTED。discovery{旧・新祖先鎖} → 6 X → locking再読込で一致確認 → 更新 | 組織部分木（自身と旧・新祖先鎖） | 不一致は409 `error.organization.versionConflict` |
| `updateStatus`（同:428-468）、`deactivate`（同:421-426） | `selectByIdForUpdate` → CAS → relation | READ_COMMITTED。6 X{自身と祖先鎖} | 組織部分木 | |
| `removeById`（同:491-499） | 参照確認 → 削除 | READ_COMMITTED。6 X{自身と祖先鎖} | 組織部分木 | |
| `merge`（同:132-336） | 統合元・統合先をX（:139-140）→ 子の付替 → 所属移送（:189,196）→ `engineerMapper.reassignOrganization`（:259-261）→ 会計履歴（:265-290）→ 原価部門・予算 | READ_COMMITTED。discovery{影響ユーザー、その連携要員、統合元所属の要員、子組織、旧・新祖先鎖} → 3 X → 6 X → 7 X → 8 → 11 → 葉 | 組織部分木、actor（所属が変わるユーザー）、engineer（統合元所属の要員と連携要員） | 管理操作のため自動再試行せず、依存集合の不一致は409。`managerUserId` の複写（:249,878）も影響ユーザーの連携要員で覆う |
| `assignUser`（同:501-528） | sys_user X（:503）→ 所属X（:506）→ 組織X（:507）→ insert | READ_COMMITTED。3 X → 6 X{対象組織} → 7 X{本人の連携要員} → 8 X → insert | actor（本人）、engineer（連携要員） | 所属→組織 を 組織→所属 へ入れ替える（mergeとの循環待ち、§11 F-4） |
| `updateUserOrganization`（同:530-548） | 非lock read → CAS | READ_COMMITTED。3 X → 6 X{旧・新組織} → 7 X → 8 X | actor、engineer | `manager_user_id` の変更を含む |
| `transferUser`（同:550-592） | sys_user X（:552）→ 所属X（:555） | READ_COMMITTED。3 X → 6 X{旧・新組織} → 7 X → 8 X | actor、engineer | |
| `releaseAssignment`（同:594-617） | 非lock read → CAS | READ_COMMITTED。discovery{本人} → 3 X → 7 X → 8 X | actor、engineer | |
| `closeAssignmentsForUser`（同:619-647） | 所属 `FOR UPDATE`（:626）→ CAS → `clearManager`（:643） | READ_COMMITTED（呼出元のtxに参加する場合は呼出元がRC）。3 X{本人、呼出元が保持済み可} → 7 X → 8 X → clearManager | actor（本人。clearManagerの影響も本人の上長経路だけなのでこれで覆う）、engineer | SysUserServiceImpl・ResignationGateCheckerから呼ばれる |
| `recordRelation`（同:395-419） | relation history | 呼出元の6 X配下 | （呼出元に含む） | |

`manager_user_id` の設定・変更は、そのマネージャーの直属ユーザー経路（DataScope）を変えるが、影響は当該ユーザーの連携要員に対する述語だけなので、連携要員のengineer epochで覆う（§2.2）。clearManagerだけは上長本人のactor epochで覆う。

規模が大きい場合、W3-16aは「要員・link・営業（EngineerSales、EngineerAccountLink、Engineer、OwnershipRepair）」と「組織・所属（OrganizationService）」に分けてよい。

W3-16b（lifecycle）:

| クラス.メソッド（ファイル:行） | 現在の処理 | 変更後のlock列 | 増分 | 備考 |
|---|---|---|---|---|
| `LifecycleCaseServiceImpl.holdCase`（src/main/java/com/ses/service/lifecycle/impl/LifecycleCaseServiceImpl.java:594-628） | case X → case更新のみ | 4 X → 5 X{非終端task、id昇順} | task generation | |
| `resumeCase`（同:632-665） | case X | 4 X | なし（世代を戻さない） | |
| `cancelCase`（同:771-829） | case X → task id昇順CAS | 同左 | task generation | |
| `completeCase`（同:669-734） | case X → gate evaluate → executeAutomaticGateActions → 要員status | §3.4 の completeCase 列 | actor（連携ユーザー）、engineer | 単一の `REQUIRED` transaction（`READ_COMMITTED`）で行い、CONFIRM coordinator（`NEVER`・再試行）は使わない。既存の `@Transactional` H2テストと互換で、再試行はCONFIRMの決定事項に限る。依存集合の不一致は409。`released_at` と所属 `valid_to` は退社基準日を維持 |
| `ResignationGateChecker.executeAutomaticGateActions`（src/main/java/com/ses/service/lifecycle/ResignationGateChecker.java:276-328） | 上記の内部 | completeCaseがlock済みの行で実行（sys_user停止 :288-300、所属close :303、unlink :320、営業解除 :326） | （completeCaseに含む） | gate入力（:51-264）はlocking再読込後に評価（batch 0 P3-1の解消） |
| `LifecycleTaskServiceImpl.waiveTask`（src/main/java/com/ses/service/lifecycle/impl/LifecycleTaskServiceImpl.java:181-237） | case X → task X | （承認経由時は2 X）→ 4 X → 5 X | task generation | |
| `reassignTask`（同:239-285） | case X → task X → 新担当を非lock read | 3 S{新担当} → 4 X → 5 X | task generation | |
| `startTask`（:47）、`completeTask`（:92）、`correctCompletedTask`（:438）、`promoteEligibleSuccessors`（:507） | | 現状維持 | なし | completeTaskのDUAL拒否（:102-104、409）を維持 |

### 4.3 F3: document（W3-17）

すべてのlink挿入・更新・削除を新設 `DocumentLinkCommandService` に集め、利用可否の状態変化を新設 `DocumentEligibilityService` に集める。

| クラス.メソッド（ファイル:行） | 現在の処理 | 変更後のlock列 | 増分 | 備考 |
|---|---|---|---|---|
| `DocumentServiceImpl.link`（src/main/java/com/ses/service/impl/DocumentServiceImpl.java:550-553）→ `linkWithinRegistration`（同:647-662） | 非lock read → insert | （呼出元のtyped parent）→ 15 X → 17 | document（実際に挿入した時） | |
| `DocumentMetadataCommitService.commitNew`（src/main/java/com/ses/service/impl/DocumentMetadataCommitService.java:58-67）と `link`（同:143-160） | `REQUIRES_NEW` で document・version・初期linkをinsert | 新規文書のinsert → 初期link | なし（新規、DEFAULT 0） | 呼出し前にlockを持たない（registerGeneratedは `NOT_SUPPORTED`、DocumentServiceImpl.java:103-104） |
| `DocumentMetadataCommitService.commitVersion`（同:110-126）、`DocumentServiceImpl.addVersion`（同:394-490） | version insert → 文書状態CAS（AMENDED、:461-465） | 15 X を先に取る → version insert → 状態CAS | なし（版追加・AMENDEDは利用可否を変えない） | 現行契約§9.2の順序修正を実施 |
| `placeLegalHold`（同:664-683） | 非lock read → CAS | 15 X → CAS | document（設定・解除とも） | |
| `requestDisposal`（同:685-712） | 非lock read → insert | 15 X → 18 insert | document | |
| `approveDisposal`（同:723-750）、`rejectDisposal`（同:752-776） | requestを先に読む → CAS | discovery{requestId→documentId} → 15 X → 18 X（再読込）→ CAS | document | |
| `executeDisposal`（同:778-852） | requestを先に読む → DISPOSED → afterCommitでstorage削除 | discovery → 15 X → 18 X → DISPOSED | document | afterCommitのFAILED更新では増やさない（DISPOSEDは終端で不適格のまま） |
| `confirm`（同:899-918） | DRAFT→CONFIRMED | 15 X → CAS | なし | |
| `DocumentRetentionJob.processExpiredDocuments`（src/main/java/com/ses/service/job/DocumentRetentionJob.java:33-62） | 候補をselect → requestを直接insert。`TenantAwareBatchRunner` を使わずtenant contextなしで動く | 文書ごとの短tx: 15 X → 条件再確認 → 18 insert | document | 直接insertを廃止し `DocumentEligibilityService` を呼ぶ。増分と照合は文書行自身の `id + tenant_id` を引数で渡し、`requireTenantContext()` に依存しない（またはjobを `TenantAwareBatchRunner` でtenantごとに実行する） |
| `EngineerChangeRequestServiceImpl.confirmSkillSheet`（src/main/java/com/ses/service/changerequest/impl/EngineerChangeRequestServiceImpl.java:406-442） | registerGenerated → link → link付帯更新（:439） | PDF生成・scan・storage・commitNewをlockなしで完了 → 15 X → 17 insert → 付帯更新 | document（link挿入時） | 外側 `@Transactional` 内で registerGenerated より前にlockを取らないことを維持し、テストで固定。SKILL_SHEETを生成する唯一の箇所（:417）であることをU4cで固定 |
| `AssetAssignmentServiceImpl.createAssignment`（src/main/java/com/ses/service/impl/AssetAssignmentServiceImpl.java:41-127、link :94-100）、`returnAssignment`（同:129-213、link :186-192） | asset → assignment → link insert（tenant未設定） | 13（ASSET X → ASSET_ASSIGNMENT X）→ 15 X → 17 | document | linkのtenantを文書から設定（§11 F-3） |
| `AssetLostIncidentServiceImpl.linkDocuments`（src/main/java/com/ses/service/impl/AssetLostIncidentServiceImpl.java:196-224） | 既存文書IDを任意にlink | 13 → 15 X → 17 | document | |
| `ExpenseRequestServiceImpl.insertDocumentLink`（src/main/java/com/ses/service/expense/impl/ExpenseRequestServiceImpl.java:270-280） | 新規RECEIPTへlink | 15 X → 17 | document | 新規文書のため影響は無いが経路を統一 |
| `ServiceRequestAttachmentCommitService.commit`（src/main/java/com/ses/service/servicedesk/ServiceRequestAttachmentCommitService.java:44-118、insert :76） | typed linkの補修insert | 13（SERVICE_REQUEST）→ 15 X → 17 | document | |

W3-17ではreader側の文書部品も作る: `DocumentEvidenceEligibility`（新、§5.1の条件3〜9をactorに依存しない形で評価）と `DocumentEvidenceStorageVerifier`（新、storage open + SHA-256）。actorごとの文書ACLの合成はW3-21で行う。

### 4.4 対象外とする書込みと理由

| 書込み | 例（ファイル:行） | 対象外の理由 |
|---|---|---|
| 契約（`sales_user_id` NULL→Bを含む） | ContractServiceImpl、ContractRenewalServiceImpl | ENGINEER対象のscope（§2.1(e)(f)）は契約を読まない。computeContractIdsはallowedEngineerIds（DataScopeServiceImpl.java:115-136）から呼ばれない |
| 提案・案件 | ProposalServiceImpl、ProjectServiceImpl、OpportunityServiceImpl、ProjectIngestionServiceImpl | computeProposalIds・computeProjectIdsは提案・案件・顧客のscopeで、ENGINEER対象に使われない |
| 営業活動・顧客 | SalesActivityServiceImpl、CustomerServiceImpl | computeCustomerIdsだけが読む |
| 資産・貸与・紛失（linkを除く） | AssetServiceImpl、AssetAssignmentServiceImpl、AssetLostIncidentServiceImpl | AssetScopeはENGINEERのDataScopeに入らない。SKILL_SHEETにASSET系linkが付けばF3のlink writerでdocument epochが進み、初回sliceの単一ENGINEER link条件で不適格になる |
| 要員の述語外の列 | EngineerStatusServiceImpl.java:36,46,66（status）、CandidateServiceImpl.java:154（単価）、BatchOperationServiceImpl.java:135（status） | 述語に入らない。EngineerStatusServiceImplはFOR UPDATE後の書戻しで、`@Version` のCASが古い書戻しを拒否する。UPDATEのX lockでreaderのS lockと直列化されるだけ。organization_id等を変える場合はEngineerServiceImpl側で増分される |
| sys_userの非認可列 | AccountLockServiceImpl.java:54-69（failed_count・locked_until、列限定の `@Update`） | status・role・tenant・deletedを変えない。password変更（ProfileApiController.java:57）とemail変更（EngineerChangeRequestApprovalAdapter.java:175）はF1で列限定更新に改めたうえで、epochの増分対象外とする（§4.1） |
| 権限group・action・menuの定義 | 実行時writerなし（migration seedのみ）。`m_menu`、`t_permission_group`、`t_permission_group_action` | 全停止配備でだけ変わる。将来実行時writerを足す場合は、globalまたは影響ユーザー全員のactor epochを増やすまで追加を認めない（§4.5 U4aで検出） |
| `m_system_config` の非認可キー | AttendanceSyncServiceImpl.java:720-734、AccountingReconciliationServiceImpl.java:681-687、AttendanceDiscrepancyServiceImpl.java:309-313、PortalAdminServiceImpl.java:313、ProductionBootstrapCompletionLatch.java:85 | 述語が読まない。全キーでglobalを進めると、勤怠同期のcursor等の定期書込みのたびに全提出が失効する（§12 O-1） |
| セッション | PersistentSessionService、PortalSessionService | 今後の要求の受理を制御するもので、提出を確認できるかの述語ではない（要求ごとのDB再読込とsession失効で担保済み） |
| portal利用者・portal link（W3-02） | | portal利用者はCONFIRMの主体ではない（design.md §6 表2）。epochは持たない。退社との線形化のためlink writerはengineer Xを取る（§5.14） |
| ライフサイクルテンプレート | LifecycleTemplateServiceImpl | taskは起票時にsnapshotされる（design.md §6 表1）。DUALの作成可否はスイッチで制御（§5.11） |
| 外部アカウント参照 | ExternalAccountServiceImpl | SES DBの認可述語ではない。退社完了時・退社後登録時に失効要求を登録するだけ（§5.13） |
| 免除台帳・経費・license | | 退社gateの入力であり、CONFIRMの述語ではない |

### 4.5 静的guard（Docker不要）

- U3 `AuthorizationConfigKeyFenceTest`（W3-15）: 認可キー台帳を持ち、認可判定クラス（DataScopeServiceImpl、OrganizationScopeServiceImpl、LifecycleScopeService、AuthorizationServiceImpl、DocumentServiceImplのACL部、ExplicitActorAuthorizationEvaluator）が `systemConfigService.get*` で読むキーと `SystemConfigMapper` で直接読むキーが台帳に含まれること、直接 `systemConfigMapper` を書くクラスが台帳のキーを使わないこと。
- U4a `GlobalActorWriterFenceTest`（W3-15）: 次の書込みが許可クラスの外に無いこと。
  - `t_role_menu`、`m_menu`、`t_permission_group`、`t_permission_group_action`、`t_user_permission_group` への書込み（mapper呼出し、注釈SQL）。
  - `SysUserMapper` の書込み呼出し、および `SysUserService` に `IService` から継承された書込みメソッド（`save`・`saveBatch`・`saveOrUpdate`・`update`・`updateById`・`updateBatchById`・`remove*`）の呼出し。許可クラスは RoleMenuServiceImpl、SysUserServiceImpl、PermissionGroupManagementServiceImpl、EngineerServiceImpl、ResignationGateChecker、AccountLockServiceImpl（列限定）、EngineerChangeRequestApprovalAdapter（列限定）。
  - `SysUser` entityを引数に取る全体更新（`updateById(entity)`、`update(entity, wrapper)`）は、静的にはどの列を書くか判定できないため許可クラス内でも禁止し、列限定の `UpdateWrapper`/`LambdaUpdateWrapper` だけを許す（SysUserServiceImpl内の、fenceを経由する更新メソッドを除く）。
  - 連携中ユーザーの削除拒否（SysUserServiceImpl.java:156-158）が残っていること。
- U4b `ScopeSourceWriterFenceTest`（W3-16a）: 次の書込みが許可クラスの外に無いこと。
  - `EngineerSalesMapper`・`EngineerAccountLinkMapper`・`UserOrganizationMapper`（`clearManager` を含む）・`OrganizationUnitMapper`・`OrganizationRelationHistoryMapper`・`EngineerAccountingHistoryMapper` の書込み呼出し、`EngineerMapper.reassignOrganization`・`assignTenantForRepair`・`deleteByIdForTenant`・`updateByIdForTenant`、および注釈SQL（`@Update`/`@Insert`/`@Delete`）でこれらの表名を含むもの。
  - `EngineerService`・`EngineerSalesService`・`OrganizationService` に `IService` から継承された書込みメソッドの呼出し（各Impl内でfence済みoverrideを呼ぶものを除く）。
  - `Engineer`・`OrganizationUnit`・`UserOrganization`・`EngineerSales` entityの全体更新は、理由付きの許可箇所（同一txでanchorをFOR UPDATEして読んだentityの書戻しで、かつ `@Version` のCASがあるもの。例: EngineerStatusServiceImpl）以外で禁止し、列限定更新だけを許す。
- U4c `DocumentEligibilityWriterFenceTest`（W3-17）: `DocumentLinkMapper` の insert・update・delete、`DocumentDisposalRequestMapper` の insert・update、`t_document` の legal_hold_flag・status・deleted_flag の更新が `DocumentLinkCommandService`・`DocumentEligibilityService`・`DocumentMetadataCommitService` の外に無いこと。加えて、文書種別 `SKILL_SHEET` を登録するコードが `EngineerChangeRequestServiceImpl.confirmSkillSheet`（:417）だけであること（現行契約§2第4段落の起点writer限定を緩和した代わりの固定）。
- U5 `AuthorizationEpochColumnIsolationTest`（W3-10）: §2.4 E-7の5 entityにepoch列・task_generationのfieldが無く、これらの列名を含むSQLが `AuthorizationEpochMapper`・`LifecycleTaskMapper` の専用メソッドとmigration以外に無いこと。
- U15 `WriterIsolationPolicyTest`（W3-15・W3-16a・W3-16b が各自の対象を追加）: §2.4 E-3の対象メソッドの `@Transactional` が `isolation = Isolation.READ_COMMITTED` を持つこと。

これらはwriterクラスの許可一覧であり、新しいwriterを足すとテストが失敗する（漏れが許可ではなく失敗になる）。CLAUDE.mdが禁じるaction keyの許可一覧（漏れが403になる）とは向きが逆で、`ActionPermissionMatrixTest` の考え方とは衝突しない。

### 4.6 serviceを経由しない直接書込みの棚卸し（基準HEAD、静的確認）

§4.1〜4.3の表に含めた直接書込みを、guardの初期許可一覧の根拠として列挙する。ここに無い直接書込みが見つかった場合は、fenceを足すまで当該バッチをPASSにしない。

| 対象表 | 直接書込みの箇所 | 所属バッチ |
|---|---|---|
| `sys_user`（status） | EngineerServiceImpl.java:111（`sysUserMapper.updateById`）、ResignationGateChecker.java:292（`sysUserMapper.update`） | W3-16a、W3-16b |
| `sys_user`（認可列外） | EngineerChangeRequestApprovalAdapter.java:175（`sysUserMapper.updateById` 全列、非lockの `selectById` :172 の後）、ProfileApiController.java:57（`sysUserService.updateById`、継承メソッド）、AccountLockServiceImpl.java:54,57,69（列限定 `updateLockState`） | W3-15（前2件を列限定へ） |
| `t_engineer` | EngineerServiceImpl.java:98（`deleteByIdForTenant`）、:153（`updateByIdForTenant`）、OrganizationServiceImpl.java:260（`reassignOrganization`）、LifecycleCaseServiceImpl.java:705（`updateById`、status）、EngineerStatusServiceImpl.java:36,46,66（status）、CandidateServiceImpl.java:154（単価）、OwnershipRepairServiceImpl.java:92（tenant補正） | W3-16a、W3-16b（status系は増分なし） |
| `t_engineer_account_link` | EngineerAccountLinkServiceImpl.java:55（insert）、:72（`deleteByIdForTenant`）、OwnershipRepairServiceImpl.java:96（tenant補正） | W3-16a |
| `t_engineer_sales` | EngineerSalesServiceImpl内のServiceImpl基底メソッド（save・updateById・update、:79,95,109,151-155,193）だけ | W3-16a |
| `t_engineer_accounting_history` | EngineerServiceImpl.java:242,245,247、OrganizationServiceImpl.java:273,280,283 | W3-16a |
| `t_user_organization` | OrganizationServiceImpl.java:212,218,230,253（merge）、:522（assignUser）、:542（updateUserOrganization）、:582,586（transferUser）、:611（releaseAssignment）、:637（closeAssignmentsForUser）、:643（`clearManager`、UserOrganizationMapper.java:68-70。本人のactor epochで覆う）、:868,883（統合時の主所属分割） | W3-16a |
| `m_organization_unit` | OrganizationServiceImpl.java:104（公開overrideの `super.updateById`、:101-105）、:122（updateOrganization）、:176（merge子付替）、:321（merge統合元）、:461（updateStatus）、:497（removeById） | W3-16a |
| `t_organization_relation_history` | OrganizationServiceImpl.java:410,413,415（recordRelation） | W3-16a |
| `t_external_account_reference`（失効要求） | ExternalAccountReferenceMapper.java:112-126（`claimRevokeRequest`）、:129-141（`claimRevokePoll`）、:144-160（`completeRevokePollWithCas`）、ExternalAccountServiceImpl.java:199-290（`requestRevokeWithIdempotency`） | W3-11 |
| `t_document` | DocumentServiceImpl.java:361（metadata commit service未注入時の登録）、:462（AMENDED）、:672（legal hold）、:796（DISPOSED）、:908（CONFIRMED）、DocumentMetadataCommitService.java:60,83,114 | W3-17 |
| `t_document_link` | DocumentServiceImpl.java:659、DocumentMetadataCommitService.java:158、EngineerChangeRequestServiceImpl.java:439（付帯更新）、ExpenseRequestServiceImpl.java:279、AssetAssignmentServiceImpl.java:99,191、AssetLostIncidentServiceImpl.java:217、ServiceRequestAttachmentCommitService.java:76 | W3-17 |
| `t_document_disposal_request` | DocumentServiceImpl.java:707,736,761,799,825,842、DocumentRetentionJob.java:56 | W3-17 |
| `t_role_menu` | RoleMenuServiceImpl.java:83,88（remove・saveBatch） | W3-15 |
| `t_user_permission_group` | PermissionGroupManagementServiceImpl.java:87,96 | W3-15 |

## 5. CONFIRM初回slice

### 5.1 範囲

受理する条件（すべて満たすこと）:

1. taskの `evidence_type = 'DUAL_CONFIRMATION'`、task `IN_PROGRESS`、case `ACTIVE`。
2. 1提出につき文書1件（`documentSelections` の要素数=1）。
3. `t_document.tenant_id` = caseのtenant、`deleted_flag = 0`。
4. `document_type = 'SKILL_SHEET'`。
5. `status ∈ {DRAFT, CONFIRMED, AMENDED}`（SKILL_SHEETは登録時DRAFT、DocumentServiceImpl.java:1024）。
6. `legal_hold_flag = 0`。
7. `t_document_disposal_request` に `PENDING/APPROVED/DISPOSED/FAILED` の行が無い（REJECTEDだけなら可）。
8. その文書の有効なlink（`t_document_link`、`deleted_flag = 0`）を**tenantを問わず全件**数え、ちょうど1件で、その行のtenantが文書と一致し、`target_type = 'ENGINEER'` かつ `target_id = t_lifecycle_case.engineer_id`。tenantの一致しないlink行が1件でもあればfail-closed（F-3修正前のtenant未設定linkを取りこぼさないため）。
9. 指定版 `t_document_version(id, tenant_id, document_id)` が一致し `scan_status = 'CLEAN'`。版IDの無い指定は受け付けない（Q-9、B-NF01-4）。
10. storageをopenでき、内容のSHA-256が版の `sha256` と一致する。
11. 提出者・確認者それぞれの文書ACL（明示actor評価）を満たす。

拒否する文書種別（400 `error.lifecycle.evidenceDocumentTypeUnsupported`）: 判定は「SKILL_SHEETだけを許可」で行い、それ以外はすべて拒否する。migrationで登録済みの種別は次のとおり（静的確認、確認用の一覧）: ACCEPTANCE、BP_SUBMISSION、CERTIFICATION_EVIDENCE、CHANGE_REQUEST_ATTACHMENT、CONTRACT、DISPATCH_LEDGER、DISPATCH_NOTICE、EMPLOYMENT_CONDITIONS_STATEMENT、ESIGN_CERT、INDIVIDUAL_CONTRACT、INVOICE_IN、INVOICE_OUT、MANAGEMENT_REPORT、ORDER_ACKNOWLEDGEMENT、ORDER_RECEIVED、PRIVATE_NOTE、QUOTATION、RECEIPT、SERVICE_REQUEST_ATTACHMENT、SIGNED_PDF、WORK_REPORT。今後追加される種別・未知の種別も拒否する。

拒否するlink形（400 `error.lifecycle.evidenceDocumentTargetUnsupported`）: link 0件、2件以上、tenant不一致のlinkを含む、ENGINEER以外のtarget（CUSTOMER、CONTRACT、ACCEPTANCE、PROJECT、PROPOSAL、SALES_ORDER、ASSET_ASSIGNMENT、ASSET_LOST_INCIDENT、EXPENSE_REQUEST、SERVICE_REQUEST、未知）、caseの要員と異なるENGINEER。

起点writer: 現行契約§2第4段落は証跡候補を `confirmSkillSheet` が作った文書に閉じていたが、本計画は条件3〜10を満たす任意のSKILL_SHEETを受け付ける。現時点でSKILL_SHEETを生成するのは `EngineerChangeRequestServiceImpl.java:417` だけで、汎用のupload APIも無いため実質の拡大は無い。この前提をU4cで固定する。

他のevidence_type（NONE、SELF_DECLARATION、DOCUMENT_LINK、SYSTEM_CHECK）は新APIで400 `error.lifecycle.invalidStateTransition`。DOCUMENT_LINKの既存完了経路（LifecycleTaskServiceImpl.java:121-142）は本sliceの対象外で、W3-02（9f6d7c0e）のversionless拒否・CLEAN確認とtenant照合を維持する（§9 RR-8、§10）。

### 5.2 API

| # | method・path | 主体 | 概要 |
|---|---|---|---|
| A1 | GET `/api/lifecycle/tasks/{id}/dual-confirmation` | lifecycle menuの利用者 | 現在のprojection、提出の要約、操作可否 |
| A2 | GET `/api/lifecycle/tasks/{id}/dual-confirmation/candidates` | 同上 | §5.1の3〜9を満たすSKILL_SHEETと、そのCLEAN版（documentId、documentVersionId、versionNo、作成日時、title）。storage key・hashは返さない |
| A3 | POST `/api/lifecycle/tasks/{id}/dual-confirmation/submissions` | 提出者 | 提出。body: `taskGeneration`（必須）、`documentSelections`（1件、`documentId`・`documentVersionId`）、`comment`（1000字以内）。header `Idempotency-Key` 必須 |
| A4 | POST `/api/lifecycle/tasks/{id}/dual-confirmation/submissions/{submissionId}/decision` | 確認者（管理者・HR） | 確認または差戻し。body: `submissionRevision`、`evidenceSha256`、`decision`（`CONFIRM`/`REJECT`）、`comment`。header `Idempotency-Key` 必須 |
| A5 | GET `/api/my/lifecycle/tasks/{id}/dual-confirmation` | 要員本人 | A1の本人版（is_engineer_visible=1のtaskだけ） |
| A6 | GET `/api/my/lifecycle/tasks/{id}/dual-confirmation/candidates` | 要員本人 | A2の本人版 |
| A7 | POST `/api/my/lifecycle/tasks/{id}/dual-confirmation/submissions` | 要員本人 | 本人の提出。`/api/my` に確認routeは作らない |
| 既存 | POST `/api/lifecycle/tasks/{id}/complete`、`/api/my/lifecycle/tasks/{id}/complete` | | DUAL taskは従来どおり409 `error.lifecycle.invalidStateTransition`（:102-104）。スイッチの状態に関係なく変えない |
| 既存 | POST/PUT `/api/lifecycle/templates(/{id})` | | DUAL_CONFIRMATIONを含むtemplateはスイッチOFFで400 `error.lifecycle.dualConfirmationDisabled`（W3-02（9f6d7c0e）のLifecycleTemplateServiceImpl.java:271）、ONで許可（W3-21b） |

- bodyはactor・tenant・role・hash（A3）・storage key・title・scan状態を受け付けない。Spring Bootの既定（`FAIL_ON_UNKNOWN_PROPERTIES=false`）ではクラス単位の `@JsonIgnoreProperties(ignoreUnknown = false)` でも未知propertyは例外にならないため、リポジトリ内の既存の型にならい、DTOの `@JsonAnySetter` で未知のproperty名だけを集め（値は保持しない）、controllerで400にする（AiRestController.java:43-75）。
- action権限はActionPermissionResolverがURIの根から導く `lifecycle.*` のままで、baseline + deny モデルに変更は無い（ActionPermissionMatrixTestの一覧は編集しない）。menuは `/api/lifecycle` → lifecycle、`/api/my/lifecycle` → myLifecycle（既存）。
- 機能スイッチはNF01の配下（Feature.java:31-35）でroute単位の遮断は変えない。子スイッチの扱いは§5.11。

### 5.3 状態遷移

- task: `IN_PROGRESS` のまま提出・差戻しを繰り返し、確認（CONFIRM）で `COMPLETED` になる。`completed_by` は確認者、`evidence_data_json` にsubmission ID・revision・evidenceSha256・confirmation IDだけを入れる。`t_lifecycle_evidence_link` に確定版のdocument・version IDを1行入れる（既存画面の表示互換）。その後、後続昇格（L3）を同じtransactionで行う。
- projection（taskごとに1行）: なし → `PENDING(rev n)` → 再提出で `PENDING(rev n+1)`（旧revはsupersede event）→ `CONFIRMED`（終端）または `REJECTED` → 再提出で `PENDING(rev n+1)`。
- generation: 案件保留・取消、担当変更、免除でtask generationが進むと、旧generationのprojection・提出は確認できない（409 `error.lifecycle.submissionStale`）。generation writer（W3-16b）はB0表を書かない。coordinatorとA1が `projection.task_generation ≠ task.task_generation` を失効として扱う。再開（resume）は世代を戻さないので新しい提出が必要。CONFIRM自体はgenerationを進めない（確認後の同key再送が同じgenerationで冪等行に当たるようにするため）。
- 免除（WAIVE、承認エンジン経由）はDUAL taskにも従来どおり使える。

### 5.4 transaction境界

| 層 | transaction | 役割 |
|---|---|---|
| controller | なし（ControllerTransactionalBanTest） | principalからactor IDを取り、coordinatorを呼ぶ |
| `LifecycleDualConfirmationCoordinator`（新） | `Propagation.NEVER`。加えて `TransactionSynchronizationManager.isActualTransactionActive()` を明示検査 | スイッチ確認、試行ループ、discovery（transaction外の単文読取）、storage検証、例外の分類、deny recorderの呼出し |
| `LifecycleDualConfirmationAttemptService`（新、別bean） | `REQUIRES_NEW` + `Isolation.READ_COMMITTED` + `rollbackFor = Exception.class` + `timeout = 10`（秒、推奨値） | lock列の取得、locking再読込とdiscovery照合、述語・epoch・generation・文書適格性の判定、冪等性、書込み、afterCommit登録 |
| `LifecycleDualConfirmationDenyRecorder`（新、別bean） | `REQUIRES_NEW` + `rollbackFor` | attemptのrollback後に拒否eventを追記。失敗はWARNだけで応答を変えない |
| afterCommit | `TransactionTemplate(REQUIRES_NEW)` | 確認待ち・確認完了の通知（publishCaseCompletedAfterCommit、LifecycleCaseServiceImpl.java:742-767 と同じ形）。失敗はWARN |

- discoveryはtransaction外の単文読取（autocommit）で行う。discovery結果はlock対象の列挙にだけ使い、認可の根拠にしない。
- storage検証（open + SHA-256）はattemptの外で行い、lockを持ったままI/Oをしない。discovery時に読んだ版行の組 `(id, tenant_id, document_id, storage_key, sha256, scan_status)` を検証対象とし、attempt内では文書・版をS lockした状態でこの組の全項目が変わっていないこと、文書がDISPOSEDでないこと、版がCLEANであることを確認する。版の内容識別子はappend-onlyであり、廃棄のstorage削除はDISPOSED commit後のafterCommit（DocumentServiceImpl.java:778-852）なので、attemptは必ずDISPOSEDを観測できる。storage外部での改ざん・消失はRR-4として受容する。
- H2の `@Transactional` テストからcoordinatorは呼べない（`NEVER`）。coordinatorのテストはmockによる単体テスト（U11）とMySQLテスト（M8、M9、非transactionalで後始末）で行う。TestIsolationAuditTestの規則に従う。

### 5.5 submit・confirmの処理順

判定の順序は SUBMIT・CONFIRM で共通に「lock → 認可 → epoch → 文書適格性 → 冪等性の検索（一致すれば保存済みの結果を返す）→ generation・projection・task状態 → 書込み」とする。冪等性の検索をtask状態の判定より前に置くのは、確認済み・提出済みの要求の再送に保存済みの結果を返すため（現行契約§4、M8）。

SUBMIT（A3/A7）:

1. coordinator: スイッチON確認 → principalのsys_userをDBで解決（tenant・role・status）→ discovery（case、task、generation、要員、提出者の有効主所属組織（マネージャーかつ組織scope有効時）、文書・版・link・disposal）→ lock plan → storage検証。
2. attempt（§3.4のsubmit列）:
   1. lock → locking再読込で同じdependency集合を再構成し `requireSameDependencies`。
   2. 認可: 提出者の述語（`ExplicitActorAuthorizationEvaluator`、task種別・可視性を含む）。
   3. epoch: 現在値を読む（新規提出ではsnapshotとして保存する値）。
   4. 文書適格性（`DocumentEvidenceEligibility`）と提出者の文書ACL。
   5. 冪等性: `(tenant, task, 現在のtask generation, SUBMIT, 提出者, key)` で検索。一致かつ同hashなら、保存済みsubmissionのsnapshot（global・提出者actor・engineer・組織集合・文書epoch）が3の現在値と一致する場合だけ保存済みの結果を返し、異なれば409 `error.lifecycle.authorizationChanged`。同key・異hashは409 `error.lifecycle.idempotencyConflict`。
   6. 状態: case `ACTIVE`、task `IN_PROGRESS`、evidence_typeが `DUAL_CONFIRMATION`、bodyの `taskGeneration` が現在値と一致（不一致は409 `error.lifecycle.submissionStale`）。
   7. 書込み: submission（3のsnapshot）・evidence子行・組織fence子行・projection CAS・supersede event・idempotency行。task行は変更しない。

CONFIRM（A4）:

1. coordinator: スイッチ確認 → 確認者を解決 → discovery（submission、projection、提出者、確認者、case、task、後続と後続の先行、提出時と現在の組織集合、要員、文書・版）→ lock plan → storage検証。
2. attempt（§3.4のconfirm列）:
   1. lock → locking再読込と照合。
   2. 認可（確認者）: 有効・同tenant・roleが管理者またはHR・提出者と別ID・menu・action。
   3. 認可（提出者）: 現在も有効で、submission時と同じtenant・role、述語 (a)〜(f) を明示actorで満たす（失っていれば409 `error.lifecycle.authorizationChanged`）。
   4. epoch: global・提出者actor・engineer・組織集合（集合の一致と各epochの一致）・文書epochがsubmissionのsnapshotと一致。確認者のactor epochはsnapshotと比較せず、S lock下の現在値をconfirmation行に記録する。
   5. 文書適格性の再評価と、提出者・確認者の文書ACL。
   6. 冪等性: `(tenant, task, 現在のtask generation, CONFIRM, 確認者, key)` で検索。一致かつ同hashなら保存済みの結果を返す（CONFIRMはgenerationを進めないので、確認済みtaskへの再送もここで当たる）。同key・異hashは409。
   7. 状態: submissionのgenerationが現在値と一致、projectionがこのsubmission・revisionを指し `PENDING`、`ConfirmationBinding(submissionId, revision, evidenceSha256)`（LifecycleCanonicalization.java:179-204）が要求と一致、case `ACTIVE`、task `IN_PROGRESS`。いずれかが不成立なら409 `error.lifecycle.submissionStale`。
   8. 書込み: confirmation行（`UNIQUE(tenant_id, submission_id)`）・projection終端・task CAS（CONFIRM時のみ）・event・evidence link・後続昇格・idempotency行。

### 5.6 再試行

- 予算は1要求あたり3回の試行（初回 + 再試行2回）。
- 予算に数える例外: `LifecycleLockPlan.DependencyChangedException`、MySQL 1213（`DeadlockLoserDataAccessException`）、1205（`CannotAcquireLockException` / `PessimisticLockingFailureException`）、attemptの `timeout` による打切り（文の実行中の `QueryTimeoutException`、および期限切れ後の次の文の前に出る `TransactionTimedOutException`。どちらも1205相当として扱う）、idempotency行の同時insertによる `DuplicateKeyException`（次の試行で既存行として扱う）。
- 予算に数えない例外: `BusinessException`（認可拒否、状態不一致、epoch不一致、冪等性衝突など決定的な結果）。即時にdeny recorder → 応答。
- 再試行はattemptのrollback完了後に、新しいdiscoveryから始める。同一transaction内での不足lockの追加はしない。試行間に短いjitter（上限100ms、実装裁量）を入れてよい。
- 3回目も失敗したら409 `error.lifecycle.concurrentUpdateRetryExhausted`、deny event（reason `RETRY_EXHAUSTED`、最後の例外の分類）。

### 5.7 冪等性

- `Idempotency-Key` headerは必須。1〜190文字のASCII `[A-Za-z0-9._~-]`。満たさなければ400 `error.lifecycle.idempotencyKeyInvalid`。DB列は `ascii_bin`（大文字小文字を区別、PAD SPACEで同一視しない）。
- 一意key: `(tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key)`。operationは `SUBMIT`/`CONFIRM`（差戻しもCONFIRM操作として記録し、decisionはrequest hashに含める）。検索に使うtask_generationはlock済みtask行の現在値である。
- request hash: `LifecycleCanonicalization.Request.sha256()`（LifecycleCanonicalization.java:118-176）。CONFIRMでは `ConfirmationBinding` とdecisionを含めるようRequestを拡張する（W3-21a）。server採番のIDは含めない。
- 判定の位置は§5.5のとおり（認可・epoch・文書適格性の後、task状態の前）。古いgenerationで送られた要求は、現在generationの冪等行に当たらず、状態判定でbodyの `taskGeneration` 不一致となり409 `error.lifecycle.submissionStale`。
- 再送であっても、提出者・確認者の認可やepochが変わっていれば成功結果を返さず409にする（現行契約§4を維持）。

### 5.8 deny recorder

- 追記先: B0の `t_lifecycle_dual_event`（`event_type = 'DENIED'`）。FKを持たない監査ログとし、他tenantのIDが要求されてもinsertが失敗しないようにする。
- 列: tenant_id（確認者または提出者のDB上のtenant。主体を解決できなければ記録しない）、actor_user_id、operation（SUBMIT/CONFIRM/REJECT）、requested_task_id、requested_submission_id、case_id・task_generation（主体のtenant内で解決できた場合だけ）、reason_code、http_status、attempt_count、occurred_at。
- 記録しない内容: 文書title、storage path・key、業務comment、clientが送ったhash、氏名。
- reason_code: `NOT_FOUND_OR_FOREIGN`、`SCOPE_DENIED`、`CONFIRMER_ROLE_DENIED`、`SELF_CONFIRMATION`、`EVIDENCE_TYPE_UNSUPPORTED`、`EVIDENCE_TARGET_UNSUPPORTED`、`EVIDENCE_SELECTION_INVALID`、`EVIDENCE_VERSION_REQUIRED`、`EVIDENCE_VERSION_NOT_CLEAN`、`EVIDENCE_UNAVAILABLE`、`STORAGE_INTEGRITY`、`SUBMISSION_STALE`、`AUTHORIZATION_CHANGED_GLOBAL`、`AUTHORIZATION_CHANGED_ACTOR`、`AUTHORIZATION_CHANGED_ENGINEER`、`AUTHORIZATION_CHANGED_ORGANIZATION`、`AUTHORIZATION_CHANGED_DOCUMENT`、`IDEMPOTENCY_CONFLICT`、`RETRY_EXHAUSTED`、`EPOCH_UNAVAILABLE`。
- 記録しない拒否: スイッチOFF（機能を公開していない）、bodyの形式違反（未知property、桁数等）。

### 5.9 エラー一覧

key名はW3-02（9f6d7c0e）の `messages.properties` で確認した（`error.lifecycle.dualConfirmationDisabled` :4125、`error.lifecycle.evidenceVersionRequired` :4126、`error.lifecycle.evidenceVersionNotClean` :4127）。

| 状況 | HTTP | message key | 新規 |
|---|---|---|---|
| 子スイッチOFF（A1〜A7、template APIのDUAL） | 400 | `error.lifecycle.dualConfirmationDisabled` | 既存（W3-02） |
| DUAL以外のtaskへの提出 | 400 | `error.lifecycle.invalidStateTransition` | 既存 |
| case非ACTIVE・task非IN_PROGRESS（初回要求） | 400 | `error.lifecycle.caseNotActive` / `error.lifecycle.invalidStateTransition` | 既存 |
| 他tenant・不可視のtask | 404 | `error.lifecycle.taskNotFound` | 既存 |
| 同tenantのscope外（提出時） | 403 | `error.lifecycle.taskEditDenied` | 既存 |
| 確認者のroleが管理者・HR以外 | 403 | `error.lifecycle.confirmerRoleDenied` | 新規 |
| 確認者 = 提出者 | 403 | `error.lifecycle.selfConfirmationDenied` | 新規 |
| 文書指定が1件でない | 400 | `error.lifecycle.evidenceSelectionInvalid` | 新規 |
| 版IDなし | 400 | `error.lifecycle.evidenceVersionRequired` | 既存（W3-02） |
| 提出時に版がCLEANでない | 400 | `error.lifecycle.evidenceVersionNotClean` | 既存（W3-02） |
| 文書・版が無い、他tenant、版が別文書、提出者の文書ACL外 | 400 | `error.document.notFound` | 既存（batch 0と同じく存在を推測させない） |
| SKILL_SHEET以外 | 400 | `error.lifecycle.evidenceDocumentTypeUnsupported` | 新規 |
| link形の不適合 | 400 | `error.lifecycle.evidenceDocumentTargetUnsupported` | 新規 |
| hold・廃棄申請・DISPOSED・deleted・状態不適合・storage不一致 | 409 | `error.lifecycle.evidenceDocumentUnavailable` | 新規（storage不一致はERRORログも出す） |
| 世代不一致・supersede済み・決定済み・確認時のcase/task状態不成立 | 409 | `error.lifecycle.submissionStale` | 新規 |
| epoch不一致（提出後の権限・scope・文書の変化） | 409 | `error.lifecycle.authorizationChanged` | 新規 |
| 確認時に提出者が認可を失った | 409 | `error.lifecycle.authorizationChanged` | 新規 |
| Idempotency-Key不正 | 400 | `error.lifecycle.idempotencyKeyInvalid` | 新規 |
| 同key・異hash | 409 | `error.lifecycle.idempotencyConflict` | 新規 |
| 再試行予算の超過 | 409 | `error.lifecycle.concurrentUpdateRetryExhausted` | 新規 |
| global行の欠落、anchor行の欠落、epoch上限 | 409 | `error.authorization.epochUnavailable` | 新規（writer側の上限到達も同じkey） |
| DUAL taskへの既存complete | 409 | `error.lifecycle.invalidStateTransition` | 既存（変更しない） |
| 未知のbody property | 400 | 既存のvalidation用key（AiRestControllerの型にならう） | 既存 |

新しいkeyは `messages{,_en,_ko,_zh_CN}.properties` の4言語へ追加し、各言語に翻訳する（MessageBundleConsistencyTest、日本語の複写は不可）。`SafeErrorPolicy` が未登録keyを汎用文言へ落とすことに依存しない。

### 5.10 画面（versionless拒否・版選択、Q-9）

- 基準HEADでは完了modalはdocumentIdだけを送る（src/main/resources/static/js/modules/lifecycle.js:482、my-lifecycle.js:162,297）。
- W3-02（9f6d7c0e）: DOCUMENT_LINKの完了で版IDを必須にし、CLEANでない版を拒否し、画面に版の選択を追加した（N1-3）。CONFIRMの開放でversionlessを拒否する決定（B-NF01-4）は、NF01の本番公開がCONFIRM完成後である（B-NF01-1）ため、公開時点では常に拒否と同じ結果になる。
- W3-21b: DUAL taskの行に「提出」（A2で候補を取り、文書とCLEAN版を選んでA3/A7へ版IDを送る）、管理者・HRに「確認」「差戻し」（A4）を追加する。W3-02の版選択部品を再利用する。templates: `templates/lifecycle/detail.html`、`templates/my/lifecycle-detail.html`。JS: `static/js/modules/lifecycle.js`、`static/js/modules/my-lifecycle.js`。390px表示、二重送信防止、Idempotency-Keyは画面操作ごとに生成して再送時に再利用する。

### 5.11 機能スイッチとテンプレートAPI

- 子スイッチ `NF01_DUAL_CONFIRMATION`（親NF01、設定キー `app.features.nf01-dual-confirmation.enabled`、既定false）を新設する。routeは持たず（NF03_CERTIFICATION_PII と同じ形、Feature.java）、service層で判定する。
- OFF: A1〜A7は400 `error.lifecycle.dualConfirmationDisabled`。テンプレートAPIでDUAL_CONFIRMATIONを含むtaskの作成・更新は400（W3-02で無条件に実装済み、W3-21bでスイッチ連動へ変更）。
- ON: テンプレートでDUALを選べ、A1〜A7が動く。ONにするのは§10の署名とW3-21のPASS後。
- 空DB新規導入のため既存のDUAL taskは0件の前提（preflight `LIFECYCLE_DUAL_TASK_UNFINISHED`）。

### 5.12 B0 DDL草案（番号なし `V?`）

```sql
-- 複合FKのための一意key（既存の (tenant_id, id) 系を補う）
ALTER TABLE t_lifecycle_case   ADD UNIQUE KEY uk_lifecycle_case_tenant_id (tenant_id, id);
ALTER TABLE t_lifecycle_task   ADD UNIQUE KEY uk_lifecycle_task_case_id (case_id, id);
ALTER TABLE sys_user           ADD UNIQUE KEY uk_sys_user_tenant_id (tenant_id, id);
ALTER TABLE t_document_version ADD UNIQUE KEY uk_document_version_tenant_doc_id (tenant_id, document_id, id);

CREATE TABLE t_lifecycle_dual_submission (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  case_id                 BIGINT       NOT NULL,
  task_id                 BIGINT       NOT NULL,
  engineer_id             BIGINT       NOT NULL COMMENT 'caseの要員（attempt内で一致確認）',
  task_generation         BIGINT       NOT NULL,
  revision                INT          NOT NULL,
  submitter_user_id       BIGINT       NOT NULL,
  submitter_role_snapshot VARCHAR(30)  NOT NULL,
  global_epoch            BIGINT       NOT NULL,
  submitter_actor_epoch   BIGINT       NOT NULL,
  engineer_scope_epoch    BIGINT       NOT NULL,
  task_version_snapshot   INT          NOT NULL,
  evidence_canonical_json LONGTEXT     NOT NULL,
  evidence_sha256         CHAR(64)     NOT NULL,
  request_sha256          CHAR(64)     NOT NULL,
  business_comment        VARCHAR(1000) NULL,
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_dual_submission_revision (tenant_id, task_id, task_generation, revision),
  UNIQUE KEY uk_dual_submission_binding (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
  UNIQUE KEY uk_dual_submission_tenant_id (tenant_id, id),
  CONSTRAINT fk_dual_submission_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
  CONSTRAINT fk_dual_submission_task FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id),
  CONSTRAINT fk_dual_submission_submitter FOREIGN KEY (tenant_id, submitter_user_id) REFERENCES sys_user (tenant_id, id),
  CONSTRAINT ck_dual_submission_nonneg CHECK (task_generation >= 0 AND revision > 0
      AND global_epoch >= 0 AND submitter_actor_epoch >= 0 AND engineer_scope_epoch >= 0)
);

CREATE TABLE t_lifecycle_dual_submission_document (
  tenant_id                  VARCHAR(100) NOT NULL,
  submission_id              BIGINT       NOT NULL,
  document_id                BIGINT       NOT NULL,
  document_version_id        BIGINT       NOT NULL,
  version_no                 INT          NOT NULL,
  sha256                     CHAR(64)     NOT NULL,
  evidence_eligibility_epoch BIGINT       NOT NULL,
  PRIMARY KEY (submission_id, document_id),
  CONSTRAINT fk_dual_doc_submission FOREIGN KEY (tenant_id, submission_id) REFERENCES t_lifecycle_dual_submission (tenant_id, id),
  CONSTRAINT fk_dual_doc_version FOREIGN KEY (tenant_id, document_id, document_version_id)
      REFERENCES t_document_version (tenant_id, document_id, id)
);

CREATE TABLE t_lifecycle_dual_submission_org_fence (
  tenant_id       VARCHAR(100) NOT NULL,
  submission_id   BIGINT       NOT NULL,
  organization_id BIGINT       NOT NULL,
  org_scope_epoch BIGINT       NOT NULL,
  PRIMARY KEY (submission_id, organization_id),
  CONSTRAINT fk_dual_org_submission FOREIGN KEY (tenant_id, submission_id) REFERENCES t_lifecycle_dual_submission (tenant_id, id),
  CONSTRAINT fk_dual_org_unit FOREIGN KEY (organization_id) REFERENCES m_organization_unit (id)
);

CREATE TABLE t_lifecycle_dual_confirmation (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  case_id                 BIGINT       NOT NULL,
  task_id                 BIGINT       NOT NULL,
  task_generation         BIGINT       NOT NULL,
  submission_id           BIGINT       NOT NULL,
  submission_revision     INT          NOT NULL,
  evidence_sha256         CHAR(64)     NOT NULL,
  confirmer_user_id       BIGINT       NOT NULL,
  confirmer_role_snapshot VARCHAR(30)  NOT NULL,
  global_epoch            BIGINT       NOT NULL,
  confirmer_actor_epoch   BIGINT       NOT NULL,
  decision                VARCHAR(10)  NOT NULL,
  decision_comment        VARCHAR(1000) NULL,
  decided_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_dual_confirmation_submission (tenant_id, submission_id),
  CONSTRAINT fk_dual_confirmation_submission
      FOREIGN KEY (tenant_id, task_id, task_generation, submission_revision, submission_id, evidence_sha256)
      REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
  CONSTRAINT fk_dual_confirmation_confirmer FOREIGN KEY (tenant_id, confirmer_user_id) REFERENCES sys_user (tenant_id, id),
  CONSTRAINT ck_dual_confirmation_decision CHECK (decision IN ('CONFIRMED', 'REJECTED'))
);

CREATE TABLE t_lifecycle_dual_projection (
  tenant_id          VARCHAR(100) NOT NULL,
  task_id            BIGINT       NOT NULL,
  case_id            BIGINT       NOT NULL,
  task_generation    BIGINT       NOT NULL,
  revision           INT          NOT NULL,
  submission_id      BIGINT       NOT NULL,
  evidence_sha256    CHAR(64)     NOT NULL,
  state              VARCHAR(12)  NOT NULL,
  projection_version BIGINT       NOT NULL DEFAULT 0,
  updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, task_id),
  CONSTRAINT fk_dual_projection_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
  CONSTRAINT fk_dual_projection_task FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id),
  CONSTRAINT fk_dual_projection_submission
      FOREIGN KEY (tenant_id, task_id, task_generation, revision, submission_id, evidence_sha256)
      REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
  CONSTRAINT ck_dual_projection_state CHECK (state IN ('PENDING', 'CONFIRMED', 'REJECTED'))
);

CREATE TABLE t_lifecycle_dual_idempotency (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       VARCHAR(100) NOT NULL,
  case_id         BIGINT       NOT NULL,
  task_id         BIGINT       NOT NULL,
  task_generation BIGINT       NOT NULL,
  operation       VARCHAR(10)  NOT NULL,
  actor_user_id   BIGINT       NOT NULL,
  idempotency_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_sha256  CHAR(64)     NOT NULL,
  submission_id   BIGINT       NOT NULL,
  confirmation_id BIGINT       NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_dual_idempotency (tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key),
  CONSTRAINT fk_dual_idem_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
  CONSTRAINT fk_dual_idem_actor FOREIGN KEY (tenant_id, actor_user_id) REFERENCES sys_user (tenant_id, id),
  CONSTRAINT ck_dual_idem_operation CHECK (operation IN ('SUBMIT', 'CONFIRM'))
);

CREATE TABLE t_lifecycle_dual_event (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  case_id                 BIGINT       NULL,
  task_id                 BIGINT       NULL,
  task_generation         BIGINT       NULL,
  submission_id           BIGINT       NULL,
  requested_task_id       BIGINT       NULL,
  requested_submission_id BIGINT       NULL,
  actor_user_id           BIGINT       NOT NULL,
  operation               VARCHAR(10)  NOT NULL,
  event_type              VARCHAR(20)  NOT NULL,
  reason_code             VARCHAR(60)  NULL,
  http_status             SMALLINT     NULL,
  attempt_count           TINYINT      NULL,
  occurred_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_dual_event_task (tenant_id, task_id, occurred_at),
  CONSTRAINT ck_dual_event_type CHECK (event_type IN ('SUBMITTED', 'SUPERSEDED', 'CONFIRMED', 'REJECTED', 'DENIED'))
);
```

- 文字集合・照合順序: 各表のtenant列は参照先（`t_lifecycle_case.tenant_id`、`sys_user.tenant_id`、`t_document_version.tenant_id`）と一致させる。DDL作成時に `information_schema` で確定する。
- 不変表（submission、submission_document、org_fence、confirmation、idempotency、event）はmapperでUPDATE/DELETEを禁止する（`LifecycleEventMapper` と同じ形、review-ledger LC-P1-15）。DB triggerによる禁止（V132と同じ方式、6表×UPDATE/DELETE）も推奨するが、配備runbookのtrigger件数の更新とDBAのtrigger権限が必要なため、採否はDDL laneとPlan Reviewで決める（§12 O-4）。
- projectionだけが可変で、`projection_version` のCASで更新する。task行のgenerationとの一致はattempt内で確認する（generationはFKにしない、現行契約§9.1）。
- `sys_user(tenant_id, id)` へのFKがあるため、F-1の修正（tenant変更の拒否）が無いとtenant変更がFK RESTRICTで500になる。W3-15をW3-18・W3-21aより前に統合する（§7.0）。
- H2: 同じ表をH2版として `src/test/resources/sql/` に追加し `application-test.yml` に登録する。triggerはH2版に含めない。entity・mapperを追加するとAllMappersSchemaSweepTestの対象になる。

### 5.13 外部アカウント失効（Q-6）とgateの整合（W3-11）

現状（基準HEAD）:

- gateの #6 は `blockersClear = 未返却資産0 && 未失効外部アカウント0 && 未解放license0`（ResignationGateChecker.java:145-147）で、資産返却taskがCOMPLETEDでも外部アカウントが1件でも残ればブロックする（同:152-156）。未失効の計数は `status != 'REVOKED'` の行だが、`requireCurrentScope()` により**操作者の現在法人**の行だけを数える（ExternalAccountReferenceMapper.java:43-51、ExternalAccountServiceImpl.java:398-402,474-482、AssetOffboardingServiceImpl.java:54-100。R2 N9-P2-1）。
- 外部アカウント行の法人は登録時の操作者の法人（ExternalAccountServiceImpl.java:85,96）で、spec（design.md:76）は別法人の要員の担当を認めている。したがって行の法人と要員の法人が異なるのは正常なデータである。
- 失効要求の送信 `AssetOffboardingServiceImpl.triggerOffboardingRevocations`（:104-121）には呼出元が無い。`ExternalAccountServiceImpl.requestRevokeWithIdempotency`（:199-290）は `claimRevokeRequest`（ExternalAccountReferenceMapper.java:112-126、`status='PENDING_CONFIRMATION'`・`next_retry_at=requestedAt`・`idempotency_key IS NULL` 条件）の直後に同期送信する。claim後・送信前に停止すると、poll（`selectPendingForTenant` :52-58、`claimRevokePoll` :129-141、`completeRevokePollWithCas` :144-160）がその行を拾って確認だけを行い（ExternalAccountServiceImpl.java:296-336。`requestRevoke` は呼ばない）、sync状態をTIMEOUT/SYNC_FAILEDへ書き換えるため、要求は二度と送られない。
- prodのproviderは `FailClosedExternalAccountProviderClient`（requestRevokeはfalse、確認はUNKNOWN）。

変更（W3-11、DDLなし。失効要求の状態は既存の `t_external_account_reference` 行に持たせる）:

1. 列挙の範囲: system scope（SecurityContextを使わない）で `tenant_id = t_lifecycle_case.tenant_id`、`assignee_type = 'ENGINEER'`、`assignee_id = 要員`、`deleted_flag = 0`、`status <> 'REVOKED'` の行を**全法人**から列挙する。gateの計数と完了時の登録は同じ列挙結果を使う。操作者の法人で絞らない。
2. 分類:
   - 登録済み: `idempotency_key` 設定済み（どの経路の要求でもよい）。ブロックしない。
   - 登録可能: `idempotency_key IS NULL` かつ行の `legal_entity_id` が非NULL。ブロックしない（完了時に登録する）。
   - 登録不能: 行の `legal_entity_id IS NULL`（所有法人の不明な行）。ブロックする。別法人の行は登録不能にしない。
   - gate #6 の外部アカウント条件を「登録不能件数 == 0」に置き換え、項目のmessageに「完了時に失効要求を登録」「失効要求登録済み（確認待ち）」を表示する。資産とlicenseの条件、免除台帳による免除（:157-162）は変えない。
3. 状態（`external_sync_status` に新しい値を足す。列はVARCHAR(32)でCHECK制約が無い、V129__asset_account_license_lifecycle.sql:134）:

   | 状態 | `status` | `external_sync_status` | `next_retry_at` の意味 | 処理するもの |
   |---|---|---|---|---|
   | 登録済み・未送信 | PENDING_CONFIRMATION | `REVOKE_REQUESTED` | 送信予定時刻 | 送信worker |
   | 送信中 | PENDING_CONFIRMATION | `REVOKE_SENDING` | 送信leaseの期限 | 送信worker（lease切れの回収） |
   | 送信失敗・再送待ち | PENDING_CONFIRMATION | `REVOKE_SEND_FAILED` | 再送時刻（指数backoff、上限1440分） | 送信worker |
   | 送信済み・確認待ち | PENDING_CONFIRMATION | `REVOKE_SENT` | 確認pollの時刻 | 既存poll |
   | 送信結果不明 | UNKNOWN | `REVOKE_SEND_UNKNOWN` | 確認pollの時刻 | 既存poll（確認だけ）・手動 |
   | 失効済み | REVOKED | （既存値） | – | – |

4. 登録（completeCaseのtransaction内、§3.1 順14）: 登録可能な各行を `selectOwnedByIdForTenant` の型（ExternalAccountReferenceMapper.java:31-34）で行自身の法人を使って `FOR UPDATE` し、新設のCAS（`idempotency_key IS NULL`・`revoke_confirmed_at IS NULL`・`status <> 'REVOKED'`・version一致）で `REVOKE_REQUESTED` にする。0件更新なら再読込し、別の要求で登録済みなら登録済みとして扱い、それ以外は409でrollbackする。completeCaseはproviderを呼ばない。
5. key: `lifecycle-resign:{caseId}:{accountId}:g{n}`（nは失効要求のgeneration、初回1）。tenantと法人は一意key `(tenant_id, legal_entity_id, idempotency_key)`（V182__nf09_tenant_scope_hardening.sql:236-238）に含まれるのでkeyに入れない。最長77文字で、列の `VARCHAR(128)`（V129:127）に収まる。
6. 送信worker: afterCommitで `NOT_SUPPORTED` のdispatcherを起動し、加えて取りこぼし回収のscheduled methodを `AssetLifecycleScheduler` に追加する（短い間隔、`@SchedulerLock`、`TenantAwareBatchRunner` でtenantごとに `runWithTenant`）。各行を `REVOKE_REQUESTED`/`REVOKE_SEND_FAILED`（時刻到来）→ `REVOKE_SENDING` へversion付きCASで取得してからproviderの `requestRevoke` を呼ぶ。CASで1行1送信者に限るので、同じkeyの同時実行でもproviderは1回だけ呼ばれる。受理 → `REVOKE_SENT`（確認pollへ）。不受理・例外 → `REVOKE_SEND_FAILED`（retry_count+1、backoff）。workerは `requireCurrentScope()` を使わず、attributionは既存のclosed pair（`SYSTEM`/`SCHEDULER_POLL`、`PROVIDER_*`）だけを使い、`LEGACY_UNRESOLVED` を新たに作らない。
7. 送信中の停止（ACK前crash）: `REVOKE_SENDING` のleaseが切れた行は、providerが同じkeyでの再送を冪等に扱うと宣言している場合だけ `REVOKE_REQUESTED` へ戻して再送する。宣言が無い場合（prodの現行provider）は `UNKNOWN`/`REVOKE_SEND_UNKNOWN` にして通知する（現行契約§8の「ACK前crashはUNKNOWN」）。
8. poll: `selectPendingForTenant`・`claimRevokePoll`・`completeRevokePollWithCas` に `external_sync_status NOT IN ('REVOKE_REQUESTED', 'REVOKE_SENDING', 'REVOKE_SEND_FAILED')` を加え、未送信の行に触れない。送信と確認を別の処理に分ける。
9. 再要求（generation）: 未処理一覧から管理者が `UNKNOWN` の行（`revoke_confirmed_at IS NULL`、keyがlifecycle形式）を再要求すると、CASでkeyを `g{n+1}` に替えて `REVOKE_REQUESTED` へ戻す。旧keyの結果がその後届いても、別keyなので新しい要求と混同しない。lifecycle形式でない既存keyの行は手動確認（`confirmRevokeManually`）で扱う。
10. 退社完了後に登録されたアカウント（R2 §4.2(8)）: `registerAccountReference`（ExternalAccountServiceImpl.java:69-）は割当先要員の `t_engineer` 行を `FOR UPDATE`（順7）してからinsertする（順14）。その要員に完了済みのRESIGNATION案件があれば、同じtransactionでその案件のkeyにより失効要求を登録し、管理者へ通知する。completeCaseは要員行のX lockを保持して列挙・登録するので、完了の前に登録された行は列挙に入り、後に登録された行は自動で登録され、どちらも漏れない。
11. 失効対象の固定（R2 §4.2(9)）: 登録後（`idempotency_key` 設定済みかつREVOKED以外）は `updateAccountReference`（ExternalAccountServiceImpl.java:115-）で識別子・権限を変えられないようにする（409）。
12. 失敗時の運用: 送信失敗・送信結果不明・backoff上限到達で管理者・HRへ通知し、ERRORログを出す。通知・一覧の識別子は `maskIdentifier`（ExternalAccountServiceImpl.java:56）を通す。行は既存の外部アカウント一覧（`searchAccountsScoped`）に未処理として残り、手動確認（`confirmRevokeManually`）で閉じる。完了はproviderの結果を待たない（B-NF01-7）。runbookに手動失効と `confirm-revoke` の手順・担当・SLA・通知の閾値を書く（R2 §4.2(6)）。
13. 既存経路の整理: `requestRevokeWithIdempotency` は登録と送信workerの組合せの上に作り直し、claim直後の同期送信を無くす。`triggerOffboardingRevocations` は置き換える。license解放は本変更の対象外で、gateのblockerのまま残す（自動解放を残す場合は退社基準日を使い、licenseごとに409を隔離する。R2 §4.2(7)）。
14. 配備前提: workerはtenantごとに動き、`ACCOUNTING_TENANT_IDS` は1 tenantだけとする（R2 N9-P3-3）。配備前提の一覧（X3-4）に書く。

epochとの関係: 外部アカウントはSES DBの認可述語ではないのでepochを持たない。completeCaseと退社後登録のlock列に順14として入るだけである。W3-16bでcompleteCaseを並べ替えるときは、列挙・登録を1つのメソッドのまま新しいlock列へ移す。

### 5.14 portal（Q-10）とepochの関係

- portal利用者と要員のlink（表・管理画面・監査）はW3-02（9f6d7c0e）が実装済み（N1-4）。B-NF01-8によりemail照合（ResignationGateChecker.java:347-360）はlink照合へ置き換わり、linkの無いportal利用者はgateの10項目目で「要確認」として列挙される。
- portal利用者はCONFIRMの主体ではなく（design.md §6 表2）、linkはCONFIRMの述語に入らない。したがってepochを持たず、link変更で増分しない。
- 退社完了との線形化: W3-02のlink writerは対象要員の `t_engineer` 行を `FOR UPDATE` してからlinkを追加・解除する（PortalEngineerLinkServiceImpl.java:38-42,90-91,140-141、9f6d7c0e。§3.1 順7 → 12）。completeCaseはengineer Xを保持してlinkを読み、linkのあるportal利用者のsessionを失効させる（葉）。W3-02のwriterが要員行より前に `t_portal_user` をlockする経路を追加する場合は、portal userのrankを別途定める。
- linkの無いportal利用者は退社時に自動処理しない。gate結果に「要確認」として列挙し、ブロックしない（Q-10）。

## 6. テスト計画

### 6.1 MySQL（`@Tag("mysql")`、Testcontainers、Docker必須、skip 0）

M2〜M7はW3-21より前に実装されるため、confirm attemptと同じlock列を取るテスト用reader（W3-10の `AuthorizationEpochReader` と `LifecycleLockExecutor`）で読み手を模す。待ちの判定はbatch 0 r2と同じく `performance_schema.data_lock_waits` のrequester/blocker対で行い、`LOCK_DATA` を連結条件にしない。

| # | クラス（仮称） | バッチ | 推奨shard（ラウンド） | 受入条件 |
|---|---|---|---|---|
| M1 | `AuthorizationEpochSchemaMySqlTest` | W3-10（W3-18で拡張） | shard-1（第2） | 空DB→LATEST。global行(1,0)が1行、5列がNOT NULL DEFAULT 0でCHECKが負値を拒否。増分でupdated_at・versionが不変。上限で409かつ値不変。増分後にentity全体の `updateById`（EngineerStatusServiceImplの型）を実行してもepochが変わらない。reader `FOR SHARE` 中にwriter `FOR UPDATE` が待つ。W3-18でB0の表・一意key・FK・（採用時）triggerの拒否を追加 |
| M10 | `ResignationRevocationOutboxMySqlTest` | W3-11 | shard-2（第2） | 多法人tenantで、法人AのHRが法人Bの要員の案件を完了すると、法人A・B双方のアカウントが各行の法人で登録される。完了をrollbackすると登録が0。同じkeyの送信を並行させてもproviderの呼出しは1回。登録後・送信前に停止した行を回収workerが送る。`REVOKE_SENDING` のlease切れは冪等でないproviderでUNKNOWNになる。pollが未送信の行を書き換えない。完了後に登録したアカウントが自動で登録される。UNKNOWN後の再要求で `g2` のkeyになる |
| M2 | `GlobalEpochFenceMySqlTest` | W3-15 | shard-2（第3） | replaceMenus・updateAll（認可キー）× reader の両順序。writer先行→reader側の値が新しい、reader先行→writerが待つ。非認可キー・同一内容でno-op。S→X昇格なし |
| M3 | `ActorEpochFenceMySqlTest` | W3-15 | shard-2（第3） | updateUserStatus・role変更・deleteUser・replaceAssignments × reader の両順序。入れ子で増分1回。別ユーザーは不変。変更申請承認のemail更新が並行した無効化を上書きしない |
| M4 | `EngineerScopeFenceMySqlTest` | W3-16a | shard-3（第3） | 営業割当・解除、link・unlink、organization_id変更、会計履歴 × reader の両順序。要員Bへの書込みは要員Aのreaderを待たせず、Aのepochも変えない（細粒度の証拠）。契約の `sales_user_id` NULL→Bでepochが変わらない |
| M5 | `OrganizationScopeFenceMySqlTest` | W3-16a | shard-3（第3） | 親付替・無効化・統合で旧・新祖先鎖だけが増え、無関係な部分木は不変。所属writer（`updateUserOrganization` による `manager_user_id` 変更を含む）で本人actorと連携要員engineerが増える。いずれも × reader の両順序。mergeとassignUserの同時実行で1213なし（§11 F-4） |
| M6 | `ResignationCompletionLockOrderMySqlTest` | W3-16b | shard-3（第4、W3-16aと別ラウンド） | 並べ替え後のcompleteCase × 連携ユーザーのF1 writer、× readerの両順序で1213/1205なし。連携ユーザーstatus=0、actor・engineerが各+1、`released_at`・所属`valid_to`が基準日、外部失効要求の登録（W3-11）が完了と同じcommitに入る。既存 `LifecycleLockOrderMySqlTest` の想定順序を新しい順序へ更新 |
| M7 | `DocumentEligibilityFenceMySqlTest` | W3-17 | shard-1（第3） | link追加・削除、hold設定・解除、廃棄申請・却下・執行、保存期限job（tenant contextなし）× reader の両順序。版追加で不変。資産・紛失・service requestからSKILL_SHEETへのlinkで増分。confirmSkillSheetがregisterGenerated前にlockを持たない |
| M8 | `DualConfirmationRaceMySqlTest` | W3-21a | shard-2（第4） | 2人の同時確認で1件だけ確定、他方は同結果または409。確認成功後の同key・同hashの再送は認可有効時に保存済みの結果、失効後は409。同key・異hashは409。保留→再開・担当変更後の旧提出は409。提出×失効、確認×失効（global・actor・engineer・組織・文書の各writer）の両順序 |
| M9 | `DualConfirmationCoordinatorRetryMySqlTest` | W3-21a | shard-2（第4） | attempt中の依存変化でrollback→新discovery、3回目で409。1213・1205（`QueryTimeoutException`・`TransactionTimedOutException` を含む）を予算に計上。attempt内の `@@transaction_isolation` がREAD-COMMITTED。ambient transactionからの呼出しを拒否。deny eventがattemptのrollback後も残る |

- shardの割当は推奨であり、最終決定はrootが行う（第3波計画§2「1ラウンドで1shardファイルは1バッチ」）。W3-16aとW3-16bは同じshard-3を推奨するため、別のラウンドに置く。現在の件数は shard-1/2/3 = 32/30/33。
- 各テストクラスは1つのshardファイルにだけ登録する（MySqlTestShardInventoryTest）。クラス名に `Concurrent` を含めない（TestIsolationAuditTest、batch 0報告§5）。
- 既存 `LifecycleMySqlIntegrationTest` は `disabledWithoutDocker = true`（同:56）だが、新クラスはこれを真似ない。

### 6.2 fast（H2・単体・MVC）

| # | クラス（仮称） | バッチ | 受入条件 |
|---|---|---|---|
| U1 | `LifecycleLockPlanTest`（`LifecycleCanonicalizationTest` 更新を含む） | W3-10 | 新rankの並び（承認申請がsys_userより前）、同一keyのmode統合（Xが勝つ）、partition順、`requireSameDependencies` がkey・modeの増減・変化で失敗 |
| U2 | `AuthorizationEpochFenceTest` | W3-10 | no-opで増えない、同tx重複で1回、上限で409、行欠落で409、transaction外の呼出しを拒否 |
| U3 | `AuthorizationConfigKeyFenceTest` | W3-15 | §4.5 |
| U4 | `GlobalActorWriterFenceTest`、`ScopeSourceWriterFenceTest`、`DocumentEligibilityWriterFenceTest` | W3-15 / W3-16a / W3-17 | §4.5（継承した `IService` 書込みと全体更新の検出、SKILL_SHEET生成者の固定を含む） |
| U5 | `AuthorizationEpochColumnIsolationTest` | W3-10 | §4.5 |
| U6 | `ExplicitActorAuthorizationEvaluatorParityTest` | W3-15 | 5 role × scope有無 × 組織scope有効/無効で、既存 LifecycleScopeService・DataScopeServiceImpl・AuthorizationServiceImpl と同じ結果。SecurityContext・config cache・MenuCacheService・ReportScopeContextを読まない |
| U7 | F1 writerの単体テスト（RoleMenu、SystemConfig、SysUser、PermissionGroup、EngineerChangeRequestApprovalAdapter、Profile） | W3-15 | 増分条件とno-op。updateUserのtenantId変更が400、createUserがbodyのtenantIdを無視して操作者のtenantになる。applyProfile・password変更が列限定更新である |
| U8 | F2 writerの単体テスト（EngineerSales、AccountLink、Engineer、Organization、lifecycle generation） | W3-16a/b | 増分条件、lockの順序（InOrder）、保留・取消・担当変更・免除でgeneration+1、再開で不変、DUALの既存completeは409のまま、OrganizationServiceImpl.updateById overrideが無い（またはfence済み） |
| U9 | `DocumentEvidenceEligibilityTest` とF3 writerの単体テスト | W3-17 | §5.1の条件3〜10の各否定が期待のkey、tenant不一致のlink行でfail-closed、版追加で不変、保存期限jobが文書自身のtenantで増分する |
| U10 | `ResignationGateRevocationOutboxTest` | W3-11 | 登録済み・登録可能（別法人の行を含む）はブロックしない、法人不明の行はブロック、登録は完了と同じtransaction、rollback時に登録が残らない、afterCommitで送信、失敗で通知と一覧残存、通知の識別子がマスクされる。既存の `AssetOffboardingServiceTest:232`、`ExternalAccountFailClosedOffboardingIntegrationTest:127` を新契約で書き直す |
| U11 | `LifecycleDualConfirmationCoordinatorTest` | W3-21a | `NEVER`、予算の計上対象（`TransactionTimedOutException` を含む）、決定的拒否は再試行しない、deny recorderがrollback後に呼ばれる、エラー対応表 |
| U12 | `LifecycleDualConfirmationApiControllerTest` | W3-21a | routeとmenu、スイッチOFFで400、未知property 400（`@JsonAnySetter`）、Idempotency-Key検査、`/api/my` に確認routeが無い、CSRF |
| U13 | `LifecycleTemplateDualPolicyTest` | W3-21b（W3-02の `LifecycleTemplateDualConfirmationGuardTest` を拡張） | スイッチOFFでDUALテンプレート400、ONで許可 |
| U14 | 画面テスト（Thymeleaf/MVC）と新keyの4言語 | W3-21b | 提出・確認・差戻しの要素とkeyの存在、MessageBundleConsistencyTest |
| U15 | `WriterIsolationPolicyTest` | W3-15 / W3-16a / W3-16b | §2.4 E-3の対象メソッドが `READ_COMMITTED` を宣言している |

### 6.3 各バッチ共通の全体guard

`TestIsolationAuditTest`、`MySqlTestShardInventoryTest`、`MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`、`TransactionalRollbackForAuditTest`、`ControllerTransactionalBanTest`、`ActionPermissionMatrixTest`、`AllMappersSchemaSweepTest`、`FeatureSwitchRouteCoverageTest`。DDLバッチ（W3-10、W3-18）は加えて `LatestMigrationVersion`、`ProductionSchemaVersionGuardTest`、Flyway smoke。migration integrityはclasspathを読むため `mvn clean` を前提とする。回帰はbatch 0 r2報告§2の一覧（Lifecycle系、ResignationGateFailureDrillTest、EngineerSalesServiceImplTest、AssetOffboardingServiceTest 等）を対象テストとして毎回含める。新しいscheduled methodを足すバッチ（W3-11）はscheduler方針のテスト（SchedulingDisabledDrainTest等）も含める。

## 7. バッチ計画

### 7.0 依存関係

```
W3-01（本計画）
  └─ W3-10 epoch A spine（DDL）
       ├─ W3-15 F1 global/actor（＋明示actor評価、F-1、F-7）
       ├─ W3-16a F2 要員・組織
       ├─ W3-17 F3 document（NF06-A1a W3-05の後）
       └─ W3-18 B0 coordinator schema（DDL、W3-15の後）
W3-02 NF01-0（L7、DUAL template 400、versionless・CLEAN、portal link、preflight runbook）
  └─ W3-11 外部アカウント失効outbox（DDLなし）
       └─ W3-16b F2 lifecycle（W3-16aと別ラウンド）
W3-21a coordinator＋API ← W3-15, W3-16a, W3-16b, W3-17, W3-18, W3-02, W3-11
  └─ W3-21b 画面・テンプレート連動（W3-02の画面変更の後）
```

- W3-02の辺: W3-02（9f6d7c0e）は `LifecycleCaseServiceImpl`、`LifecycleTaskServiceImpl`、`LifecycleTemplateServiceImpl`、`ResignationGateChecker`、`lifecycle.js` 等を変更した。W3-11・W3-16b・W3-21a/bはW3-02の統合後の状態を基準にする。
- W3-15はW3-18・W3-21aより前に統合する（F-1。B0のFK `(tenant_id, user_id)` はtenant変更をFK RESTRICTで500にするため）。
- 同じファイルを触るバッチの順序: ResignationGateChecker・LifecycleCaseServiceImpl は W3-02 → W3-11 → W3-16b。DocumentServiceImpl は W3-05（NF06-A1a）→ W3-17。EngineerServiceImpl は W3-16a だけ（W3-15はEngineerServiceImplとResignationGateCheckerを触らない）。SysUserServiceImpl・EngineerChangeRequestApprovalAdapter・ProfileApiController は W3-15 だけ。
- 共有の新規ファイル（`AuthorizationEpochFence`、`AuthorizationEpochMapper`、`LifecycleLockPlan`）はW3-10が作り、後続バッチは呼出しだけを追加する。
- messages・CLAUDE.md の衝突は統合時に解決する（第3波計画§2）。

### 7.1 W3-10 epoch A spine（DDL `V?`）

- 目的: epoch列とglobal表、mapper、fence・readerの部品、lock plan。業務経路は変えない。
- ファイル: `db/migration/V?__nf01_fine_grained_authorization_epoch.sql`（新規）、`src/test/resources/sql/engineer-schema-h2.sql`、`schema-document-archive-h2.sql`、`schema-lifecycle-workflow-h2.sql`、必要ならH2版migration、`src/test/resources/application-test.yml`、`LatestMigrationVersion`、`ProductionSchemaVersionGuardTest`、配備runbookの版行、`mapper/AuthorizationEpochMapper.java`（新規）、`service/security/epoch/AuthorizationEpochFence.java`・`AuthorizationEpochReader.java`（新規）、`service/lifecycle/evidence/LifecycleLockPlan.java`、`LifecycleLockExecutor.java`（新規）、`LifecycleAuthorizationEpochs.java` → `LifecycleAuthorizationFence.java`、`messages*.properties`（`error.authorization.epochUnavailable`）、テスト U1・U2・U5・M1。
- 規模: 約800〜1,100行（テスト含む）。
- 完了条件: M1・U1・U2・U5 PASS、全体guard PASS、既存lifecycle回帰に差分なし。
- Demo: 使い捨てMySQL 8で空DB→LATEST。(1) `SELECT * FROM t_authorization_global_epoch` が (1,0) の1行。(2) 5列の定義（NOT NULL DEFAULT 0、CHECK）を `information_schema` で確認。(3) テストから要員1件を増分し、epoch=1、updated_at・versionが不変。続けてその要員をentity全体で `updateById` してもepochが1のまま。(4) epochを上限に設定して増分すると409で値不変。(5) 2 sessionでreaderの `FOR SHARE` 中にwriterの `FOR UPDATE` が待つことを `data_lock_waits` で確認。(6) 画面で要員・利用者・文書の更新が従来どおり動く（writer未接続）。

### 7.2 W3-11 外部アカウント失効outbox（DDLなし、W3-02の後）

- 目的: §5.13。
- ファイル: `ExternalAccountService`・`ExternalAccountServiceImpl`（system scopeの列挙、登録 `MANDATORY`、送信worker `NOT_SUPPORTED`、再要求、退社後登録時の自動登録、登録後の対象固定）、`ExternalAccountReferenceMapper`（全法人の列挙、登録CAS、送信状態のCAS、poll条件への除外追加）、`AssetOffboardingServiceImpl`（clearanceの分類、`triggerOffboardingRevocations` の置換）、`OffboardingClearanceResultDto`、`ResignationGateChecker`（gate #6）、`LifecycleCaseServiceImpl.completeCase`（登録とafterCommit起動）、`AssetLifecycleScheduler`（回収用のscheduled method）、通知、未処理一覧の再要求操作、`messages*.properties`、engineer-lifecycle-workflow runbook（手動失効・confirm-revoke・担当・SLA・通知閾値）、テスト U10・M10、`scripts/test-suites/mysql-shard-*.txt`（M10の登録）、既存の退社系テスト更新。
- 規模: 約1,200〜1,600行（テスト含む）。
- 完了条件: U10・M10 PASS、ResignationGateFailureDrillTest・LifecycleMySqlIntegrationTest・AssetOffboardingServiceTest・ExternalAccountFailClosedOffboardingIntegrationTest の回帰 PASS、scheduler方針のテスト PASS、全体guard PASS。
- Demo: 法人A・Bを持つtenantで、法人Aに所属するHRが、法人Bの要員（外部アカウント: 法人A所有1件、法人B所有1件）の退社案件を完了する。(1) gate画面が2件とも「完了時に失効要求を登録」と表示しブロックしない。(2) 完了後、DBで2件が各行の法人のまま `PENDING_CONFIRMATION`・`REVOKE_REQUESTED`、`idempotency_key = lifecycle-resign:{caseId}:{accountId}:g1`。(3) commit後の送信でproviderなしのため `REVOKE_SEND_FAILED` になり、管理者に通知（識別子はマスク）が届き、外部アカウント一覧に未処理として残る。(4) 送信前にアプリを止めた行が、再起動後の回収workerで送信される。(5) 完了を409でrollbackさせると登録が残らない。(6) 完了後に同じ要員へアカウントを登録すると、自動で失効要求が登録される。(7) UNKNOWNにした行を再要求するとkeyが `g2` になる。(8) 法人不明（`legal_entity_id` NULL）の行があるとgateがブロックする。

### 7.3 W3-15 F1 global/actor（DDLなし、W3-18・W3-21aより前に統合）

- 目的: §4.1のwriter、`ExplicitActorAuthorizationEvaluator`、F-1、F-7。
- ファイル: `RoleMenuServiceImpl`、`SystemConfigServiceImpl`、`AuthorizationConfigKeys`（新規）、`SysUserService`・`SysUserServiceImpl`（create・updateのtenant、password用の列限定メソッド）、`UserApiController`（必要ならtenantIdの扱いの明示）、`ProfileApiController`、`PermissionGroupManagementServiceImpl`、`EngineerChangeRequestApprovalAdapter`、`ExplicitActorAuthorizationEvaluator`（新規）、`OrganizationScopeServiceImpl` と `AuthorizationServiceImpl` への明示actor用メソッドの追加（既存メソッドの挙動は変えない）、`messages*.properties`、テスト U3・U4a・U6・U7・U15（F1分）・M2・M3。
- 規模: 約1,300〜1,700行。
- 完了条件: 上記テストと全体guard PASS、UserApiController・RoleMenuApiController・SystemConfigApiController・ProfileApiController・変更申請承認の既存テスト PASS。
- Demo: (1) 役割メニュー画面で営業のメニューを1つ外して保存→global epoch+1、同じ内容で再保存→不変。(2) システム設定で `scope.sales-own-data-only` を切替→+1、通知系のキー変更→不変。(3) 利用者を無効化→その利用者のactor epoch+1、他の利用者は不変。(4) 利用者のrole変更→+1（入れ子の割当リセットと合わせて1回）。(5) `PUT /api/users/{id}` に別のtenantIdを含めると400、`POST /api/users` に別のtenantIdを含めても操作者のtenantで作成される。(6) 要員のemail変更申請を承認しても、その間に管理者が行った利用者の無効化が残る。(7) parityテストの結果表。

### 7.4 W3-16a F2 要員・組織（DDLなし）

- 目的: §4.2前半のwriter、F-4、F-5、F-8。
- ファイル: `EngineerSalesServiceImpl`、`EngineerAccountLinkServiceImpl`、`EngineerServiceImpl`、`EngineerMapper`、`OwnershipRepairServiceImpl`、`OrganizationServiceImpl`（公開 `updateById` overrideの削除を含む）、`UserOrganizationMapper`、テスト U4b・U8（要員・組織分）・U15（F2分）・M4・M5、既存の EngineerSalesServiceImplTest・EngineerServiceImplTest・組織系テストの更新。
- 規模: 約1,200〜1,600行。大きい場合は「要員・link・営業」と「組織・所属」に分ける。
- 完了条件: 上記テストと全体guard PASS。
- Demo: (1) 要員Aに担当営業を割当→Aのengineer epoch+1、要員Bは不変。(2) 組織Xの親を付替→X・旧親の祖先鎖・新親の祖先鎖だけ+1、無関係な部分木は不変。(3) 利用者Uを組織へ配属→Uのactor epoch+1、Uの連携要員のengineer epoch+1。所属の上長を変更→Uの連携要員のengineer epoch+1。(4) 要員削除→連携ユーザーのstatusだけが0になり（他列は不変）、actor・engineerが+1。(5) mergeとassignUserを同時に実行して1213が出ない（M5のログ）。

### 7.5 W3-16b F2 lifecycle（DDLなし、W3-11の後、W3-16aと別ラウンド）

- 目的: §4.2後半（task generation、completeCaseの並べ替え）。
- ファイル: `LifecycleCaseServiceImpl`、`LifecycleTaskServiceImpl`、`ResignationGateChecker`、`LifecycleTaskMapper`（generationの専用select・増分）、`AuthorizationEpochFence` の呼出し、テスト U8（lifecycle分）・U15（completeCase）・M6、`LifecycleLockOrderMySqlTest` 更新。
- 規模: 約900〜1,300行。
- 完了条件: 上記テスト、batch 0 r2報告§2の回帰一覧、全体guard PASS。
- Demo: (1) 案件を保留→非終端taskのgeneration+1、再開→不変、取消→+1。(2) taskの担当変更→+1。(3) 退社案件を完了→DBで連携ユーザーstatus=0とactor+1、要員engineer+1、`released_at` と所属 `valid_to` が基準日、外部失効要求が登録済み、linkのあるportal利用者のsessionが失効。(4) M6で完了と利用者writerを両順序で実行して1213/1205が出ない。

### 7.6 W3-17 F3 document（DDLなし、W3-05の後）

- 目的: §4.3のwriterと文書側の判定部品、F-3。
- ファイル: `DocumentServiceImpl`、`DocumentMetadataCommitService`、`DocumentLinkCommandService`・`DocumentEligibilityService`・`DocumentEvidenceEligibility`・`DocumentEvidenceStorageVerifier`（新規）、`DocumentRetentionJob`、`EngineerChangeRequestServiceImpl`、`AssetAssignmentServiceImpl`、`AssetLostIncidentServiceImpl`、`ExpenseRequestServiceImpl`、`ServiceRequestAttachmentCommitService`、テスト U4c・U9・M7。
- 規模: 約1,200〜1,600行。
- 完了条件: 上記テスト、文書台帳・資産・経費・service desk・変更申請の既存テスト、全体guard PASS。
- Demo: (1) SKILL_SHEETにholdを設定・解除→epochが+1ずつ。(2) 版追加→不変。(3) 資産貸与でSKILL_SHEETをlink→+1、そのlinkのtenantが文書のtenantになり、適格性判定が「link形の不適合」になる。(4) 保存期限jobを実行→tenant contextなしでもPENDING作成で+1。(5) スキルシート確定（confirmSkillSheet）が従来どおり成功し、新しい文書のlink作成で+1。

### 7.7 W3-18 B0 coordinator schema（DDL `V?`、W3-15の後）

- 目的: §5.12の表・一意key・FK、entity・mapper（不変表は更新禁止）。route・業務経路は作らない。
- ファイル: `db/migration/V?__nf01_dual_confirmation_coordinator.sql`（新規）、H2版、`application-test.yml`、`LatestMigrationVersion`、`ProductionSchemaVersionGuardTest`、配備runbookの版行（trigger採用時は件数行も）、entity 7件・mapper 7件（新規）、M1の拡張。
- 規模: 約800〜1,100行。
- 完了条件: M1拡張・AllMappersSchemaSweepTest・MigrationScriptIntegrityTest・全体guard PASS。
- Demo: (1) 空DB→LATESTで7表と4つの一意keyを確認。(2) 不変表へのUPDATE/DELETEがmapperで拒否される（trigger採用時はDBでも拒否）。(3) 他tenantのcaseを指すsubmission insertがFKで失敗する。(4) 既存画面に変化がない。

### 7.8 W3-21a coordinator＋API（DDLなし）

- 目的: §5.2〜§5.9。子スイッチOFFで出荷。
- ファイル: `LifecycleDualConfirmationCoordinator`・`AttemptService`・`DenyRecorder`（新規）、API controller（`LifecycleApiController`・`MyLifecycleApiController` への追加、または新controller）、DTO（`@JsonAnySetter`）、`Feature.java`（子スイッチ）、`application.yml`（`app.features.nf01-dual-confirmation.enabled: false`）、`LifecycleCanonicalization`（CONFIRMのrequest hash拡張）、`messages*.properties`、テスト U11・U12・M8・M9。
- 規模: 約1,800〜2,400行。
- 完了条件: 上記テスト、FeatureSwitchRouteCoverageTest、ActionPermissionMatrixTest（一覧を編集しないこと）、全体guard PASS。
- Demo（API、子スイッチをテスト環境でON）: (1) OFFでA1〜A7が400。(2) ONで担当営業が提出→HRが確認→taskがCOMPLETEDになり後続が昇格。(3) 確認成功後に同じkeyで再送すると保存済みの結果、bodyを変えた同keyで409。(4) 提出者本人による確認、マネージャーによる確認が403。(5) 提出後に担当営業を解除→確認が409 `authorizationChanged`。別の要員の担当変更では確認できる（細粒度）。(6) SKILL_SHEET以外、版IDなし、未知のbody propertyが400。(7) 保留→再開後の旧提出が409 `submissionStale`。(8) 拒否eventがrollback後もDBに残る。

### 7.9 W3-21b 画面・テンプレート連動（DDLなし）

- 目的: §5.10、§5.11のテンプレート連動。
- ファイル: `templates/lifecycle/detail.html`、`templates/my/lifecycle-detail.html`、`static/js/modules/lifecycle.js`、`static/js/modules/my-lifecycle.js`、`LifecycleTemplateServiceImpl`（W3-02のDUAL拒否をスイッチ連動に）、`messages*.properties`、テスト U13・U14。
- 規模: 約700〜1,000行。
- 完了条件: 上記テスト、StaticAssetLocalityTest・MessageBundleConsistencyTest PASS、ブラウザでの手動確認。
- Demo: (1) DUAL taskの詳細で「提出」→候補からCLEAN版を選択→提出。(2) 管理者・HRで「確認」「差戻し」。(3) 本人画面（/my/lifecycle）で要員が提出し、確認ボタンは出ない。(4) 390px幅で操作できる。(5) テンプレート画面でスイッチONの時だけDUALを選べる。

## 8. 現行契約との差分表

| 現行契約 | 現行の内容 | 本計画での扱い |
|---|---|---|
| §2 第3段落 | 認可世代 `(global, tenant, submitterActor)`。tenant内のscope source変更でtenant epoch | `(global, 提出者actor, engineer, 組織集合, 文書)` と task generation に置換（§2.2）。tenant epochは廃止 |
| §2 第4段落（起点writer） | 証跡候補を `confirmSkillSheet` → `registerGenerated/link` → `commitNew` で作られた文書に閉じる | 条件3〜10を満たす任意のSKILL_SHEETへ緩和（§5.1）。SKILL_SHEETの生成者が `confirmSkillSheet` だけであることをU4cで固定 |
| §2 第4段落（公開route・閉包） | 公開confirmation routeを作らない。全document type・全attachment writerの閉包までCONFIRMを有効化しない | 初回sliceでrouteを作り、SKILL_SHEET×単一ENGINEERだけを受理、他は明示拒否（Q-2）。閉包の対象はこのsliceの述語が読む行とそのwriterに限定（§4、§5.1） |
| §3 第1段落 | `DOCUMENT_LINK` を含む全evidence typeが同じfacadeを通り、evidence type別の認可迂回を認めない | 初回sliceでfacadeを通るのはDUAL_CONFIRMATIONだけ。DOCUMENT_LINKの既存完了経路はW3-02のversionless拒否・CLEAN確認・tenant照合のまま（§5.1、§9 RR-8、§10で署名） |
| §4 第1段落 最終文 | 非DUALの文書証跡も新facadeを経由させる | 同上 |
| §4 第3段落 | submissionにtenant epochを保存 | engineer epochと組織fence子表を保存（§5.12） |
| §6 lock順(1)〜(8) | global → tenant → sys_user・actor epoch → case/task → typed parent → document → version → child | §3.1に置換。tenantを削除、actor epochはsys_user行、承認申請をsys_userの前に置き、scope子行・portal link・外部アカウントのrankを追加 |
| §6 表 tenant authorization epoch行 | tenant内の全scope述語をtenant epochで粗くfence。`sales_user_id NULL→B` で全sales提出が失効 | 廃止。ENGINEER対象の述語は契約を読まないため、契約writerはfence対象外（§4.4） |
| §6 表 active actor・actor epoch行 | 別表 `t_actor_authorization_epoch` | `sys_user.actor_authz_epoch` 列（§2.3）。所属変化・clearManagerも本人actorへ |
| §6 表 sales DataScope・manager/engineer/asset行 | tenant epochをanchorに全source writer | 要員行・組織行をanchorにしたengineer・組織部分木epoch（§2.2、§4.2）。資産はfence対象外 |
| §6 表 document ACL・typed target行 | typed parentを先にlockしてからdocument | documentのX lockとdocument epochで足りる。typed parentのlockは呼出元に既存のもの（資産等）だけ（§4.3） |
| §7 Epoch fence・Current reads | tenant epochの `NULL→B` を2-sessionで確認 | M4で「契約の `sales_user_id` 変更ではepochが変わらない」ことを確認（§6.1） |
| §7 末段 CONFIRM解放gate | 全document type・typed targetを閉じるかunsupportedを個別承認 | unsupportedはQ-2で明示拒否と決定済み。Document Ownerの署名で確定（§10） |
| §8 durable revoke request | HR/adminが明示開始する、case/engineer/account generation・stable key付きのrequestを短txで保存し、commit後にworkerが送る。claim後・送信前crashに備えdurable dispatcherとsend-stateを持つ。送信後ACK前crashはprovider契約が無ければUNKNOWNのままblock | 採用: case・account・generation由来のkey（tenantは一意keyに含まれるのでkeyに入れない）、commit後の送信worker、送信状態（REQUESTED/SENDING/SEND_FAILED/SENT）と回収、provider I/Oを完了txの外に出すこと、ACK前crashはUNKNOWN（冪等なproviderだけ再送）。変更: 開始は退社完了時の自動登録（Q-6）、UNKNOWNでも退社完了をblockせず通知と未処理一覧で扱う（B-NF01-7）、専用outbox表を作らず既存の外部アカウント行を状態機械として使う（DDLなし、§12 O-7）、engineer generationは使わない（keyのcaseIdで足りる）（§5.13） |
| §9.1 tenant/actor epoch表、tenant backfill | tenant集合のseedとprovision手順 | 不要。DEFAULT 0の列（空DB新規導入、P-2）。preflightのtenant mappingはepoch Aを止めない |
| §9.2 global DataScope config行 | `scope.sales-own-data-only` の変更でglobal | 認可キー台帳（初期値は同キーのみ）に一般化し、guardで固定（§4.1、§4.5） |
| §9.2 tenant scope行 | contract・proposal・project・activity・engineer・sales・account link・organization・assetの全writerにtenant root | 要員・組織・所属のwriterだけをfence（§4.2）。他は対象外（§4.4） |
| §9.2 actor status/role行 | global shared → tenant root → user X → actor epoch X | user X（actor epoch同居）だけ（§4.1） |
| §9.2 lifecycle generation行 | hold/cancel/reassign等でgeneration、completeCaseを分割 | 同じ方針。completeCaseは単一REQUIRED（READ_COMMITTED）で並べ替え、coordinatorは使わない（§4.2） |
| §9.3 第2段落 | lock順とtenant epoch照合 | §3.4、§5.5の列と照合項目に置換。境界（NEVER、REQUIRES_NEW、RC、最大3回）は維持し、1213/1205を予算に含める（B-NF01-5） |
| §9.5 batch 1〜6 | preflight → spine → global/actor → tenant scope → document → coordinator | §7のW3-10〜W3-21bに置換。preflightはW3-02（N1-12）のrunbookへ |

## 9. 残留リスク

- RR-1 時間で変わる述語: 所属・組織の `valid_to` の経過、未来日付の所属・relation history の発効はwriterを伴わずに述語を変える。epochでは守れず、確認時の述語再評価で拒否する。提出と確認の間に「時間で外れ、未来日付の行で戻る」場合は確認できてしまう。
- RR-2 配備時のproperty変更: `organization.scope.enabled`（application.yml、OrganizationScopeServiceImpl.java:69-70）や機能スイッチの変更はepochを進めない。確認時の再評価で拒否側には効くが、2回の配備をまたぐ復活は検出しない。権限関連propertyを変える配備では、未確認の提出を差し戻す運用手順を配備runbookに書くこと（または起動時にproperty指紋を比較してglobalを進める改修を別途検討）。
- RR-3 組織にtenantが無い: 組織epochはtenantに依存しない。tenant間で組織を共有すると過剰失効になる（失効漏れは起きない）。
- RR-4 storageの外部状態: storageのopen・hash検証はattempt外で行い、DBのlockでは線形化しない。改ざん・消失は検証時点でしか検出しない。
- RR-5 外部provider: 外部アカウントの実失効はprovider接続まで手動。退社完了は失効を待たない（B-NF01-7）。IdP側の停止はNF01の完了条件に含まれない。
- RR-6 単一インスタンス前提: セッション台帳・PWA restoreのregistryはプロセス内。複数インスタンスでの失効の即時性は保証しない（既存）。
- RR-7 lock競合: CONFIRMの全attemptがglobal行をSで取る。要員行のS lock中は要員の編集が待つ。merge・階層変更は多数の行をXで取る。いずれも短いtransactionを前提とし、性能gateで確認する。
- RR-8 DOCUMENT_LINKの既存完了経路: 文書ACL・storage hashを検証しない（W3-02のtenant・版・CLEAN照合のみ）。現行契約§3第1段落・§4第1段落の「全evidence typeが同じfacadeを通る」を初回sliceでは満たさない。
- RR-9 管理系fan-out writerの409: merge・階層変更・completeCaseは自動再試行せず、同時変更で409になり得る。
- RR-10 writer閉包はguardテストに依存: Java・注釈SQL以外（手動SQL、DB直接操作）による変更は検出できない。本番DBへの手作業変更は配備runbookで禁止する。
- RR-11 退社gateの資産・license入力: 貸与の新規作成・license割当は要員行をlockしないため、gate評価と完了の間に作られた貸与・licenseを見落とし得る（既存。CONFIRMの述語ではない）。外部アカウントはW3-11の要員行lockと退社後登録時の自動登録で覆う。
- RR-12 H2: `FOR SHARE` の意味論はH2で証明しない。lock順・待ちの証拠はM系だけ。
- RR-13 表単位のrestore・rollback: anchor表（sys_user、t_engineer、m_organization_unit、t_document、t_lifecycle_task）だけを古い行へ戻すとepochが巻き戻り、旧提出が復活し得る。DB全体のPITRは前後で一貫するので問題ない。配備runbookで表単位のrestoreを禁止する。
- RR-14 単一tenant前提: `/api/users` は一覧・取得・更新・削除がtenant境界を持たない（§2.6）。F-1の修正後も、多tenant配備では管理者が他tenantの利用者を操作できる。多tenant公開の前に横断バッチ（X3のUSER-TENANT）で `/api/users` をtenant化し、それまでは単一tenant配備を配備前提（X3-4）に書く。外部アカウント関連の表・schedulerも単一tenantを前提とする（`ACCOUNTING_TENANT_IDS` は1件、R2 N9-P3-3）。

## 10. 外部署名が必要な事項

| 署名者 | 事項 |
|---|---|
| Identity/Security | 確認者 = 管理者・HRの運用承認（B-NF01-5の運用面）、1213/1205を含む3回予算と409の扱い、attemptのtimeout値、エラーkeyと拒否eventの項目、RR-1・RR-2・RR-4の受容、globalを進める認可キーの範囲（§12 O-1）、内部session台帳が記録するtenantの基準（`PersistentSessionServiceImpl.tenantId()` はOIDC設定のtenantを使う）、IdP側停止をNF01の外とすること、外部失効失敗の通知先、RR-8（DOCUMENT_LINKがfacadeを通らないこと）の受容、別法人所有の外部アカウントを各行の法人で失効要求すること（§5.13）、RR-11の受容、RR-14（単一tenant前提）の受容 |
| SRE | RR-14（単一tenant配備、`ACCOUNTING_TENANT_IDS` 1件）を配備前提とすること、RR-13（表単位restoreの禁止）、外部失効の通知の閾値と回収workerの間隔 |
| Document Owner | SKILL_SHEET×単一ENGINEER×1件だけの受理と他種別・他link形の明示拒否（§5.1）、起点writerの緩和と生成者の固定、適格な状態集合 `{DRAFT, CONFIRMED, AMENDED}`、hold・廃棄・link・版追加とepochの規則（§2.2）、`t_document_version.scan_status` を唯一のscan根拠とすること、保存期限jobのservice経由化、資産・紛失・service requestからSKILL_SHEETへlinkした場合に不適格となる扱い、RR-8の受容 |
| HR | 差戻し（REJECT）を含む二者確認の手順、確認者の役割、提出後の担当・所属・権限変更で再提出が必要になる運用（細粒度化で対象は当該要員・当該利用者に限られる）、保留・担当変更・免除でのgeneration失効、外部失効要求の登録で完了を通す運用と未処理一覧の確認・再要求、別法人所有の外部アカウントの扱い（§5.13）、RR-11の受容、linkの無いportal利用者の「要確認」の扱い、`released_at` に退社基準日を残すこと |
| DBA | 2つのmigration（epoch A、B0）、B0の不変triggerの採否とtrigger権限、tenant列の文字集合・照合順序、lock待ちの監視、RR-13 |
| Privacy | submissionのcomment・canonical JSON・拒否eventの保存期間（NF07のretentionとの整合） |

## 11. 静的確認で見つかった既存の問題（対処バッチを割当）

- F-1（W3-15、W3-18・W3-21aより前に統合）: `SysUserServiceImpl.updateUser`（:73-125）はbodyのtenantIdを除外せず、`createUser`（:47-71）もbodyのtenantIdをそのまま保存する（`EntityProtectUtil.protectForCreate` はtenantIdを消さない、EntityProtectUtil.java:12-26。`SysUser.tenantId` は通常のfield、SysUser.java:30）。修正: updateでtenantIdが旧値と異なれば400、createはbodyのtenantIdを無視して操作者のtenantにする。U7でテストする。重大度はP2（`/api/users/**` は管理者限定で、管理者は現状でも全tenantの利用者を操作できるため、権限の拡大は無い）。多tenant配備ではP1相当になるので、`/api/users` 全体のtenant化を横断バッチで行い（RR-14）、単一tenant前提を配備前提（X3-4）に書き、Security・SREの署名を得る。
- F-2（W3-11）: 外部アカウントの照会（ExternalAccountServiceImpl.java:398-402）は操作者の現在法人で絞るため、要員と異なる法人のHR・管理者が完了すると外部アカウントを見落とし得る（R2 N9-P2-1）。tenant内の全法人から列挙し、各行自身の法人で登録する。別法人の行は正常データでありfail-closedにしない。法人不明の行だけをブロックする（§5.13）。
- F-3（W3-17）: `AssetAssignmentServiceImpl` のlink insert（:94-100、:186-192）が `tenant_id` を設定しない（列DEFAULTに依存）。文書のtenantを設定する。修正前のlinkも§5.1条件8の全tenant計数で取りこぼさない。
- F-4（W3-16a）: `OrganizationServiceImpl.assignUser` は 所属行 → 組織行（:506-507）、`merge` は 組織行 → 所属行（:139-140 → :189,196）の順でlockしており、同じ組織と利用者に並行すると循環待ちになり得る（推定、M5で確認）。組織 → 所属 に揃える。
- F-5（W3-16a）: `EngineerServiceImpl.removeById` は連携ユーザーを最後に非lockで読み `updateById(user)` で全列を書き戻す（:106-112）。lost updateとrank逆転。status列だけのUPDATEに改め、順序を§4.2へ。
- F-6（W3-11）: `requestRevokeWithIdempotency`（:199-290）はclaim後・送信前に停止すると同keyで再送せず、pollが未送信の行のsync状態を書き換える。送信状態を分け、pollから未送信行を外す（§5.13）。
- F-7（W3-15）: `EngineerChangeRequestApprovalAdapter.applyProfile` は連携ユーザーを非lockで読み（:172）、`sysUserMapper.updateById(u)` で全列を書き戻す（:175）。読取と書込みの間に管理者が行った無効化・role変更を黙って元に戻す。email列だけの `UpdateWrapper` に改める。
- F-8（W3-16a）: `OrganizationServiceImpl.updateById`（:101-105）は公開overrideで、transaction・recordRelation・増分なしに parent_id・status を書ける。呼出元は無いので削除する（残すならfenceを通す）。
- F-9（NF09の担当へ引継ぎ、本計画では対処しない）: `AssetAssignmentServiceImpl.waiveAssignment` は asset → assignment → 承認申請 の順でlockする（:215-240、:277）。承認確定（申請行を最初にlock）から資産をlockする経路があれば循環し得る。CONFIRMの述語外であり、CONFIRM readerとは循環しない。

## 12. 未決・確認推奨事項

- O-1（利用者確認済みとして扱う。Plan Reviewで解釈が成立と確認）Q-3の「system config」を、認可述語が読むキー（現行は `scope.sales-own-data-only` だけ）に限る。全キーを対象にすると、勤怠同期のcursor（AttendanceSyncServiceImpl.java:720-734）等の定期書込みのたびにglobalが進み、未確認の提出がすべて失効する。
- O-2（Plan Review r2で確認）1提出1文書、差戻し（REJECT）を含めること、`completed_by` を確認者にすること。
- O-3（Plan Reviewで受容済み）storage検証をattempt外で行う設計。条件は§5.4に明記した。
- O-4（DDL laneとPlan Reviewで決定）B0不変表のDB trigger採否（配備runbookのtrigger件数行への影響）。
- O-5（解決）key名はW3-02（9f6d7c0e）で `error.lifecycle.dualConfirmationDisabled`・`error.lifecycle.evidenceVersionRequired`・`error.lifecycle.evidenceVersionNotClean` と確認した。portal link writerのengineer lockはW3-02で実装済み。
- O-6（W3-11とrootで決定）回収用scheduled methodの間隔。scheduler方針のテスト（SchedulingDisabledDrainTest等、第3波計画§2でNF05-Cが所有）への登録が必要になるため、rootがファイルの所有を調整する。
- O-7（Plan Review r2で確認）W3-11は専用のoutbox表を作らず、既存の `t_external_account_reference` 行を状態機械として使う（DDLなし）。R2 §4.2(3)の「アカウント行は読取専用」とは異なるが、pollと送信workerが扱う状態を分けたので、両者は同じ行を同時に書き換えない（§5.13の8）。専用表が必要と判断された場合はDDL laneで番号を取り、W3-11をDDLバッチにする。

## 13. r2の改訂対応表（Plan Review a9e08002）

| 指摘 | 対応箇所 |
|---|---|
| NP-P2-1（W3-11） | §5.13（全面改訂）、§7.2、§6.1 M10、§6.2 U10、§8 現行契約§8の行、§11 F-2・F-6、§12 O-6・O-7、§3.1 順14、§3.4 外部アカウント登録の行 |
| NP-P2-2（writer一覧） | §4.1（EngineerChangeRequestApprovalAdapter、ProfileApiController）、§4.2（OrganizationServiceImpl.updateById、closeAssignmentsForUserのclearManager）、§4.5 U4a・U4b（継承した `IService` 書込み、全体更新の検出、m_menu等）、§4.6、§2.2、§11 F-7・F-8 |
| NP-P2-3（冪等性の順序） | §5.5（SUBMIT・CONFIRMを分けて順序を固定）、§5.7、§5.3、§6.1 M8 |
| NP-P2-4（差分表・署名） | §0（現行契約§2第4段落の起点writer、§3第1段落、§4第1段落の追加）、§8（同3行と§8の採否）、§10（RR-8、別法人外部アカウント、単一tenant前提、RR-11、SRE行） |
| NP-P2-5（F-1） | §11 F-1、§4.1（createUser・updateUser）、§2.6、§5.12、§7.0、§7.3、§6.2 U7、§9 RR-14、§10 |
| NP-P3-1 E-3 | §2.4 E-3、§4.1・§4.2 の各行、§4.5 U15 |
| NP-P3-2 JsonAnySetter | §5.2、§5.9、§6.2 U12 |
| NP-P3-3 条件8 | §5.1 条件8、§6.2 U9 |
| NP-P3-4 起点writer guard | §4.5 U4c、§5.1 |
| NP-P3-5 evaluatorのcache | §4.1、§6.2 U6 |
| NP-P3-6 TransactionTimedOutException | §5.6、§6.1 M9、§6.2 U11 |
| NP-P3-7 所属writerの組織lock | §3.4 |
| NP-P3-8 W3-02の辺とkey名 | §7.0、§5.9、§12 O-5 |
| NP-P3-9 shard・テスト | §6.1（M1、M5、M10、ラウンド） |
| NP-P3-10 保存期限jobのtenant | §4.3、§6.1 M7、§6.2 U9 |
| NP-P3-11 承認adapterのrank倒置 | §3.1（APPROVAL_REQUESTを順2へ）、§3.4、§3.5 規則8、§11 F-9 |
| NP-P3-12 keyの長さ・冗長 | §5.13 の5 |
| NP-P3-13 連携ユーザーのsys_user | §2.1(f)、§2.2、§4.5 U4a |
| NP-P3-14 m_menu等のguard | §2.1(b)、§4.4、§4.5 U4a |
| NP-P3-15 表単位restore | §9 RR-13、§10 |
| R2 §4.2(1)(4)(6)(7)(9)(10)(11) | §5.13 の6・11〜14、§7.2 |
