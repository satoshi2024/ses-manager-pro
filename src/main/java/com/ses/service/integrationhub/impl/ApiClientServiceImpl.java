package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiClient;
import com.ses.mapper.ApiClientMapper;
import com.ses.service.integrationhub.ApiClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** NF-05 client binding persistence implementation。 */
@Service
@RequiredArgsConstructor
public class ApiClientServiceImpl implements ApiClientService {
    private final ApiClientMapper mapper;

    @Override
    public ApiClient getByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return mapper.selectByClientId(clientId);
    }
}
