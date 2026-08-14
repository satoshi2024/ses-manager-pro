package com.ses.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FreeeConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FreeeConnectionMapper extends BaseMapper<FreeeConnection> {
    
    /** refresh直列化用。active（soft deleteされていない）接続だけを対象にする。 */
    @Select("SELECT * FROM t_freee_connection WHERE deleted_flag = 0 ORDER BY id DESC LIMIT 1 FOR UPDATE")
    FreeeConnection selectLatestForUpdate();
}
