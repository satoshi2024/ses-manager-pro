package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceAttachmentLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * サービスリクエスト添付ファイルリンクマッパー
 */
@Mapper
public interface ServiceAttachmentLinkMapper extends BaseMapper<ServiceAttachmentLink> {

    @Select("SELECT * FROM t_service_attachment_link WHERE tenant_id = #{tenantId} "
            + "AND business_key = #{businessKey} LIMIT 1")
    ServiceAttachmentLink selectByBusinessKey(@Param("tenantId") String tenantId,
                                               @Param("businessKey") String businessKey);

    @Select("SELECT * FROM t_service_attachment_link WHERE tenant_id = #{tenantId} "
            + "AND id = #{id} AND service_request_id = #{requestId}")
    ServiceAttachmentLink selectByTenantIdAndRequest(@Param("tenantId") String tenantId,
                                                      @Param("id") Long id,
                                                      @Param("requestId") Long requestId);
}
