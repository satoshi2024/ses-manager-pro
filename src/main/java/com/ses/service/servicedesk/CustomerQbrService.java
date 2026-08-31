package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.servicedesk.CustomerQbrCreateRequest;
import com.ses.dto.servicedesk.CustomerQbrDto;
import com.ses.dto.servicedesk.CustomerQbrUpdateRequest;

/**
 * 定例会・QBR管理サービス
 */
public interface CustomerQbrService {

    Page<CustomerQbrDto> searchQbrs(int page, int size, Long customerId, String keyword);

    CustomerQbrDto getQbr(Long id);

    CustomerQbrDto createQbr(CustomerQbrCreateRequest req, Long actorUserId);

    void updateQbr(Long id, CustomerQbrUpdateRequest req);

    void deleteQbr(Long id);
}
