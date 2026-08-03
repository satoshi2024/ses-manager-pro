package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
    @Select("SELECT * FROM m_customer WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Customer selectByIdForUpdate(Long id);
}
