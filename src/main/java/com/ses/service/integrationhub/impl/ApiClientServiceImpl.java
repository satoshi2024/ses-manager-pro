package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.mapper.ApiClientMapper;
import com.ses.service.integrationhub.ApiClientService;
import org.springframework.stereotype.Service;

/** NF-05 client binding persistence implementation。 */
@Service
public class ApiClientServiceImpl extends ServiceImpl<ApiClientMapper, ApiClient> implements ApiClientService {
    @Override
    public ApiClient getByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return baseMapper.selectByClientId(clientId);
    }
}
