package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiClient;

/** NF-05 client binding persistence service。公開endpointはまだ持たない。 */
public interface ApiClientService {
    ApiClient getByClientId(String clientId);
}
