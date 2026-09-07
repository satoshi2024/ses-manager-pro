package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceRequestSequence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * サービスリクエスト月次採番シーケンスマッパー
 */
@Mapper
public interface ServiceRequestSequenceMapper extends BaseMapper<ServiceRequestSequence> {

    /**
     * 当該月のシーケンス初期レコードが存在しなければ 0 で挿入する（存在すれば何もしない）。
     */
    @Insert("INSERT INTO t_service_request_sequence (sequence_month, current_val, created_at, updated_at) "
            + "VALUES (#{month}, 0, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE sequence_month = sequence_month")
    int insertInitialIfAbsent(@Param("month") String month);

    /**
     * 当該月のシーケンス現在値を行ロック付き（FOR UPDATE）で取得する。
     */
    @Select("SELECT current_val FROM t_service_request_sequence WHERE sequence_month = #{month} FOR UPDATE")
    Integer selectCurrentValForUpdate(@Param("month") String month);

    /**
     * 当該月のシーケンス現在値を更新する。
     */
    @Update("UPDATE t_service_request_sequence SET current_val = #{nextVal}, updated_at = NOW() WHERE sequence_month = #{month}")
    int updateCurrentVal(@Param("month") String month, @Param("nextVal") int nextVal);
}
