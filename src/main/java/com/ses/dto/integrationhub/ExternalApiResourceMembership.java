package com.ses.dto.integrationhub;

import lombok.Data;

/** webhook replayがDBの現行親relationを再確認するための内部projection。外部JSONへ返さない。 */
@Data
public class ExternalApiResourceMembership {
    private Long primaryResourceId;
    private Long customerId;
    private Long projectId;
    private Long contractId;
}
