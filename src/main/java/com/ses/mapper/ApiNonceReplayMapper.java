package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiNonceReplay;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** NF-05 nonce replay ledger mapper。 */
@Mapper
public interface ApiNonceReplayMapper extends BaseMapper<ApiNonceReplay> {
    @Delete("DELETE FROM t_api_nonce_replay WHERE id IN "
            + "(SELECT id FROM (SELECT id FROM t_api_nonce_replay WHERE expires_at <= #{now} "
            + "ORDER BY id LIMIT #{limit}) expired_nonce)")
    int deleteExpiredBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
