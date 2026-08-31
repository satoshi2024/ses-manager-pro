package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.service.integrationhub.ApiClientScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** NF-05 scope/operation persistence implementation。 */
@Service
@RequiredArgsConstructor
public class ApiClientScopeServiceImpl implements ApiClientScopeService {
    private final ApiClientScopeMapper mapper;

    @Override
    public List<ApiClientScope> listActive(Long apiClientId) {
        return apiClientId == null ? List.of() : mapper.selectActiveByClientId(apiClientId);
    }

    @Override
    public ApiClientScope getActive(Long apiClientId, String scopeCode, String operationCode) {
        if (apiClientId == null || scopeCode == null || scopeCode.isBlank()
                || operationCode == null || operationCode.isBlank()) {
            return null;
        }
        return mapper.selectActive(apiClientId, scopeCode, operationCode);
    }
}
