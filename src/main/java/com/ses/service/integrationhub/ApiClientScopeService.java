package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiClientScope;

import java.util.List;

/** NF-05 client scope / operation permission persistence service。 */
public interface ApiClientScopeService {
    List<ApiClientScope> listActive(Long apiClientId);

    ApiClientScope getActive(Long apiClientId, String scopeCode, String operationCode);
}
