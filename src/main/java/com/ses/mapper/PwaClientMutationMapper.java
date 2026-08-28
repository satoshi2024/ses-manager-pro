package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PwaClientMutation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 要員PWA専用client mutation ledger mapper。 */
@Mapper
public interface PwaClientMutationMapper extends BaseMapper<PwaClientMutation> {

    @Delete("DELETE FROM t_pwa_client_mutation WHERE created_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Select("SELECT * FROM t_pwa_client_mutation WHERE user_id = #{userId} "
            + "AND client_request_id = #{clientRequestId} LIMIT 1")
    PwaClientMutation selectByUserAndClientRequest(@Param("userId") Long userId,
                                                    @Param("clientRequestId") String clientRequestId);
}
