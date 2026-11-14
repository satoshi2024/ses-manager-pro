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
 *   <li>IPv4の範囲はprivate address（RFC 1918の {@code 10/8}・{@code 172.16/12}・{@code 192.168/16}）または
 *       shared address（RFC 6598の {@code 100.64/10}）に収まること（P3-7）。ALBのnodeはVPC内のsubnetから
 *       private IPv4を受け取ってtargetへ接続するため、公開IPv4（{@code 8.8.0.0/16} 等）が信頼プロキシに
 *       なる正規の構成は無く、指定されていれば誤設定である。{@code 127.0.0.0/8} もALBではないため拒否する
 *       （container内からのhealth checkはHTTP許可path（{@code /actuator/health}）を使い、信頼しない）。
 *       IPv6はVPCに割り当たる範囲自体がglobal unicastのため、許可する種別では絞らない。ただしIPv4と同じく
 *       ALBになり得ないloopback（{@code ::1}）・未指定（{@code ::}）・link-local（{@code fe80::/10}）・
 *       multicast（{@code ff00::/8}）と重なる範囲は拒否する（r3 D-P3-3）。</li>
 *   <li>この制約は本番VPC・ALB subnetをRFC 1918または {@code 100.64/10} で設計することを前提にする。
 *       AWSが許す公開routableなCIDRや {@code 198.19.0.0/16} 等をVPCに使う構成は対象外で、採用する場合は
 *       このvalidatorの変更が必要になる（r3 D-P3-4。runbookの配備前確認でVPC CIDRを確かめる）。</li>
 *   <li>host部に1のbitがあるCIDR（{@code 10.0.1.5/24} 等）は、意図した範囲が曖昧なため拒否する。</li>
 * </ul>
 * <p>{@code @PostConstruct} で検査するため、{@code spring.main.lazy-initialization=true} にすると検査されない。
 * 本番でlazy初期化を有効にしないこと（他の起動時validatorと同じ前提）。
 */
@Component
@Profile("prod")
public class ForwardedHeaderProductionValidator {

    /** AWS VPCのIPv4 CIDRの最大（/16）。これより広い範囲はVPCの外を含む。 */
    static final int MIN_IPV4_PREFIX = 16;
    /** AWS VPCのIPv6 CIDRは/56、subnetは/64。/48より広い範囲は拒否する。 */
    static final int MIN_IPV6_PREFIX = 48;
    /** 信頼プロキシとして許すIPv4の範囲（RFC 1918のprivate addressとRFC 6598のshared address）。 */
    static final List<String> ALLOWED_IPV4_BLOCKS = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "100.64.0.0/10");
    /** 信頼プロキシと重なってはならないIPv6の範囲（loopback・未指定・link-local・multicast）。 */
    static final List<String> FORBIDDEN_IPV6_BLOCKS = List.of("::1/128", "::/128", "fe80::/10", "ff00::/8");

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
            boolean ipv4 = IpCidrMatcher.isIpv4Range(proxy);
            int minimum = ipv4 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
            if (prefix < minimum) {
                errors.add("app.security.trusted-proxiesの範囲が広すぎます（IPv4は/" + MIN_IPV4_PREFIX
                        + "以上、IPv6は/" + MIN_IPV6_PREFIX + "以上）: " + proxy);
            }
            if (ipv4 && ALLOWED_IPV4_BLOCKS.stream().noneMatch(block -> IpCidrMatcher.isContainedIn(proxy, block))) {
                errors.add("app.security.trusted-proxiesのIPv4はprivate address（10/8・172.16/12・192.168/16）"
                        + "またはshared address（100.64/10）のALB subnetに限ります: " + proxy);
            }
            if (!ipv4 && FORBIDDEN_IPV6_BLOCKS.stream().anyMatch(block -> IpCidrMatcher.isContainedIn(proxy, block)
                    || IpCidrMatcher.isContainedIn(block, proxy))) {
                errors.add("app.security.trusted-proxiesのIPv6にloopback（::1）・未指定（::）・link-local（fe80::/10）・"
                        + "multicast（ff00::/8）は指定できません: " + proxy);
            }
            if (IpCidrMatcher.hasHostBits(proxy)) {
                errors.add("app.security.trusted-proxiesはnetwork address（host部が0）のCIDRで指定してください: " + proxy);
            }
        }
        return errors;
    }
}
