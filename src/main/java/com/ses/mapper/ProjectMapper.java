package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Project;
import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.project.ProjectListDto;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

/**
 * 案件マッパー
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
    
    @Select("""
        <script>
        SELECT
            p.*,
            c.company_name AS customer_name
        FROM t_project p
        LEFT JOIN m_customer c ON p.customer_id = c.id
        WHERE p.deleted_flag = 0
        <if test="projectName != null and projectName != ''">
            AND p.project_name LIKE CONCAT('%', #{projectName}, '%')
        </if>
        <if test="status != null and status != ''">
            AND p.status = #{status}
        </if>
        <if test="customerId != null">
            AND p.customer_id = #{customerId}
        </if>
        <if test="customerName != null and customerName != ''">
            AND c.company_name LIKE CONCAT('%', #{customerName}, '%')
        </if>
        <if test="allowedIds != null">
            <choose>
                <when test="allowedIds.isEmpty()">
                    AND 1 = 0
                </when>
                <otherwise>
                    AND p.customer_id IN
                    <foreach item="item" collection="allowedIds" open="(" separator="," close=")">
                        #{item}
                    </foreach>
                </otherwise>
            </choose>
        </if>
        ORDER BY p.id DESC
        </script>
    """)
    Page<ProjectListDto> selectPageWithNames(Page<ProjectListDto> page,
                                             @Param("projectName") String projectName,
                                             @Param("status") String status,
                                             @Param("customerId") Long customerId,
                                             @Param("customerName") String customerName,
                                             @Param("allowedIds") Collection<Long> allowedIds);
}
