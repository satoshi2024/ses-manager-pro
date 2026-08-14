package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ContractDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 契約書ドキュメントMapper。
 * 配送工程の遷移は (id, version, dispatch_state) のCAS（条件付きUPDATE）でのみ行う。
 * MyBatis-Plusの自動SQLは論理削除条件を含むが、手書き@Updateは自前で deleted_flag を付ける。
 */
@Mapper
public interface ContractDocumentMapper extends BaseMapper<ContractDocument> {

    /**
     * 状態CAS: 期待する(dispatch_state, version)のときだけ新状態へ遷移する。
     *
     * @return 更新行数（0=競合/状態不一致。握り潰さず呼び出し元へ返す）
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, version = version + 1, "
            + "updated_at = NOW() WHERE id = #{id} AND deleted_flag = 0 "
            + "AND dispatch_state = #{from} AND version = #{expectedVersion}")
    int casTransition(@Param("id") Long id,
                      @Param("expectedVersion") int expectedVersion,
                      @Param("from") String from,
                      @Param("to") String to);

    /**
     * worker claim CAS: 未claimかつnext_attempt_at経過済みのQUEUED行だけをclaimする。
     * 同時workerは1件しかclaimできず、claim済み行は他workerから見えない（単一writer保証）。
     *
     * @return 更新行数（0=他workerがclaim済み/期限前）
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, "
            + "dispatch_attempt_count = dispatch_attempt_count + 1, "
            + "claimed_at = #{claimedAt}, claim_owner = #{owner}, next_attempt_at = NULL, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion} "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})")
    int casClaim(@Param("id") Long id,
                 @Param("expectedVersion") int expectedVersion,
                 @Param("from") String from,
                 @Param("to") String to,
                 @Param("claimedAt") LocalDateTime claimedAt,
                 @Param("owner") String owner,
                 @Param("now") LocalDateTime now);

    /**
     * 工程checkpoint CAS: provider呼出し成功後に document/file/participant ID を保存しつつ
     * 次工程へ進める。versionと状態の両方を条件にし、commit順反転やstale workerを排除する。
     *
     * @return 更新行数（0=競合）
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, "
            + "cloudsign_document_id = COALESCE(#{documentId}, cloudsign_document_id), "
            + "cloudsign_file_id = COALESCE(#{fileId}, cloudsign_file_id), "
            + "cloudsign_participant_id = COALESCE(#{participantId}, cloudsign_participant_id), "
            + "cloudsign_status = #{status}, claimed_at = NULL, claim_owner = NULL, "
            + "last_provider_error_code = NULL, version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casCheckpoint(@Param("id") Long id,
                      @Param("expectedVersion") int expectedVersion,
                      @Param("from") String from,
                      @Param("to") String to,
                      @Param("documentId") String documentId,
                      @Param("fileId") String fileId,
                      @Param("participantId") String participantId,
                      @Param("status") Integer status);

    /**
     * 送信queue受付CAS: NONE→QUEUED と同時に operation ID / payload hash を永続化する。
     * 二重クリック・並列request・worker再実行を同じoperationとして扱う（HFP-02-AC-04-01）。
     *
     * @return 更新行数（0=他requestが先にqueue済み/状態不一致）
     */
    @Update("UPDATE t_contract_document SET dispatch_state = 'QUEUED', "
            + "operation_id = #{operationId}, send_payload_sha256 = #{payloadHash}, "
            + "last_provider_error_code = NULL, claimed_at = NULL, claim_owner = NULL, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = 'NONE' "
            + "AND version = #{expectedVersion}")
    int casQueue(@Param("id") Long id,
                 @Param("expectedVersion") int expectedVersion,
                 @Param("operationId") String operationId,
                 @Param("payloadHash") String payloadHash);

