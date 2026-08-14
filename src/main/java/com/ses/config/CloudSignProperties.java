package com.ses.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * CloudSign連携設定（cloudsign.*）。
 *
 * <p>host は公式の environment allow-list（prod: api.cloudsign.jp / sandbox: api-sandbox.cloudsign.jp）から
 * のみ選択でき、任意URL・HTTP・userinfo・query/fragment付きURLを拒否する（HFP-02-AC-02-01）。
 * {@code enabled=true} なのに client ID・host・timeout が不備なら起動を fail-closed にする
 * （HFP-02-AC-02-04。scanner/storage/文書台帳はHFP-02-10のdeployment readinessで検証）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloudsign")
public class CloudSignProperties {

    /** 機能kill switch。false の間は queue/dispatch/poll が動かない（HFP-02-AC-12-03）。 */
    private boolean enabled = false;

    /** 接続先環境（SANDBOX / PRODUCTION）。 */
    private String environment = "SANDBOX";

    /** base URL。未設定時は環境の公式hostを使用。設定時は公式allow-listと完全一致のみ許可。 */
    private String baseUrl = "";

    /** 環境ごとの公式host（research.md HFP-02-EV-F02）。 */
    public enum CloudSignEnvironment {
        SANDBOX("https://api-sandbox.cloudsign.jp"),
        PRODUCTION("https://api.cloudsign.jp");

        private final String officialBaseUrl;

        CloudSignEnvironment(String officialBaseUrl) {
            this.officialBaseUrl = officialBaseUrl;
        }

        public String officialBaseUrl() {
            return officialBaseUrl;
        }
    }

    /** 発行ユーザーごとのsecret（POST /token の client_id）。平文をDB/log/APIへ出さない。 */
    private String clientId = "";

    /** token有効期限から引く安全余裕（秒）。 */
    private int tokenSafetyMarginSeconds = 60;

    /** HTTP connect timeout（ms）。 */
    private int connectTimeoutMs = 5000;

    /** HTTP read timeout（ms）。公式は最大180秒接続維持だが、本番運用は15秒を既定にする。 */
    private int readTimeoutMs = 15000;

    /** 送信PDFの最大bytes（公式 body 50MB と自システム上限の小さい方。既定50MB）。 */
    private long maxPdfBytes = 50L * 1024 * 1024;

    /** 同一access tokenの毎分request上限（公式800を超えない既定500）。 */
    private int rateLimitPerMinute = 500;

    /** mutation後にprovider反映を待つ時間（ms）。公式ガイドの数秒〜10秒を下限3秒とする。 */
    private int mutationReflectWaitMs = 3000;

    /** poll schedulerのbatch上限。 */
    private int pollBatchSize = 50;

    /** stale claimとみなす経過時間（分）。 */
    private int staleClaimMinutes = 15;

    /** dispatch attempt上限（GET/tokenのbounded retry含む）。 */
    private int maxAttempts = 5;

    /** worker識別子（複数instance分離用。未設定時はホスト名+ランダム）。 */
    private String instanceId = "";

    /** legacy artifact（signed/certificate path）の読み取りroot。既定はアプリupload base。 */
    private String legacyReadBasePath = "./uploads";

    @PostConstruct
    public void validate() {
        CloudSignEnvironment env = resolveEnvironment();
        URI uri = resolveBaseUri(env);
        if (uri == null) {
            throw new IllegalStateException("cloudsign.base-url は公式allow-list（" + env.officialBaseUrl()
                    + "）と完全一致するHTTPSのみ許可されます");
        }
        if (enabled && !StringUtils.hasText(clientId)) {
            throw new IllegalStateException("cloudsign.enabled=true なのに cloudsign.client-id が設定されていません");
        }
        if (enabled && connectTimeoutMs <= 0) {
            throw new IllegalStateException("cloudsign.connect-timeout-ms は正の値が必要です");
        }
        if (enabled && readTimeoutMs <= 0) {
            throw new IllegalStateException("cloudsign.read-timeout-ms は正の値が必要です");
        }
        if (enabled && maxPdfBytes > 50L * 1024 * 1024) {
            throw new IllegalStateException("cloudsign.max-pdf-bytes は公式上限50MBを超えられません");
        }
    }

    public CloudSignEnvironment resolveEnvironment() {
        try {
            return CloudSignEnvironment.valueOf(environment.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cloudsign.environment は SANDBOX または PRODUCTION のみ許可されます: " + environment);
        }
    }

    /**
     * 使用するbase URLを返す。baseUrl未設定時は環境の公式host。
     * userinfo/query/fragment付き、HTTP、公式host以外はnull（fail-closed）。
     */
    public URI resolveBaseUri(CloudSignEnvironment env) {
        String candidate = StringUtils.hasText(baseUrl) ? baseUrl.trim() : env.officialBaseUrl();
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            return null;
        }
        // base URLは公式hostのルートのみ許可（path付きで任意URL化するのを防ぐ）
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        String expected = env.officialBaseUrl();
        try {
            URI expectedUri = new URI(expected);
            String expectedHost = expectedUri.getHost();
            if (!host.equalsIgnoreCase(expectedHost)) {
                return null;
            }
            if (uri.getPort() != -1 && uri.getPort() != (expectedUri.getPort() == -1 ? 443 : expectedUri.getPort())) {
                return null;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return uri;
    }

    /** 実際にHTTP呼び出しに使うbase URL（allow-list検証済み）。 */
    public URI effectiveBaseUri() {
        return resolveBaseUri(resolveEnvironment());
    }
}
