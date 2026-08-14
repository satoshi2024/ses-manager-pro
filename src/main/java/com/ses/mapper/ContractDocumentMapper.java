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
}
