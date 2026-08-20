package com.ses.service.ai;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class AiGatewayRequest {

    public static final String USE_MATCHING = "MATCHING";
    public static final String USE_PROPOSAL_DRAFT = "PROPOSAL_DRAFT";
    public static final String USE_CHAT = "CHAT";
    public static final String USE_INGEST_RESUME = "INGEST_RESUME";
    public static final String USE_INGEST_PROJECT = "INGEST_PROJECT";
    public static final String USE_INGEST_BP = "INGEST_BP_AVAILABILITY";

    public static final String CANARY = "SES-PII-CANARY-T109-7f2e9c1a";

    private String useCase;
    private String traceId;
    private String trustedInstruction;
    private String taskMarker;
    @Builder.Default
    private Map<String, Object> allowlistedFields = new LinkedHashMap<>();
    private String untrustedSourceText;
    private boolean persistRun;
    private Long actorUserId;
    private boolean requireJson;
}
