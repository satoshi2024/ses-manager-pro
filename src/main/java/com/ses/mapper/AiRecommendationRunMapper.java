package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiRecommendationRun;
import com.ses.service.ai.copilot.dispatch.DispatchColumns;
import com.ses.service.ai.copilot.dispatch.DispatchRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiRecommendationRunMapper extends BaseMapper<AiRecommendationRun> {

    /** NF08 B2c-1: summary dispatch attempt行の特定条件（tenant・trace・use caseを常に含める）。 */
    String DISPATCH_ROW_IDENTITY = "id = #{c.runId} AND tenant_id = #{c.tenantId} AND trace_id = #{c.traceId} "
            + "AND use_case = #{c.useCase} AND deleted_flag = 0 ";

    /**
     * NF08 B2c-1: 発行時bindingの全列の完全一致。claim CASのWHERE条件に使い、aggregate hashだけで代替しない。
     * claim IDは小文字36文字表記を {@code UUID_TO_BIN(?, 0)} で変換して比べる（計画 D-2）。
     */
    String DISPATCH_BINDING_FENCE = "dispatch_claim_id = UUID_TO_BIN(#{c.claimId}, 0) "
            + "AND dispatch_nonce_hash = #{c.nonceHash} AND dispatch_idempotency_hash = #{c.idempotencyHash} "
            + "AND dispatch_binding_hash = #{c.bindingHash} AND preflight_snapshot_hash = #{c.preflightSnapshotHash} "
            + "AND dispatch_request_sha256 = #{c.requestSha256} "
            + "AND dispatch_issued_at = #{c.issuedAt} AND dispatch_expires_at = #{c.expiresAt} "
            + "AND dispatch_provider = #{c.provider} AND dispatch_model_revision = #{c.modelRevision} "
            + "AND dispatch_region = #{c.region} AND dispatch_tier = #{c.tier} "
            + "AND dispatch_config_hash = #{c.configHash} AND dispatch_artifact_version_id = #{c.artifactVersionId} "
            + "AND dispatch_scope_hash = #{c.scopeHash} AND dispatch_input_hash = #{c.inputHash} "
            + "AND dispatch_approval_digest = #{c.approvalDigest} "
            + "AND dispatch_card_id = #{c.cardId} AND dispatch_card_version = #{c.cardVersion} "
            + "AND dispatch_card_digest = #{c.cardDigest} "
            + "AND dispatch_policy_id = #{c.policyId} AND dispatch_policy_version = #{c.policyVersion} "
            + "AND dispatch_policy_digest = #{c.policyDigest} ";

    /** NF08 B2c-1: claim照合で読む列。claim IDは {@code BIN_TO_UUID(?, 0)} で36文字表記へ戻す。 */
    String DISPATCH_ROW_COLUMNS = "id, tenant_id, trace_id, use_case, status, status_version, dispatch_state, "
            + "BIN_TO_UUID(dispatch_claim_id, 0) AS dispatch_claim_id_text, dispatch_nonce_hash, "
            + "dispatch_idempotency_hash, dispatch_binding_hash, preflight_snapshot_hash, dispatch_request_sha256, "
            + "dispatch_issued_at, dispatch_expires_at, dispatch_claimed_at, dispatch_started_at, dispatch_provider, "
            + "dispatch_model_revision, dispatch_region, dispatch_tier, dispatch_config_hash, "
            + "dispatch_artifact_version_id, dispatch_scope_hash, dispatch_input_hash, dispatch_approval_digest, "
            + "dispatch_card_id, dispatch_card_version, dispatch_card_digest, dispatch_policy_id, "
            + "dispatch_policy_version, dispatch_policy_digest ";

    @Select("SELECT * FROM t_ai_recommendation_run WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0 LIMIT 1")
    AiRecommendationRun selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** tenant_idは必須。NULL分岐による全tenant掃除は禁止。 */
    @Update("UPDATE t_ai_recommendation_run SET redacted_summary_json = NULL, updated_at = #{now} "
            + "WHERE id IN (SELECT id FROM (SELECT id FROM t_ai_recommendation_run "
            + "WHERE created_at < #{cutoff} AND redacted_summary_json IS NOT NULL AND deleted_flag = 0 "
            + "AND tenant_id = #{tenantId} "
            + "ORDER BY created_at, id LIMIT #{limit}) candidates)")
    int purgeExpiredSummaries(@Param("tenantId") String tenantId,
                              @Param("cutoff") LocalDateTime cutoff,
                              @Param("now") LocalDateTime now,
                              @Param("limit") int limit);

    default int purgeExpiredSummaries(String tenantId, LocalDateTime cutoff, LocalDateTime now) {
        return purgeExpiredSummaries(tenantId, cutoff, now, 1000);
    }

    /**
     * NF08 B2c-1: 未完了のSUMMARY attempt（PENDING/v0/NOT_APPLICABLE、dispatch列が未設定）を一度だけREADYにし、
     * 発行時bindingの全列を固定する。対象行が条件に合わなければ0件（呼出側は失敗させる）。
     */
    @Update("UPDATE t_ai_recommendation_run SET dispatch_state = 'READY', "
            + "dispatch_claim_id = UUID_TO_BIN(#{c.claimId}, 0), dispatch_nonce_hash = #{c.nonceHash}, "
            + "dispatch_idempotency_hash = #{c.idempotencyHash}, dispatch_binding_hash = #{c.bindingHash}, "
            + "preflight_snapshot_hash = #{c.preflightSnapshotHash}, dispatch_request_sha256 = #{c.requestSha256}, "
            + "dispatch_issued_at = #{c.issuedAt}, dispatch_expires_at = #{c.expiresAt}, "
            + "dispatch_provider = #{c.provider}, dispatch_model_revision = #{c.modelRevision}, "
            + "dispatch_region = #{c.region}, dispatch_tier = #{c.tier}, dispatch_config_hash = #{c.configHash}, "
            + "dispatch_artifact_version_id = #{c.artifactVersionId}, dispatch_scope_hash = #{c.scopeHash}, "
            + "dispatch_input_hash = #{c.inputHash}, dispatch_approval_digest = #{c.approvalDigest}, "
            + "dispatch_card_id = #{c.cardId}, dispatch_card_version = #{c.cardVersion}, "
            + "dispatch_card_digest = #{c.cardDigest}, dispatch_policy_id = #{c.policyId}, "
            + "dispatch_policy_version = #{c.policyVersion}, dispatch_policy_digest = #{c.policyDigest} "
            + "WHERE " + DISPATCH_ROW_IDENTITY
            + "AND status = 'PENDING' AND status_version = 0 AND dispatch_state = 'NOT_APPLICABLE' "
            + "AND dispatch_claim_id IS NULL AND dispatch_issued_at IS NULL")
    int markDispatchReady(@Param("c") DispatchColumns columns);

    /** NF08 B2c-1: claim transactionの最初に対象行を排他lockして読む（lock順の先頭）。 */
    @Select("SELECT " + DISPATCH_ROW_COLUMNS + "FROM t_ai_recommendation_run WHERE " + DISPATCH_ROW_IDENTITY
            + "FOR UPDATE")
    DispatchRunRow lockDispatchRow(@Param("c") DispatchColumns columns);

    /** NF08 B2c-1: commit結果が不明な場合などに、別transactionで状態を読み直す（lockしない）。 */
    @Select("SELECT " + DISPATCH_ROW_COLUMNS + "FROM t_ai_recommendation_run WHERE " + DISPATCH_ROW_IDENTITY)
    DispatchRunRow readDispatchRow(@Param("c") DispatchColumns columns);

    /**
     * NF08 B2c-1: {@code READY → CLAIMED} の完全一致CAS。tenant/id/trace/use_case/status/status_version/dispatch_stateと
     * 発行時bindingの全列を条件に含め、1件だけ更新できた場合だけclaim成立とする。
     */
    @Update("UPDATE t_ai_recommendation_run SET dispatch_state = 'CLAIMED', dispatch_claimed_at = #{claimedAt} "
            + "WHERE " + DISPATCH_ROW_IDENTITY
            + "AND status = 'PENDING' AND status_version = 0 AND dispatch_state = 'READY' "
            + "AND dispatch_claimed_at IS NULL AND dispatch_started_at IS NULL AND " + DISPATCH_BINDING_FENCE)
    int claimDispatch(@Param("c") DispatchColumns columns, @Param("claimedAt") LocalDateTime claimedAt);
}
