package com.ses.privacy.production;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.security.AuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * NF07 の service 層の再認可（production-bridge-phased-plan.md §3.1.2 の6・§6.8）。
 *
 * <p>privacy の各 service は入口で本 guard を通す。{@code FeatureSwitchFilter}（登録済みprefixだけを404にする）と
 * {@code MenuPermissionFilter}（{@code AuthorizationService} が取れないsliceでは素通し）に依存せず、
 * <ol>
 *   <li>公開スイッチ: OFF なら {@code FeatureSwitchFilter} と同じ本文の404（遮断表に登録していない
 *       {@code /api/privacy/<x>} でも同じ応答になる）。{@link FeatureSwitches} が取れない場合もOFFとして扱う。</li>
 *   <li>action 権限: {@code privacy.} 接頭辞の action だけを受け付け、{@link AuthorizationService#isAllowed} が
 *       true の場合だけ通す。{@code AuthorizationService} が取れない・認証が無い・例外の場合は403（fail-closed）。</li>
 * </ol>
 * の順に判定する（OFF の機能の存在を権限エラーで知らせない）。
 */
@Slf4j
@Component
public class PrivacyAccessGuard {

    /**
     * 判定対象の公開単位。RETENTION は {@code Feature.NF07_RETENTION}、DSAR は {@code Feature.NF07_DSAR}（A2a で登録）の
     * 実効値で判定する（{@code FeatureSwitchFilter}・sidebar・prod の実行時条件と同じ値になる）。
     */
    public enum Capability {
        RETENTION,
        DSAR
    }

    private static final String PRIVACY_ACTION_PREFIX = "privacy.";

    private final ObjectProvider<FeatureSwitches> featureSwitchesProvider;
    private final ObjectProvider<AuthorizationService> authorizationServiceProvider;

    public PrivacyAccessGuard(ObjectProvider<FeatureSwitches> featureSwitchesProvider,
                              ObjectProvider<AuthorizationService> authorizationServiceProvider) {
        this.featureSwitchesProvider = featureSwitchesProvider;
        this.authorizationServiceProvider = authorizationServiceProvider;
    }

    /**
     * 公開スイッチと action 権限を両方確認する。
     *
     * @throws PrivacyEntryNotFoundException スイッチがOFF（404）
     * @throws BusinessException             action 権限が無い・確認できない（403）
     */
    public void requireAllowed(Capability capability, String actionKey) {
        if (!isEnabled(capability)) {
            throw new PrivacyEntryNotFoundException();
        }
        if (actionKey == null || !actionKey.startsWith(PRIVACY_ACTION_PREFIX)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthorizationService authorizationService = authorizationServiceProvider.getIfAvailable();
        boolean allowed;
        try {
            allowed = authentication != null && authorizationService != null
                    && authorizationService.isAllowed(authentication, actionKey);
        } catch (RuntimeException e) {
            log.warn("[privacy] action権限を確認できないため拒否します: action={}", actionKey, e);
            allowed = false;
        }
        if (!allowed) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    /** 公開スイッチの実効値。判定部品が取れない場合はOFF（fail-closed）。 */
    public boolean isEnabled(Capability capability) {
        if (capability == null) {
            return false;
        }
        FeatureSwitches switches = featureSwitchesProvider.getIfAvailable();
        if (switches == null) {
            return false;
        }
        return switch (capability) {
            case RETENTION -> switches.isEnabled(Feature.NF07_RETENTION);
            case DSAR -> switches.isEnabled(Feature.NF07_DSAR);
        };
    }

    /** {@code FeatureSwitchFilter} の404と同じ status・本文。controller の例外handlerから返す。 */
    public static ResponseEntity<ApiResult<Void>> notFoundResponse() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.<Void>error(HttpStatus.NOT_FOUND.value(), FeatureSwitchFilter.NOT_FOUND_MESSAGE));
    }

    /** 公開スイッチがOFFの privacy 入口。存在しないURLと同じ404として返す（stack traceは持たない）。 */
    public static final class PrivacyEntryNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PrivacyEntryNotFoundException() {
            super("privacy entry is disabled", null, false, false);
        }
    }
}
