package com.ses.service.integrationhub;

import com.ses.dto.integrationhub.InboundEventAdminPage;
import com.ses.dto.integrationhub.InboundEventReplayResponse;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;

/** inbound DLQのadmin UI境界。entity/snapshotをcontrollerへ返さない。 */
public interface InboundEventAdminService {
    InboundEventAdminPage page(long current, long size, String status, String providerName);

    InboundEventReplayResponse replay(Long inboundEventId, String reasonCode,
                                      Authentication authentication, LocalDateTime now);

    InboundEventReplayResponse processReplay(Long requestId, Authentication authentication,
                                              LocalDateTime now);
}
