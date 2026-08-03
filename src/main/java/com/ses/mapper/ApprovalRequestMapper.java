package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {

    /** 最終承認transactionの順序（request行ロック→target version再検証→…）を守るための行ロック取得。 */
    @Select("SELECT * FROM t_approval_request WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    ApprovalRequest selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_approval_request WHERE idempotency_key = #{idempotencyKey}"
            + " AND deleted_flag = 0"
            + " AND status NOT IN ('approved','rejected','withdrawn','conflict')")
    ApprovalRequest selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * participant/delegation typeを可視性のSQL境界へ置いた承認一覧。
     * Java側で全件取得してからfilter/subListしてはならない。
     */
    @Select("""
            <script>
            SELECT r.*
            FROM t_approval_request r
            WHERE r.deleted_flag = 0
            <if test="admin == false">
              AND (
                EXISTS (
                  SELECT 1
                  FROM t_approval_participant p
                  WHERE p.request_id = r.id
                    AND p.round_no = r.round_no
                    AND p.user_id = #{userId}
                )
                OR EXISTS (
                  SELECT 1
                  FROM t_approval_participant p
                  JOIN t_approval_delegation d ON d.from_user_id = p.user_id
                  WHERE p.request_id = r.id
                    AND p.round_no = r.round_no
                    AND p.participant_role = 'approver'
                    AND d.to_user_id = #{userId}
                    AND d.deleted_flag = 0
                    AND d.valid_from &lt;= #{today}
                    AND (d.valid_to IS NULL OR d.valid_to &gt;= #{today})
                    AND (
                      NOT EXISTS (
                        SELECT 1
                        FROM t_approval_delegation_type dt_any
                        WHERE dt_any.delegation_id = d.id
                      )
                      OR EXISTS (
                        SELECT 1
                        FROM t_approval_delegation_type dt
                        WHERE dt.delegation_id = d.id
                          AND dt.request_type = r.request_type
                      )
                    )
                )
              )
            </if>
            <choose>
              <when test="status != null and status != ''">
                AND r.status = #{status}
              </when>
              <when test="view == 'completed'">
                AND r.status IN ('approved', 'rejected', 'withdrawn', 'conflict')
              </when>
              <when test="view == 'inbox'">
                AND r.status IN ('requested', 'in_review')
              </when>
            </choose>
            <if test="view == 'mine'">
              AND r.applicant_id = #{userId}
            </if>
            ORDER BY r.requested_at DESC, r.id DESC
            </script>
            """)
    Page<ApprovalRequest> selectVisiblePage(Page<ApprovalRequest> page,
                                             @Param("userId") Long userId,
                                             @Param("admin") boolean admin,
                                             @Param("today") LocalDate today,
                                             @Param("view") String view,
                                             @Param("status") String status);
}
