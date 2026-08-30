package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.ApiClient;

/** NF-05 client binding persistence service。公開endpointはまだ持たない。 */
public interface ApiClientService extends IService<ApiClient> {
    ApiClient getByClientId(String clientId);
}
