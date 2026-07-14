package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.engineersales.SalesUserAssignCountDto;
import com.ses.entity.EngineerSales;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 要員担当営業マッパー
 * sys_user との join が必要な参照系のみ @Select を使用する（現任 = released_at IS NULL）
 */
@Mapper
public interface EngineerSalesMapper extends BaseMapper<EngineerSales> {

    /** 要員の現任担当営業一覧（営業名付き、主担当が先頭） */
    @Select("""
        SELECT es.id, es.engineer_id AS engineerId, es.sales_user_id AS salesUserId,
               su.real_name AS salesUserName, es.primary_flag AS primaryFlag,
               es.assigned_at AS assignedAt, es.released_at AS releasedAt, es.remarks
        FROM t_engineer_sales es
        LEFT JOIN sys_user su ON es.sales_user_id = su.id
        WHERE es.deleted_flag = 0 AND es.engineer_id = #{engineerId} AND es.released_at IS NULL
        ORDER BY es.primary_flag DESC, es.assigned_at, es.id
        """)
    List<EngineerSalesDto> selectActiveWithNames(@Param("engineerId") Long engineerId);

    /** 要員の担当営業履歴（解除済み含む、開始日降順） */
    @Select("""
        SELECT es.id, es.engineer_id AS engineerId, es.sales_user_id AS salesUserId,
               su.real_name AS salesUserName, es.primary_flag AS primaryFlag,
               es.assigned_at AS assignedAt, es.released_at AS releasedAt, es.remarks
        FROM t_engineer_sales es
        LEFT JOIN sys_user su ON es.sales_user_id = su.id
        WHERE es.deleted_flag = 0 AND es.engineer_id = #{engineerId}
        ORDER BY es.released_at IS NULL DESC, es.assigned_at DESC, es.id DESC
        """)
    List<EngineerSalesDto> selectHistoryWithNames(@Param("engineerId") Long engineerId);

    /** 複数要員の現任主担当営業を一括取得（一覧画面用） */
    @Select("""
        <script>
        SELECT es.engineer_id AS engineerId, es.sales_user_id AS salesUserId,
               su.real_name AS salesUserName
        FROM t_engineer_sales es
        LEFT JOIN sys_user su ON es.sales_user_id = su.id
        WHERE es.deleted_flag = 0 AND es.released_at IS NULL AND es.primary_flag = 1
          AND es.engineer_id IN
          <foreach collection="engineerIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
        </script>
        """)
    List<EngineerPrimarySalesDto> selectActivePrimaryByEngineerIds(@Param("engineerIds") List<Long> engineerIds);

    /** 営業ユーザー別の現任主担当要員数（削除済み要員は除外） */
    @Select("""
        SELECT es.sales_user_id AS salesUserId, COUNT(*) AS engineerCount
        FROM t_engineer_sales es
        JOIN t_engineer e ON es.engineer_id = e.id AND e.deleted_flag = 0
        WHERE es.deleted_flag = 0 AND es.released_at IS NULL AND es.primary_flag = 1
        GROUP BY es.sales_user_id
        """)
    List<SalesUserAssignCountDto> countActivePrimaryGroupBySalesUser();
}
