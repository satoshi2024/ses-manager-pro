package com.ses.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FreeeConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FreeeConnectionMapper extends BaseMapper<FreeeConnection> {
    
    /** refresh直列化用。active（soft deleteされていない）接続だけを対象にする。 */
    @Select("SELECT * FROM t_freee_connection WHERE deleted_flag = 0 ORDER BY id DESC LIMIT 1 FOR UPDATE")
    FreeeConnection selectLatestForUpdate();

    /**
     * connection_statusだけを更新するtargeted UPDATE（REV-008）。
     * エンティティ全体のupdateByIdは、afterCompletion経路などでstale値がtoken等を
     * 上書きする余地があるため使わない。updated_atはH2/MySQL双方で明示更新する。
     */
    @Update("UPDATE t_freee_connection SET connection_status = #{status}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateConnectionStatus(@Param("id") Long id, @Param("status") String status);
}
