package com.ses.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * NF01 細粒度認可epoch（V189）の読取・増分を行う専用Mapper（計画 §2.3「増分SQLの形」、§2.4 E-6〜E-9）。
 *
 * <p>epoch列（{@code sys_user.actor_authz_epoch}・{@code t_engineer.engineer_scope_epoch}・
 * {@code m_organization_unit.org_scope_epoch}・{@code t_document.evidence_eligibility_epoch}・
 * {@code t_lifecycle_task.task_generation}）と全体epoch表は entity へ写像しない（E-7）。entityを経由する
 * {@code updateById} が読取時の値を書き戻して増分を巻き戻すのを防ぐため、これらの列を読み書きするSQLは本Mapperだけに置く
 * （{@code AuthorizationEpochColumnIsolationTest} が固定する）。{@link com.baomidou.mybatisplus.core.mapper.BaseMapper}
 * は継承しない（汎用の更新メソッドを持たせない）。
 *
 * <ul>
 *   <li>増分: 同一transactionで当該anchorを {@code FOR UPDATE} 済みであることが前提（E-2）。{@code updated_at = updated_at}
 *       でON UPDATEの自動更新を抑止し（E-8）、{@code version} 列には触れない（E-9）。上限
 *       （{@code 9223372036854775807} = {@code Long.MAX_VALUE}）では0件更新になり、呼出側が409で業務更新ごとrollbackする（E-6）。
 *       論理削除済みの行も増分する（削除そのものが世代を進める変化であるため）。</li>
 *   <li>読取: {@code ...ForShare} はCONFIRM attemptのreader用（MySQLだけで実行する。H2は {@code FOR SHARE} を解釈しない）。
 *       {@code ...ForUpdate} はwriterのanchor先行lock用。どちらも論理削除済みの行を返し、判定は呼出側が行う。</li>
 *   <li>tenantを持つanchorは {@code id + tenant_id} で照合する。組織はtenantに依存しない（計画 §2.6）。
 *       タスク世代は所属案件のtenantで照合し、案件行をX lock済みであることが前提（case X → task X）。</li>
 * </ul>
 */
@Mapper
public interface AuthorizationEpochMapper {

    // ===== 全体（singleton_id = 1） =====

    @Select("SELECT epoch AS epoch, 0 AS deletedFlag FROM t_authorization_global_epoch "
            + "WHERE singleton_id = 1 FOR SHARE")
    EpochRow selectGlobalForShare();

    @Select("SELECT epoch AS epoch, 0 AS deletedFlag FROM t_authorization_global_epoch "
            + "WHERE singleton_id = 1 FOR UPDATE")
    EpochRow selectGlobalForUpdate();

    /** 全体epochは専用表のため、最後に世代を進めた日時として updated_at を更新する（業務行ではないのでE-8の対象外）。 */
    @Update("UPDATE t_authorization_global_epoch SET epoch = epoch + 1, updated_at = CURRENT_TIMESTAMP(6) "
            + "WHERE singleton_id = 1 AND epoch < 9223372036854775807")
    int incrementGlobal();

    // ===== 利用者（sys_user） =====

