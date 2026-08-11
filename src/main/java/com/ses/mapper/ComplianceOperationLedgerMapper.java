package com.ses.mapper;

import com.ses.entity.ComplianceOperationLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** operation ledgerはclaim/select/CAS transitionだけを公開し、CRUDを公開しない。 */
@Mapper
public interface ComplianceOperationLedgerMapper {
    @Insert("INSERT INTO t_compliance_operation_ledger "
            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, retryable_flag, "
            + "attempt_count, started_at, lease_until, correlation_id, expires_at, version, deleted_flag) VALUES "
            + "(#{ledger.tenantId}, #{ledger.operationId}, #{ledger.operationType}, #{ledger.idempotencyKey}, "
            + "#{ledger.requestHash}, #{ledger.state}, #{ledger.retryableFlag}, #{ledger.attemptCount}, "
            + "#{ledger.startedAt}, #{ledger.leaseUntil}, #{ledger.correlationId}, #{ledger.expiresAt}, "
            + "#{ledger.version}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "ledger.id")
    int insertClaim(@Param("ledger") ComplianceOperationLedger ledger);

    @Select("SELECT * FROM t_compliance_operation_ledger "
            + "WHERE tenant_id = #{tenantId} AND operation_type = #{operationType} "
            + "AND idempotency_key = #{idempotencyKey} AND deleted_flag = 0")
    ComplianceOperationLedger selectByIdempotencyKey(@Param("tenantId") String tenantId,
                                                      @Param("operationType") String operationType,
                                                      @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM t_compliance_operation_ledger "
            + "WHERE tenant_id = #{tenantId} AND operation_id = #{operationId} AND deleted_flag = 0")
    ComplianceOperationLedger selectByOperationId(@Param("tenantId") String tenantId,
                                                   @Param("operationId") String operationId);

    @Update("UPDATE t_compliance_operation_ledger SET state = 'PROCESSING', attempt_count = attempt_count + 1, "
            + "lease_until = #{leaseUntil}, version = version + 1, updated_at = CURRENT_TIMESTAMP(6) "
            + "WHERE tenant_id = #{tenantId} AND operation_id = #{operationId} AND state = 'PROCESSING' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int renewLeaseCas(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                      @Param("expectedVersion") Integer expectedVersion,
                      @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    @Update("UPDATE t_compliance_operation_ledger SET state = 'SUCCEEDED', retryable_flag = 0, finished_at = #{finishedAt}, "
            + "result_reference_type = #{resultReferenceType}, result_reference_id = #{resultReferenceId}, "
            + "result_reference_version = #{resultReferenceVersion}, result_summary_canonical = #{resultSummaryCanonical}, "
            + "result_http_status = #{resultHttpStatus}, result_hash = #{resultHash}, failure_code = NULL, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP(6) "
            + "WHERE tenant_id = #{tenantId} AND operation_id = #{operationId} AND state = 'PROCESSING' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int completeSuccessCas(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("finishedAt") java.time.LocalDateTime finishedAt,
                           @Param("resultReferenceType") String resultReferenceType,
                           @Param("resultReferenceId") Long resultReferenceId,
                           @Param("resultReferenceVersion") String resultReferenceVersion,
                           @Param("resultSummaryCanonical") String resultSummaryCanonical,
                           @Param("resultHttpStatus") Integer resultHttpStatus,
                           @Param("resultHash") String resultHash);

    @Update("UPDATE t_compliance_operation_ledger SET state = 'FAILED', retryable_flag = #{retryableFlag}, "
            + "finished_at = #{finishedAt}, failure_code = #{failureCode}, "
            + "result_summary_canonical = #{resultSummaryCanonical}, result_http_status = #{resultHttpStatus}, "
            + "result_hash = #{resultHash}, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6) "
            + "WHERE tenant_id = #{tenantId} AND operation_id = #{operationId} AND state = 'PROCESSING' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int completeFailureCas(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("finishedAt") java.time.LocalDateTime finishedAt,
                           @Param("retryableFlag") Integer retryableFlag,
                           @Param("failureCode") String failureCode,
                           @Param("resultSummaryCanonical") String resultSummaryCanonical,
                           @Param("resultHttpStatus") Integer resultHttpStatus,
                           @Param("resultHash") String resultHash);

    @Update("UPDATE t_compliance_operation_ledger SET state = 'PROCESSING', retryable_flag = 1, "
            + "attempt_count = attempt_count + 1, lease_until = #{leaseUntil}, finished_at = NULL, "
            + "result_reference_type = NULL, result_reference_id = NULL, result_reference_version = NULL, "
            + "result_summary_canonical = NULL, result_http_status = NULL, result_hash = NULL, "
            + "failure_code = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP(6) "
            + "WHERE tenant_id = #{tenantId} AND operation_id = #{operationId} AND state = 'FAILED' "
            + "AND retryable_flag = 1 AND version = #{expectedVersion} AND deleted_flag = 0")
    int restartFailedCas(@Param("tenantId") String tenantId, @Param("operationId") String operationId,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("leaseUntil") java.time.LocalDateTime leaseUntil);
}
