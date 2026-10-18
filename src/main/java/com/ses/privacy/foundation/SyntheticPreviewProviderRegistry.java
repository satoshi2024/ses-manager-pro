package com.ses.privacy.foundation;

import java.util.Map;

/** registry自体はprovider ID/audienceのみを選択境界とし、scope照合後にread portを利用する。 */
final class SyntheticPreviewProviderRegistry {
    private final Map<String,SyntheticPreviewPort> providers;
    private final SyntheticEnvironmentGuard guard;
    SyntheticPreviewProviderRegistry(Map<String,SyntheticPreviewPort> providers,SyntheticEnvironmentGuard guard){
        this.providers=Map.copyOf(providers);this.guard=guard;
        this.providers.forEach((id,p)->{if(!id.equals(p.providerId()))throw new IllegalArgumentException("provider IDが一致しません");});
    }
    SyntheticPreviewPort require(String id,String audience){
        SyntheticPreviewPort p=providers.get(id);
        if(p==null||!p.audience().equals(audience))throw new SecurityException("synthetic provider未登録またはaudience不一致です");
        guard.requireSyntheticAllowed(p.audience());return p;
    }
}
