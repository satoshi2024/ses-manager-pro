package com.ses.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FreeeConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FreeeConnectionMapper extends BaseMapper<FreeeConnection> {
    
    @Select("SELECT * FROM t_freee_connection ORDER BY id DESC LIMIT 1 FOR UPDATE")
    FreeeConnection selectLatestForUpdate();
}
