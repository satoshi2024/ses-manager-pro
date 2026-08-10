package com.ses.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 勤怠の法人scopeをSQL境界で解決するMapper。画面取得後のfilterへ逃がさない。 */
@Mapper
public interface AttendanceScopeMapper {

    /** HR担当者の対象日時点の有効組織から、既知の法人だけを返す。 */
    @Select("""
            SELECT DISTINCT ou.legal_entity_id
            FROM t_user_organization uo
            JOIN m_organization_unit ou ON ou.id = uo.organization_id
                 AND ou.deleted_flag = 0
                 AND ou.valid_from <= #{asOf}
                 AND (ou.valid_to IS NULL OR ou.valid_to >= #{asOf})
            WHERE uo.user_id = #{userId}
              AND uo.deleted_flag = 0
              AND uo.valid_from <= #{asOf}
              AND (uo.valid_to IS NULL OR uo.valid_to >= #{asOf})
              AND ou.legal_entity_id IS NOT NULL
            ORDER BY ou.legal_entity_id
            """)
    List<Long> selectLegalEntityIdsByUser(@Param("userId") Long userId,
                                          @Param("asOf") LocalDate asOf);

    /** 対象月末の所属履歴を使い、法人scopeに入る要員IDを返す。UNKNOWNはfail-closedで除外する。 */
    @Select("""
            <script>
            SELECT DISTINCT e.id
            FROM t_engineer e
            LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
            LEFT JOIN t_engineer_accounting_history eh ON eh.engineer_id = e.id
                 AND eh.deleted_flag = 0
                 AND eh.valid_from &lt;= #{asOf}
                 AND (eh.valid_to IS NULL OR eh.valid_to &gt;= #{asOf})
            LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                 AND uo.primary_flag = 1
                 AND uo.deleted_flag = 0
                 AND uo.valid_from &lt;= #{asOf}
                 AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
            JOIN m_organization_unit ou ON ou.id = CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE eh.organization_id
               END
                 AND ou.deleted_flag = 0
                 AND ou.valid_from &lt;= #{asOf}
                 AND (ou.valid_to IS NULL OR ou.valid_to &gt;= #{asOf})
            WHERE e.deleted_flag = 0
              AND ou.legal_entity_id IN
                <foreach collection="legalEntityIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectEngineerIdsByLegalEntityIds(@Param("legalEntityIds") List<Long> legalEntityIds,
                                                 @Param("asOf") LocalDate asOf);

    /** 全法人IDの列挙（管理者のpullで全法人のcursorを対象にするため）。 */
    @Select("""
            SELECT DISTINCT legal_entity_id
            FROM m_organization_unit
            WHERE legal_entity_id IS NOT NULL
              AND deleted_flag = 0
            ORDER BY legal_entity_id
            """)
    List<Long> selectAllLegalEntityIds();
}