    /**
     * 結果不明/恒久エラーへの遷移CAS。error code(PIIなし)を記録し、claimを解放する。
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, "
            + "last_provider_error_code = #{errorCode}, claimed_at = NULL, claim_owner = NULL, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casFail(@Param("id") Long id,
                @Param("expectedVersion") int expectedVersion,
                @Param("from") String from,
                @Param("to") String to,
                @Param("errorCode") String errorCode);

    /**
     * 429等のbounded retry待機CAS: 状態を親工程へ戻し、next_attempt_at を設定する。
     * mutationの再実行は429等「受理されなかった」場合だけ（結果不明は自動再実行しない）。
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, "
            + "last_provider_error_code = #{errorCode}, next_attempt_at = #{nextAttemptAt}, "
            + "claimed_at = NULL, claim_owner = NULL, version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casRetryWait(@Param("id") Long id,
                     @Param("expectedVersion") int expectedVersion,
                     @Param("from") String from,
                     @Param("to") String to,
                     @Param("errorCode") String errorCode,
                     @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt);

    /**
     * provider GETで確定した状態の保存CAS（polling/manual sync/reconciliation共用）。
     * terminalへの逆戻りはfrom状態CASで排除する。
     */
    @Update("UPDATE t_contract_document SET dispatch_state = #{to}, "
            + "cloudsign_status = #{status}, status = #{businessStatus}, "
            + "sent_at = COALESCE(sent_at, #{sentAt}), "
            + "completed_at = CASE WHEN #{to} = 'COMPLETED' THEN COALESCE(completed_at, NOW()) ELSE completed_at END, "
            + "last_synced_at = NOW(), last_provider_error_code = NULL, "
            + "claimed_at = NULL, claim_owner = NULL, version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casStatusSync(@Param("id") Long id,
                      @Param("expectedVersion") int expectedVersion,
                      @Param("from") String from,
                      @Param("to") String to,
                      @Param("status") Integer status,
                      @Param("businessStatus") String businessStatus,
                      @Param("sentAt") java.time.LocalDateTime sentAt);

    /**
     * 安全側の要確認記録CAS: dispatch状態は維持したまま、provider raw status・業務status・
     * finding code を更新する（未知status/逆戻り疑い。自動送信・自動遷移しない）。
     */
    @Update("UPDATE t_contract_document SET cloudsign_status = #{status}, status = #{businessStatus}, "
            + "last_synced_at = NOW(), last_provider_error_code = #{errorCode}, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casStatusFinding(@Param("id") Long id,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("from") String from,
                         @Param("status") Integer status,
                         @Param("businessStatus") String businessStatus,
                         @Param("errorCode") String errorCode);

    /**
     * GET系のbounded backoff CAS: attemptを増やし、next_attempt_at を設定する（状態は維持）。
     */
    @Update("UPDATE t_contract_document SET last_provider_error_code = #{errorCode}, "
            + "next_attempt_at = #{nextAttemptAt}, dispatch_attempt_count = dispatch_attempt_count + 1, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casGetBackoff(@Param("id") Long id,
                      @Param("expectedVersion") int expectedVersion,
                      @Param("from") String from,
                      @Param("errorCode") String errorCode,
                      @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt);

    /**
     * artifact（締結済みPDF/証明書）のarchive IDとhashのCAS保存（HFP-02-AC-07-04/05）。
     * 同一hashの再取得はno-op（呼び出し側が判定）、相違hashは上書きせずfindingのみ。
     */
    @Update("UPDATE t_contract_document SET "
            + "signed_pdf_sha256 = COALESCE(#{signedHash}, signed_pdf_sha256), "
            + "signed_archive_document_id = COALESCE(#{signedArchiveId}, signed_archive_document_id), "
            + "certificate_sha256 = COALESCE(#{certHash}, certificate_sha256), "
            + "certificate_archive_document_id = COALESCE(#{certArchiveId}, certificate_archive_document_id), "
            + "last_provider_error_code = NULL, version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted_flag = 0 AND dispatch_state = #{from} "
            + "AND version = #{expectedVersion}")
    int casArtifactSave(@Param("id") Long id,
                        @Param("expectedVersion") int expectedVersion,
                        @Param("from") String from,
                        @Param("signedHash") String signedHash,
                        @Param("signedArchiveId") Long signedArchiveId,
                        @Param("certHash") String certHash,
                        @Param("certArchiveId") Long certArchiveId);
}