    @Select("SELECT actor_authz_epoch AS epoch, deleted_flag AS deletedFlag FROM sys_user "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR SHARE")
    EpochRow selectActorForShare(@Param("id") long id, @Param("tenantId") String tenantId);

    @Select("SELECT actor_authz_epoch AS epoch, deleted_flag AS deletedFlag FROM sys_user "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    EpochRow selectActorForUpdate(@Param("id") long id, @Param("tenantId") String tenantId);

    @Update("UPDATE sys_user SET actor_authz_epoch = actor_authz_epoch + 1, updated_at = updated_at "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND actor_authz_epoch < 9223372036854775807")
    int incrementActor(@Param("id") long id, @Param("tenantId") String tenantId);

    // ===== 要員（t_engineer） =====

    @Select("SELECT engineer_scope_epoch AS epoch, deleted_flag AS deletedFlag FROM t_engineer "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR SHARE")
    EpochRow selectEngineerForShare(@Param("id") long id, @Param("tenantId") String tenantId);

    @Select("SELECT engineer_scope_epoch AS epoch, deleted_flag AS deletedFlag FROM t_engineer "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    EpochRow selectEngineerForUpdate(@Param("id") long id, @Param("tenantId") String tenantId);

    @Update("UPDATE t_engineer SET engineer_scope_epoch = engineer_scope_epoch + 1, updated_at = updated_at "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND engineer_scope_epoch < 9223372036854775807")
    int incrementEngineer(@Param("id") long id, @Param("tenantId") String tenantId);

    // ===== 組織部分木（m_organization_unit。数値tenant_idは使わない） =====

    @Select("SELECT org_scope_epoch AS epoch, deleted_flag AS deletedFlag FROM m_organization_unit "
            + "WHERE id = #{id} FOR SHARE")
    EpochRow selectOrganizationForShare(@Param("id") long id);

    @Select("SELECT org_scope_epoch AS epoch, deleted_flag AS deletedFlag FROM m_organization_unit "
            + "WHERE id = #{id} FOR UPDATE")
    EpochRow selectOrganizationForUpdate(@Param("id") long id);

    @Update("UPDATE m_organization_unit SET org_scope_epoch = org_scope_epoch + 1, updated_at = updated_at "
            + "WHERE id = #{id} AND org_scope_epoch < 9223372036854775807")
    int incrementOrganization(@Param("id") long id);

    // ===== 文書（t_document） =====

    @Select("SELECT evidence_eligibility_epoch AS epoch, deleted_flag AS deletedFlag FROM t_document "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR SHARE")
    EpochRow selectDocumentForShare(@Param("id") long id, @Param("tenantId") String tenantId);

    @Select("SELECT evidence_eligibility_epoch AS epoch, deleted_flag AS deletedFlag FROM t_document "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    EpochRow selectDocumentForUpdate(@Param("id") long id, @Param("tenantId") String tenantId);

    @Update("UPDATE t_document SET evidence_eligibility_epoch = evidence_eligibility_epoch + 1, updated_at = updated_at "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND evidence_eligibility_epoch < 9223372036854775807")
    int incrementDocument(@Param("id") long id, @Param("tenantId") String tenantId);

    // ===== タスク世代（t_lifecycle_task。案件X保持下で読み書きする） =====

    /** タスク行はreader・writerとも X で取る（計画 §2.5）。案件のtenant照合はlockしない副問合せで行う。 */
    @Select("SELECT t.task_generation AS epoch, t.deleted_flag AS deletedFlag FROM t_lifecycle_task t "
            + "WHERE t.id = #{taskId} AND t.case_id = #{caseId} "
            + "AND EXISTS (SELECT 1 FROM t_lifecycle_case c WHERE c.id = #{caseId} AND c.tenant_id = #{tenantId}) "
            + "FOR UPDATE")
    EpochRow selectTaskGenerationForUpdate(@Param("taskId") long taskId, @Param("caseId") long caseId,
                                           @Param("tenantId") String tenantId);

    /**
     * タスク世代をlockせずに読む（W3-21a）。二者確認の照会（A1）が提出bodyの {@code taskGeneration} を画面へ渡すためと、
     * coordinatorのtransaction外discoveryのためだけに使う。認可・状態判定の根拠にはしない（attemptは
     * {@link #selectTaskGenerationForUpdate} で読み直す）。案件のtenantで照合する。
     */
    @Select("SELECT t.task_generation AS epoch, t.deleted_flag AS deletedFlag FROM t_lifecycle_task t "
            + "WHERE t.id = #{taskId} AND t.case_id = #{caseId} "
            + "AND EXISTS (SELECT 1 FROM t_lifecycle_case c WHERE c.id = #{caseId} AND c.tenant_id = #{tenantId})")
    EpochRow selectTaskGeneration(@Param("taskId") long taskId, @Param("caseId") long caseId,
                                  @Param("tenantId") String tenantId);

    @Update("UPDATE t_lifecycle_task SET task_generation = task_generation + 1, updated_at = updated_at "
            + "WHERE id = #{taskId} AND case_id = #{caseId} AND task_generation < 9223372036854775807 "
            + "AND EXISTS (SELECT 1 FROM t_lifecycle_case c WHERE c.id = #{caseId} AND c.tenant_id = #{tenantId})")
    int incrementTaskGeneration(@Param("taskId") long taskId, @Param("caseId") long caseId,
                                @Param("tenantId") String tenantId);

    /** epoch読取の結果行（epoch値と論理削除フラグ）。行が無い場合はMapperが {@code null} を返す。 */
    final class EpochRow {
        private Long epoch;
        private Integer deletedFlag;

        public EpochRow() {
        }

        public EpochRow(Long epoch, Integer deletedFlag) {
            this.epoch = epoch;
            this.deletedFlag = deletedFlag;
        }

        public Long getEpoch() {
            return epoch;
        }

        public void setEpoch(Long epoch) {
            this.epoch = epoch;
        }

        public Integer getDeletedFlag() {
            return deletedFlag;
        }

        public void setDeletedFlag(Integer deletedFlag) {
            this.deletedFlag = deletedFlag;
        }

        /** 行が論理削除されておらず、epochが0以上で読めたか。 */
        public boolean usable() {
            return epoch != null && epoch >= 0 && (deletedFlag == null || deletedFlag == 0);
        }
    }
}
