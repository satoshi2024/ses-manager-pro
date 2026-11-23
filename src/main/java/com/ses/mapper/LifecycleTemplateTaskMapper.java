package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTemplateTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleTemplateTaskMapper extends BaseMapper<LifecycleTemplateTask> {

    /**
     * 証跡種別が許可値一覧（{@code LifecycleTemplateServiceImpl.EVIDENCE_TYPES}）の外にある行の条件（別名 {@code tt}。FS 4.9.5 の
     * (1)(2)、feature-enablement-console design §8.2。FE-B01）。
     *
     * <p>照合順序（{@code utf8mb4_unicode_ci}）は大文字小文字・全角英字・一部の空白を同一視するため、MySQL の
     * {@code CAST(... AS BINARY)} と同じくバイト列で比べる。{@code BINARY(128)}（{@code VARCHAR(30)} の utf8mb4 は最大 120 byte）は
     * 短い値を 0x00 で埋めるため、末尾に区切りの {@code '|'} を付けてから比べる（末尾の NUL を見落とさない）。同じ SQL が H2 でも
     * 動く（{@code HEX} は H2 に無い）。許可値を変える時は {@code EVIDENCE_TYPES} とここを同じ change で直す
     * （{@code LifecycleDualConfirmationEnablementConditionsTest} が全ての許可値で0件になることを確かめる）。</p>
     */
    String EVIDENCE_TYPE_OUTSIDE_ALLOWED = "(tt.evidence_type IS NULL OR CAST(CONCAT(tt.evidence_type, '|') AS BINARY(128)) "
            + "NOT IN (CAST('NONE|' AS BINARY(128)), CAST('COMMENT|' AS BINARY(128)), "
            + "CAST('SELF_DECLARATION|' AS BINARY(128)), CAST('DOCUMENT_LINK|' AS BINARY(128)), "
            + "CAST('SYSTEM_CHECK|' AS BINARY(128)), CAST('DUAL_CONFIRMATION|' AS BINARY(128))))";

    /** 証跡種別がバイト列で {@code DUAL_CONFIRMATION} と一致する行の条件（別名 {@code tt}。FS 4.9.5 の (3)）。 */
    String EVIDENCE_TYPE_DUAL = "CAST(CONCAT(tt.evidence_type, '|') AS BINARY(128)) = CAST('DUAL_CONFIRMATION|' AS BINARY(128))";

    @Select("SELECT * FROM m_lifecycle_template_task " +
            "WHERE template_id = #{templateId} AND deleted_flag = 0 " +
            "ORDER BY sort_order ASC, id ASC")
    List<LifecycleTemplateTask> selectByTemplateId(@Param("templateId") Long templateId);

    /** テンプレートのタスク定義で、証跡種別が許可値一覧の外にある行の件数（FS 4.9.5 の (1)。論理削除を除く）。 */
    @Select("SELECT COUNT(*) FROM m_lifecycle_template_task tt JOIN m_lifecycle_template t ON t.id = tt.template_id "
            + "WHERE tt.deleted_flag = 0 AND t.deleted_flag = 0 AND " + EVIDENCE_TYPE_OUTSIDE_ALLOWED)
    long countEvidenceTypesOutsideAllowed();

    /** 二者確認を含む有効なテンプレートの件数（FS 4.9.5 の (3)。NF01 二者確認の閉鎖の警告）。 */
    @Select("SELECT COUNT(*) FROM m_lifecycle_template t WHERE t.deleted_flag = 0 AND t.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM m_lifecycle_template_task tt WHERE tt.template_id = t.id AND tt.deleted_flag = 0 "
            + "AND " + EVIDENCE_TYPE_DUAL + ")")
    long countActiveTemplatesWithDualConfirmation();
}
