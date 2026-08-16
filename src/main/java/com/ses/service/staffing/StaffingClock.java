package com.ses.service.staffing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 要員配置・需給計画の計画window（"今日"と24か月上限）の共通解決。
 *
 * <p>timezoneはtenant設定（spring.jackson.time-zone）を参照し、Asia/Tokyoをコードへ直書きしない
 * （platform-invariants §1）。計画windowは最大24か月（design §4/§5.4）。
 */
@Component
public class StaffingClock {

    /** 計画window長（か月）。最大24か月（design §4/§5.4）。 */
    public static final int HORIZON_MONTHS = 24;

    private final ZoneId zoneId;

    public StaffingClock(@Value("${spring.jackson.time-zone:Asia/Tokyo}") String deploymentTimezone) {
        this.zoneId = ZoneId.of(deploymentTimezone == null || deploymentTimezone.isBlank()
                ? "Asia/Tokyo" : deploymentTimezone.trim());
    }

    /** 計画の基準日（"今日"）。 */
    public LocalDate today() {
        return LocalDate.now(zoneId);
    }

    /** 計画window末（open end区間の上限）。 */
    public LocalDate horizonEnd() {
        return today().plusMonths(HORIZON_MONTHS);
    }
}
