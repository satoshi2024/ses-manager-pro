package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserOrganization;
import org.apache.ibatis.annotations.Mapper;

/** ユーザー所属履歴Mapper。 */
@Mapper
public interface UserOrganizationMapper extends BaseMapper<UserOrganization> {
}
