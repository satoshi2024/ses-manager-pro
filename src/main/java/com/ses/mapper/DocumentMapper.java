package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 文書台帳Mapper。
 * 認可母集団はt_document_linkからの導出を基本とし、business layerで制御する。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 保存期限切れ候補を取得する。
     * retention_until IS NULL の文書は候補から除外する（design §6.1）。
     * legal_hold_flag = 1 の文書も除外する（R4.2）。
     */
    @Select("SELECT * FROM t_document WHERE deleted_flag = 0 AND status = 'CONFIRMED' AND retention_until IS NOT NULL AND retention_until <= #{today} AND legal_hold_flag = 0 ORDER BY retention_until ASC")
    List<Document> findDisposalCandidates(@Param("today") LocalDate today);

    /**
     * 同一原本hash（SHA-256）で既に登録済みの文書を返す（order-acceptance-workflow R2.4）。
     * 注文書原本の二重登録拒否に使用する。
     */
    @Select("""
        SELECT v.document_id
        FROM t_document_version v
        JOIN t_document d ON d.id = v.document_id AND d.deleted_flag = 0
        WHERE v.sha256 = #{sha256}
          AND d.tenant_id = #{tenantId}
          AND d.document_type = #{documentType}
          AND v.deleted_flag = 0
        LIMIT 1
        """)
    Long findDocumentIdBySha256AndType(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                                       @org.apache.ibatis.annotations.Param("sha256") String sha256,
                                       @org.apache.ibatis.annotations.Param("documentType") String documentType);

    /**
     * 証跡適格性の判定用に文書行を読む（NF01 計画 §5.1 条件3〜6、{@code DocumentEvidenceEligibility} 専用）。
     *
     * <p>論理削除済みの行も返し（削除を「不在」ではなく「利用不可」として区別するため）、判定は呼出側が行う。
     * tenantは引数で明示し、tenant contextに依存しない。判定の最初に読む文で、実行前にMyBatisのsession cacheを
     * 消す（同じtransactionで先に読んだlink・廃棄申請・版の結果を判定に使い回さない）。</p>
     */
    @Select("SELECT id, tenant_id, document_type, status, legal_hold_flag, deleted_flag FROM t_document "
            + "WHERE id = #{id} AND tenant_id = #{tenantId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Document selectEvidenceCandidate(@Param("id") long id, @Param("tenantId") String tenantId);
}
