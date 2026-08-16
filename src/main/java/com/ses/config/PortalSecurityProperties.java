package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ポータル（外部顧客/BP）専用設定（app.portal.*）。
 * 内部管理画面と分離したsession cookie・rate limit・session期限を管理する（G3）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.portal")
public class PortalSecurityProperties {

    /** portal session cookie名（内部JSESSIONIDと分離） */
    private String sessionCookieName = "PORTAL_SESSION";

    /** session絶対期限（時間）。既定12時間 */
    private long sessionMaxLifetimeHours = 12;

    /** sessionアイドル期限（分）。既定30分 */
    private long sessionIdleTimeoutMinutes = 30;

    /** 同一userの同時session上限 */
    private int sessionMaxConcurrent = 5;

    /** rate limit（1分あたり。0以下=無制限） */
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class RateLimit {
        /** login API（IPあたり/分） */
        private int loginPerMinute = 10;
        /** 招待受諾API（IPあたり/分） */
        private int invitePerMinute = 10;
        /** download API（userあたり/分） */
        private int downloadPerMinute = 60;
        /** upload API（userあたり/分） */
        private int uploadPerMinute = 20;
        /** 検収API（userあたり/分） */
        private int acceptancePerMinute = 20;
    }
}
