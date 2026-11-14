package com.ses.config;

import com.ses.service.pwa.PwaRestoreSessionRegistry;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.web.util.WebUtils;

/** logout等でsessionが破棄された時、PWA復元の遅延処理も同じmutexで失効させる。 */
public class PwaRestoreSessionListener implements HttpSessionListener {
    private final PwaRestoreSessionRegistry registry;

    public PwaRestoreSessionListener(PwaRestoreSessionRegistry registry) { this.registry = registry; }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        var session = event.getSession();
        String id = session.getId();
        synchronized (WebUtils.getSessionMutex(session)) {
            registry.destroyed(id);
            try {
                session.removeAttribute(com.ses.service.pwa.PwaRestoreAuthorizationService.SESSION_STATE_ATTRIBUTE);
            } catch (IllegalStateException ignored) {
                // Servlet containerが破棄済みattributeへアクセスさせない場合も、global tombstoneが有効。
            }
        }
    }
}
