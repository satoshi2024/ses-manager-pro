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
     *
     * <p><b>{@code COALESCE} ではなく {@code CASE WHEN h.id IS NULL}</b> で判定する。
     * 履歴行が見つかった（{@code h.id}が非NULL）のに、その版の{@code parent_id}が
     * 「当時は正しくルート組織だった」ことを示す明示的なNULLである場合、{@code COALESCE}は
     * 現在の{@code parent_id}へフォールバックしてしまい、統合前の日付を照会しても
     * 統合後の親を返す（第十三次Review P1-1）。履歴の有無で分岐すれば、
     * 見つかった版のNULLはそのままNULL（ルート）として扱われる。
     */
    @Select("""
        SELECT o.id AS organizationId,
               CASE WHEN h.id IS NULL THEN o.parent_id ELSE h.parent_id END AS parentId,
               CASE WHEN h.id IS NULL THEN o.status ELSE h.status END AS status
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
