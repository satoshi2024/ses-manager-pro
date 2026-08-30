package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.service.integrationhub.ApiClientScopeService;
import org.springframework.stereotype.Service;

import java.util.List;

/** NF-05 scope/operation persistence implementation。 */
@Service
public class ApiClientScopeServiceImpl extends ServiceImpl<ApiClientScopeMapper, ApiClientScope>
        implements ApiClientScopeService {
    @Override
    public List<ApiClientScope> listActive(Long apiClientId) {
        return apiClientId == null ? List.of() : baseMapper.selectActiveByClientId(apiClientId);
    }

    @Override
    public ApiClientScope getActive(Long apiClientId, String scopeCode, String operationCode) {
        if (apiClientId == null || scopeCode == null || scopeCode.isBlank()
                || operationCode == null || operationCode.isBlank()) {
            return null;
        }
        return baseMapper.selectActive(apiClientId, scopeCode, operationCode);
    }
}
