package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.IpCidrMatcher;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本番の転送ヘッダー設定を起動時に検査する（FWD-SANITIZE）。不足・誤りがあれば起動を拒否する（fail-fast）。
 *
 * <ul>
 *   <li>{@code server.forward-headers-strategy} は {@code framework}。{@link ForwardedHeaderConfiguration} の
 *       清浄化filterはframeworkのときだけ登録され、{@code native} ではTomcatのRemoteIpValveが
 *       clientの送った {@code X-Forwarded-Host} 等を信頼プロキシ経由として反映してしまう。</li>
 *   <li>{@code app.security.trusted-proxies}（環境変数 {@code TRUSTED_PROXIES}）にALBのsubnetを設定する。
 *       未設定でも実行時は転送ヘッダーを一切信頼しない（fail-closed）が、本番はALB配下でHTTPS終端するため
 *       X-Forwarded-Protoを読めず、HTTPS強制のredirectが終わらずOIDCのredirect URIも {@code http://} になる。
 *       この状態でdeployが「成功」しないよう、未設定は起動時に拒否する。</li>
 *   <li>各要素はCIDR（構文は {@link ClientIpResolver} が起動時に検査済み）で、範囲が広すぎないこと
 *       （IPv4は/{@value #MIN_IPV4_PREFIX}以上、IPv6は/{@value #MIN_IPV6_PREFIX}以上）。範囲内の任意のhostが
 *       client IPとHTTPS判定を偽装できるため、VPC全体より広い指定（{@code 0.0.0.0/0} 等）は拒否する。</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ForwardedHeaderProductionValidator {

    /** AWS VPCのIPv4 CIDRの最大（/16）。これより広い範囲はVPCの外を含む。 */
    static final int MIN_IPV4_PREFIX = 16;
    /** AWS VPCのIPv6 CIDRは/56、subnetは/64。/48より広い範囲は拒否する。 */
    static final int MIN_IPV6_PREFIX = 48;

    private final Environment environment;
    private final ClientIpResolver clientIpResolver;

    public ForwardedHeaderProductionValidator(Environment environment, ClientIpResolver clientIpResolver) {
        this.environment = environment;
        this.clientIpResolver = clientIpResolver;
    }

    @PostConstruct
    void validate() {
        List<String> errors = check(environment.getProperty("server.forward-headers-strategy"),
                clientIpResolver.trustedProxies());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("本番の転送ヘッダー設定が不正なため起動できません: " + String.join(" / ", errors));
        }
    }

    static List<String> check(String strategy, List<String> trustedProxies) {
        List<String> errors = new ArrayList<>();
        if (strategy == null || !"framework".equalsIgnoreCase(strategy.trim())) {
            errors.add("server.forward-headers-strategyはframeworkにしてください（転送ヘッダーの清浄化filterはframeworkのときだけ有効）");
        }
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            errors.add("app.security.trusted-proxies（環境変数TRUSTED_PROXIES）にALBのsubnet CIDRを設定してください");
            return errors;
        }
        for (String proxy : trustedProxies) {
            int prefix = IpCidrMatcher.prefixLength(proxy);
            if (prefix < 0) {
                errors.add("app.security.trusted-proxiesはIPアドレスまたはCIDR形式で指定してください: " + proxy);
                continue;
            }
            int minimum = IpCidrMatcher.isIpv4Range(proxy) ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
            if (prefix < minimum) {
                errors.add("app.security.trusted-proxiesの範囲が広すぎます（IPv4は/" + MIN_IPV4_PREFIX
                        + "以上、IPv6は/" + MIN_IPV6_PREFIX + "以上）: " + proxy);
            }
        }
        return errors;
    }
}
