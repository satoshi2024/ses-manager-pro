package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalOrganization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * ポータル組織マッパー（m_portal_organization）。
 * portal userの認可母集団の起点（design §6.2）。
 */
@Mapper
public interface PortalOrganizationMapper extends BaseMapper<PortalOrganization> {

    /**
     * 顧客IDで有効なポータル組織を取得する（論理削除・停止は呼出側で判定）。
     */
    @Select("SELECT * FROM m_portal_organization WHERE customer_id = #{customerId} AND deleted_flag = 0")
    PortalOrganization selectByCustomerId(Long customerId);

    /**
     * BP会社IDでポータル組織を取得する。
     */
    @Select("SELECT * FROM m_portal_organization WHERE bp_company_id = #{bpCompanyId} AND deleted_flag = 0")
    PortalOrganization selectByBpCompanyId(Long bpCompanyId);
}
