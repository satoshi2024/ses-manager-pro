package com.ses.service.cloudsign;

import com.ses.common.enums.CloudSignErrorCode;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.CloudSignAccessToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * CloudSign access token provider（HFP-02-AC-02-03）。
 *
 * <ul>
 *   <li>公式契約どおり {@code POST /token} を form-urlencoded の {@code client_id} で呼ぶ（OAuth/refresh は作らない）</li>
 *   <li>token値はメモリのみ。expires_in から安全余裕を引いて cache し、同一JVM内の同時取得をsingle-flight化</li>
 *   <li>401後の再取得は呼び出し側(client)が一操作につき一回だけ {@link #invalidateAndGetOnce()} を呼ぶ</li>
 * </ul>
 */
@Slf4j
@Component
public class CloudSignTokenProvider {

    private final CloudSignProperties properties;
    private final RestTemplate rest;
    private final CloudSignErrorClassifier classifier;

    private volatile CachedToken cached;

    public CloudSignTokenProvider(CloudSignProperties properties,
                                  @Qualifier("cloudsignRestTemplate") RestTemplate rest,
                                  CloudSignErrorClassifier classifier) {
        this.properties = properties;
        this.rest = rest;
        this.classifier = classifier;
    }

    /** 有効なtoken値を返す。期限切れ・未取得なら取得してから返す。 */
    public String getToken() {
        CachedToken current = cached;
        if (current != null && current.validAt(Instant.now())) {
            return current.token();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.validAt(Instant.now())) {
                return current.token();
            }
            CachedToken fresh = fetch();
            cached = fresh;
            return fresh.token();
        }
    }

    /** cacheを捨てて一回だけ再取得する（401経路）。取得失敗時はcacheが空のまま（古いtokenの無期限利用をしない）。 */
    public String invalidateAndGetOnce() {
        synchronized (this) {
            cached = null;
            CachedToken fresh = fetch();
            cached = fresh;
            return fresh.token();
        }
    }

    public void invalidate() {
        synchronized (this) {
            cached = null;
        }
    }

    private CachedToken fetch() {
        if (!properties.isEnabled()) {
            throw new CloudSignApiException(CloudSignErrorCode.INVALID_CLIENT, "cloudsign disabled");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        try {
            ResponseEntity<CloudSignAccessToken> response = rest.postForEntity(
                    properties.effectiveBaseUri() + "/token",
                    new HttpEntity<>(form, headers),
                    CloudSignAccessToken.class);
            CloudSignAccessToken token = response.getBody();
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE,
                        "token responseにaccess_tokenがありません");
            }
            long expiresInSeconds = token.expiresIn() != null && token.expiresIn() > 0
                    ? token.expiresIn() : 3600L;
            long marginSeconds = Math.max(0, properties.getTokenSafetyMarginSeconds());
            Instant expiresAt = Instant.now()
                    .plus(Duration.ofSeconds(expiresInSeconds))
                    .minus(Duration.ofSeconds(marginSeconds));
            log.info("CloudSign access tokenを取得しました: expiresIn={}s margin={}s",
                    expiresInSeconds, marginSeconds);
            return new CachedToken(token.accessToken(), expiresAt);
        } catch (RestClientResponseException e) {
            throw classifier.classifyResponse(e.getRawStatusCode(), e.getResponseBodyAsByteArray(), false);
        } catch (RuntimeException e) {
            throw classifier.classify(e, false);
        }
    }

    /** メモリ内token。値はtoString/log/例外へ出さない。 */
    record CachedToken(String token, Instant expiresAt) {
        boolean validAt(Instant now) {
            return expiresAt.isAfter(now);
        }
    }
}
