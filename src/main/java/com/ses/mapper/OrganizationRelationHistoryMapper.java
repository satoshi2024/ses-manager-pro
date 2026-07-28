package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.OrganizationRelationHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/** 組織の親子・状態履歴Mapper。 */
@Mapper
public interface OrganizationRelationHistoryMapper extends BaseMapper<OrganizationRelationHistory> {

    /**
     * 指定日時点で有効な全組織の親子・状態を返す。
     *
     * <p>履歴が1件も無い組織（V61適用前に作られ、backfillも走っていない異常系）は
     * 現在値へフォールバックする。ここで組織ごと落とすと過去月の部門損益が丸ごと消えるため。
     */
    @Select("""
        SELECT o.id AS organizationId,
               COALESCE(h.parent_id, o.parent_id) AS parentId,
               COALESCE(h.status, o.status) AS status
        FROM m_organization_unit o
        LEFT JOIN t_organization_relation_history h
               ON h.organization_id = o.id
              AND h.deleted_flag = 0
              AND h.valid_from <= #{asOf}
              AND (h.valid_to IS NULL OR h.valid_to >= #{asOf})
        WHERE o.deleted_flag = 0
          AND o.valid_from <= #{asOf}
          AND (o.valid_to IS NULL OR o.valid_to >= #{asOf})
        """)
    List<OrganizationRelationHistory> selectAsOf(@Param("asOf") LocalDate asOf);

    /** 現行版（valid_to IS NULL）を指定日の前日で締める。 */
    @Update("UPDATE t_organization_relation_history SET valid_to = #{validTo} "
            + "WHERE organization_id = #{organizationId} AND valid_to IS NULL AND deleted_flag = 0")
    int closeCurrent(@Param("organizationId") Long organizationId, @Param("validTo") LocalDate validTo);

    /** 現行版を1件返す。無ければnull。 */
    @Select("SELECT * FROM t_organization_relation_history WHERE organization_id = #{organizationId} "
            + "AND valid_to IS NULL AND deleted_flag = 0 ORDER BY id DESC LIMIT 1")
    OrganizationRelationHistory selectCurrent(@Param("organizationId") Long organizationId);
}
