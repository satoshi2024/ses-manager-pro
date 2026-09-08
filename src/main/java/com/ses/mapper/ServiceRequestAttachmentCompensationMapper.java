package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceRequestAttachmentCompensation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ServiceRequestAttachmentCompensationMapper
        extends BaseMapper<ServiceRequestAttachmentCompensation> {

    @Select("SELECT * FROM t_service_attachment_compensation "
            + "WHERE status = 'RETRY' AND next_retry_at <= CURRENT_TIMESTAMP ORDER BY id LIMIT #{limit}")
    List<ServiceRequestAttachmentCompensation> selectDue(@Param("limit") int limit);

    @Select("SELECT * FROM t_service_attachment_compensation WHERE tenant_id = #{tenantId} "
            + "AND business_key = #{businessKey} AND status = 'RETRY' LIMIT 1")
    ServiceRequestAttachmentCompensation selectRetry(@Param("tenantId") String tenantId,
                                                     @Param("businessKey") String businessKey);
}
