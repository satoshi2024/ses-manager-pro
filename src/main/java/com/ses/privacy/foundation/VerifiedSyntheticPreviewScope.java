package com.ses.privacy.foundation;

/** 一回のread/commitに束縛されるscope capability。service以外からは作れない。 */
final class VerifiedSyntheticPreviewScope {
    static final Object TOKEN = new Object();
    private final String tenantId, subjectRef, scopeId, providerId, providerAudience;
    private final java.util.List<String> targetIds;
    VerifiedSyntheticPreviewScope(String tenantId, String subjectRef, String scopeId, String providerId,
                                  String providerAudience, java.util.List<String> targetIds, Object token) {
        if (token != TOKEN) throw new SecurityException("verified scope capabilityが必要です");
        this.tenantId=tenantId; this.subjectRef=subjectRef; this.scopeId=scopeId;
        this.providerId=providerId; this.providerAudience=providerAudience; this.targetIds=java.util.List.copyOf(targetIds);
    }
    String tenantId(){return tenantId;} String subjectRef(){return subjectRef;} String scopeId(){return scopeId;}
    String providerId(){return providerId;} String providerAudience(){return providerAudience;}
    java.util.List<String> targetIds(){return targetIds;}
}
