package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureEnablementRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 機能の開放の申請（V203）。未決から終了への遷移は version の CAS で、列を名指しした {@code UpdateWrapper} で行う。
 * 同じ対象の未決は1件だけ（生成列 {@code pending_slot} と一意索引）。
 */
@Mapper
public interface FeatureEnablementRequestMapper extends BaseMapper<FeatureEnablementRequest> {

    /** 申請を X lock して読む（design §5.1 の (3)）。 */
    @Select("SELECT * FROM t_feature_enablement_request WHERE id = #{id} FOR UPDATE")
    FeatureEnablementRequest selectByIdForUpdate(@Param("id") Long id);

    /** 対象の未決の申請（無ければ null）。 */
    @Select("SELECT * FROM t_feature_enablement_request WHERE tenant_id = #{tenantId} AND target_key = #{targetKey} "
            + "AND status = 'PENDING'")
    FeatureEnablementRequest selectPendingByTarget(@Param("tenantId") String tenantId,
                                                   @Param("targetKey") String targetKey);

    /** tenant の未決の申請（対象の昇順）。 */
    @Select("SELECT * FROM t_feature_enablement_request WHERE tenant_id = #{tenantId} AND status = 'PENDING' "
            + "ORDER BY target_key, id")
    List<FeatureEnablementRequest> selectPending(@Param("tenantId") String tenantId);

    /** 期限を過ぎた未決の申請（{@code expires_at <= now}）。 */
    @Select("SELECT * FROM t_feature_enablement_request WHERE tenant_id = #{tenantId} AND status = 'PENDING' "
            + "AND expires_at <= #{now} ORDER BY id")
    List<FeatureEnablementRequest> selectExpiredPending(@Param("tenantId") String tenantId,
                                                        @Param("now") LocalDateTime now);
}
