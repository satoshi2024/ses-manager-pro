package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.entity.ServiceRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 顧客ポータルへ出す問い合わせの範囲（portal起票、または公開開始時刻以降の作成）を検証する（DB不要）。
 */
class PortalServiceRequestVisibilityPolicyTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        // 同一JVMで先に起動したSpring contextのmetadataがあればそれを使い、置き換えない（他テストへの影響を避ける）。
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(ServiceRequest.class) == null) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), ServiceRequest.class);
        }
    }

    @Test
    void 公開開始時刻が未設定ならportal起票の問い合わせだけを出す() {
        PortalServiceRequestVisibilityPolicy policy = new PortalServiceRequestVisibilityPolicy(new FeatureSwitchProperties());

        assertThat(policy.visibleSince()).isEmpty();
        assertThat(policy.isVisible(portalRequest(LocalDateTime.of(2026, 12, 1, 9, 0)), TOKYO)).isTrue();
        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2030, 1, 1, 0, 0)), TOKYO)).isFalse();
        assertThat(policy.isVisible(internalRequest("EMAIL", LocalDateTime.now()), TOKYO)).isFalse();
        assertThat(policy.isVisible(null, TOKYO)).isFalse();
        assertThat(policy.describe()).contains("portal起票のみ");
        assertThat(PortalServiceRequestVisibilityPolicy.PORTAL_ORIGINATED_ONLY.visibleSince()).isEmpty();
    }

    @Test
    void 社内起票でchannelにPORTALを指定してもportal起票とはみなさない() {
        ServiceRequest labeledAsPortal = internalRequest("PORTAL", LocalDateTime.of(2026, 12, 1, 9, 0));

        assertThat(PortalServiceRequestVisibilityPolicy.isPortalOriginated(labeledAsPortal)).isFalse();
        assertThat(PortalServiceRequestVisibilityPolicy.PORTAL_ORIGINATED_ONLY.isVisible(labeledAsPortal, TOKYO))
                .isFalse();
        // portal_user_idだけが設定されchannelが異なる行もportal起票とはみなさない（両方を要求する）。
        ServiceRequest userOnly = internalRequest("EMAIL", LocalDateTime.of(2026, 12, 1, 9, 0));
        userOnly.setPortalUserId(200L);
        assertThat(PortalServiceRequestVisibilityPolicy.isPortalOriginated(userOnly)).isFalse();
    }

    @Test
    void 公開開始時刻以降に作成された問い合わせは起票経路にかかわらず出し境界時刻ちょうども含む() {
        PortalServiceRequestVisibilityPolicy policy = policy("2027-04-01T00:00:00+09:00");

        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2027, 4, 1, 0, 0)), TOKYO)).isTrue();
        assertThat(policy.isVisible(internalRequest("PHONE", LocalDateTime.of(2027, 5, 1, 10, 0)), TOKYO)).isTrue();
        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2027, 3, 31, 23, 59, 59)), TOKYO))
                .isFalse();
        // 公開開始前でもportal起票なら出す。
        assertThat(policy.isVisible(portalRequest(LocalDateTime.of(2026, 1, 1, 0, 0)), TOKYO)).isTrue();
        // 作成日時が無い行は出さない（fail-closed）。
        assertThat(policy.isVisible(internalRequest("INTERNAL", null), TOKYO)).isFalse();
        assertThat(policy.describe()).contains("2027-04-01T00:00+09:00");
    }

    @Test
    void 公開開始時刻はtenantのtimezoneへ変換して作成日時と比較する() {
        PortalServiceRequestVisibilityPolicy policy = policy("2027-04-01T00:00:00+09:00");
        ZoneId utc = ZoneId.of("UTC");

        // UTCのtenantでは、同じ瞬間はローカル日時 2027-03-31T15:00 になる。
        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2027, 3, 31, 15, 0)), utc)).isTrue();
        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2027, 3, 31, 14, 59)), utc)).isFalse();
        // timezoneが不明な呼出しはAsia/Tokyoとして比較する。
        assertThat(policy.isVisible(internalRequest("INTERNAL", LocalDateTime.of(2027, 4, 1, 0, 0)), null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2027-04-01T00:00:00", "2027-04-01", "tomorrow", "2027/04/01 00:00:00+09:00"})
    void offset付きISO_8601でない公開開始時刻は起動時に失敗させる(String invalid) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf02CustomerPortal().setVisibleSince(invalid);

        assertThatThrownBy(() -> new PortalServiceRequestVisibilityPolicy(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PortalServiceRequestVisibilityPolicy.VISIBLE_SINCE_PROPERTY);
    }

    @Test
    void 空白だけの公開開始時刻と設定欠落は未設定として扱う() {
        FeatureSwitchProperties blank = new FeatureSwitchProperties();
        blank.getNf02CustomerPortal().setVisibleSince("   ");
        FeatureSwitchProperties missing = new FeatureSwitchProperties();
        missing.setNf02CustomerPortal(null);

        assertThat(new PortalServiceRequestVisibilityPolicy(blank).visibleSince()).isEmpty();
        assertThat(new PortalServiceRequestVisibilityPolicy(missing).visibleSince()).isEmpty();
        assertThat(new PortalServiceRequestVisibilityPolicy((FeatureSwitchProperties) null).visibleSince()).isEmpty();
    }

    @Test
    void 一覧queryにはportal起票の条件だけを付け公開開始時刻があればORで作成日時の条件を加える() {
        LambdaQueryWrapper<ServiceRequest> portalOnly = new LambdaQueryWrapper<>();
        PortalServiceRequestVisibilityPolicy.PORTAL_ORIGINATED_ONLY.restrict(portalOnly, TOKYO);
        String portalOnlySql = portalOnly.getSqlSegment().toLowerCase(Locale.ROOT);

        assertThat(portalOnlySql).contains("channel =", "portal_user_id is not null").doesNotContain("created_at");
        assertThat(portalOnly.getParamNameValuePairs()).containsValue("PORTAL");

        LambdaQueryWrapper<ServiceRequest> withSince = new LambdaQueryWrapper<>();
        policy("2027-04-01T00:00:00+09:00").restrict(withSince, ZoneId.of("UTC"));
        String withSinceSql = withSince.getSqlSegment().toLowerCase(Locale.ROOT);

        assertThat(withSinceSql).contains("channel =", "portal_user_id is not null", " or ", "created_at >=");
        assertThat(withSince.getParamNameValuePairs()).containsValue("PORTAL")
                .containsValue(LocalDateTime.of(2027, 3, 31, 15, 0));
        // portal起票の条件はANDで結合した1組としてORの片側に入る（ORが他の条件を打ち消さない）。
        assertThat(withSinceSql.indexOf("portal_user_id is not null")).isLessThan(withSinceSql.indexOf(" or "));
        assertThat(withSinceSql).startsWith("(");
    }

    @Test
    void 公開開始時刻の値をFeatureSwitchPropertiesから読み取る() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf02CustomerPortal().setVisibleSince(" 2027-04-01T00:00:00+09:00 ");

        assertThat(new PortalServiceRequestVisibilityPolicy(properties).visibleSince())
                .contains(OffsetDateTime.parse("2027-04-01T00:00:00+09:00"));
    }

    private static PortalServiceRequestVisibilityPolicy policy(String visibleSince) {
        return new PortalServiceRequestVisibilityPolicy(OffsetDateTime.parse(visibleSince));
    }

    private static ServiceRequest portalRequest(LocalDateTime createdAt) {
        ServiceRequest request = new ServiceRequest();
        request.setId(1L);
        request.setChannel("PORTAL");
        request.setPortalUserId(200L);
        request.setCreatedAt(createdAt);
        return request;
    }

    private static ServiceRequest internalRequest(String channel, LocalDateTime createdAt) {
        ServiceRequest request = new ServiceRequest();
        request.setId(2L);
        request.setChannel(channel);
        request.setPortalUserId(null);
        request.setCreatedAt(createdAt);
        return request;
    }
}
