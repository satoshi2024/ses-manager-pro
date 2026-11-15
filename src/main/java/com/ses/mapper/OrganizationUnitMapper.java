package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.OrganizationUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/** 組織マスタMapper。 */
@Mapper
public interface OrganizationUnitMapper extends BaseMapper<OrganizationUnit> {

    @Select("SELECT * FROM m_organization_unit WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    OrganizationUnit selectByIdForUpdate(Long id);

    @Select("SELECT * FROM m_organization_unit WHERE id = #{id} AND deleted_flag = 0 "
            + "AND valid_from <= #{asOf} AND (valid_to IS NULL OR valid_to >= #{asOf}) LIMIT 1")
    OrganizationUnit selectAt(@Param("id") Long id, @Param("asOf") LocalDate asOf);

    /**
     * NF01 lock rank 6 の組織行lock（細粒度epoch計画 §3.1・§3.5 規則1）。外部キー検査が参照先の組織行に付ける共有lockを、
     * 要員行（rank 7）のX lockより前に取っておくためのもの（W3-16a後半 review P3-1）。外部キー検査と同じく論理削除では
     * 絞らず、IDだけを返す（行が無ければ {@code null}）。{@code exclusive=false} は {@code FOR SHARE} で、H2は解釈しないため
     * 呼出しは {@code OrganizationShareLock} だけにする（H2では {@code exclusive=true} で代える）。
     */
    @Select("<script>SELECT id FROM m_organization_unit WHERE id = #{id} "
            + "<choose><when test='exclusive'>FOR UPDATE</when><otherwise>FOR SHARE</otherwise></choose></script>")
    Long lockReferencedRow(@Param("id") long id, @Param("exclusive") boolean exclusive);
}
