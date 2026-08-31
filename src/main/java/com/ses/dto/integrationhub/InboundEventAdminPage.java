package com.ses.dto.integrationhub;

import java.util.List;

/** 管理画面向けbounded page。 */
public record InboundEventAdminPage(
        List<InboundEventAdminDto> records,
        long total,
        long current,
        long size) {
    public InboundEventAdminPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
