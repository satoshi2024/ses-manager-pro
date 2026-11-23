package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.entity.ServiceRequest;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * 顧客ポータルへ出してよい問い合わせの範囲（2026-11-13 決定: 初回リリース中に社内で登録した問い合わせを、
 * 第二段階でportalを開いた時に顧客へ出さない）。
 *
 * <p>portalの一覧・詳細・返信・CSAT・添付upload/downloadの全経路で、次のいずれかを満たす問い合わせだけを対象にする。
 * <ol>
 *   <li>portalから起票された問い合わせ（{@code channel = 'PORTAL'} かつ {@code portal_user_id} あり）。
 *       社内起票でもchannelに {@code PORTAL} を指定できるため、channelだけでは判定しない
 *       （{@code portal_user_id} はportal起票でしか設定されない）。</li>
 *   <li>公開開始時刻（{@code app.features.nf02-customer-portal.visible-since}）以降に作成された問い合わせ。</li>
 * </ol>
 * 公開開始時刻が未設定・空なら1だけ（fail-closed）。作成日時（{@code created_at}）はtenantのtimezoneのローカル日時で保存されるため、
 * 公開開始時刻は同じtimezoneへ変換して比較する。内部サービスデスクの経路はこのpolicyを使わない。
 *
 * <p>公開開始時刻の源（feature-enablement-console design §8.4、FE-RQ-010。FE-B03）:
 * <ul>
 *   <li>DB の源（prod）: 承認で変える設定（{@code t_feature_enablement_setting} の
 *       {@value FeatureEnablementCatalog#SETTING_NF02_VISIBLE_SINCE}。申請と別の管理者の承認で変える）を、判定のたびに snapshot から
 *       読む（構築時に固定しない。承認の後の次の判定から新しい範囲になる）。解釈の結果は値の文字列を key に1件だけ cache する。DB の値を
 *       解釈できなければ portal 起票だけ（fail-closed。値は申請の時に検査するため通常は起きない）で、WARN を値ごとに1回出す。</li>
 *   <li>property の源（dev・test）: 従来どおり構築時に解釈し、不正な値は起動時に失敗させる（公開範囲を推測しない）。</li>
 * </ul>
 */
@Slf4j
@Component
public class PortalServiceRequestVisibilityPolicy {

    /** 設定キー（ログ・エラー文言用）。 */
    public static final String VISIBLE_SINCE_PROPERTY = "app.features.nf02-customer-portal.visible-since";

    static final String PORTAL_CHANNEL = "PORTAL";

    /** portal起票の問い合わせだけを出すpolicy。policy Beanを取得できない経路の既定値（fail-closed）。 */
    public static final PortalServiceRequestVisibilityPolicy PORTAL_ORIGINATED_ONLY =
            new PortalServiceRequestVisibilityPolicy((OffsetDateTime) null);

    /** property の源の公開開始時刻（構築時に固定）。DB の源では使わない。 */
    private final OffsetDateTime fixedVisibleSince;
    /** DB の源（null なら構築時に固定した値を使う）。 */
    private final FeatureEnablementStateSource source;
    /** DB の値の解釈の cache（値の文字列 → 結果。1件だけ）。 */
    private volatile Parsed parsed;

    @Autowired
    public PortalServiceRequestVisibilityPolicy(FeatureSwitchProperties properties,
                                                ObjectProvider<FeatureEnablementStateSource> stateSource) {
        FeatureEnablementStateSource resolved = stateSource == null ? null : stateSource.getIfAvailable();
        if (resolved != null && resolved.database()) {
            this.fixedVisibleSince = null;
            this.source = resolved;
        } else {
            this.fixedVisibleSince = parseVisibleSince(propertyValue(properties));
            this.source = null;
        }
        log.info("[顧客ポータル問い合わせ] portalへ出す範囲: {}", describe());
    }

    /** property の源（構築時に解釈し、不正な値は起動時に失敗させる）。 */
    public PortalServiceRequestVisibilityPolicy(FeatureSwitchProperties properties) {
        this(parseVisibleSince(propertyValue(properties)));
        log.info("[顧客ポータル問い合わせ] portalへ出す範囲: {}", describe());
    }

    public PortalServiceRequestVisibilityPolicy(OffsetDateTime visibleSince) {
        this.fixedVisibleSince = visibleSince;
        this.source = null;
    }

    /** DB の源（判定のたびに源の設定を読む。試験の補助にも使う）。 */
    public PortalServiceRequestVisibilityPolicy(FeatureEnablementStateSource source) {
        this.fixedVisibleSince = null;
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 公開開始時刻（未設定なら空）。DB の源は呼ぶたびに今の設定を読む。 */
    public Optional<OffsetDateTime> visibleSince() {
        return Optional.ofNullable(current());
    }

    /** portal経路でこの問い合わせを扱ってよいか。 */
    public boolean isVisible(ServiceRequest request, ZoneId zone) {
        if (request == null) {
            return false;
        }
        if (isPortalOriginated(request)) {
            return true;
        }
        LocalDateTime since = visibleSinceAt(zone);
        return since != null && request.getCreatedAt() != null && !request.getCreatedAt().isBefore(since);
    }

    /** portalの一覧queryへ {@link #isVisible} と同じ条件を付ける。 */
    public void restrict(LambdaQueryWrapper<ServiceRequest> wrapper, ZoneId zone) {
        LocalDateTime since = visibleSinceAt(zone);
        wrapper.and(scope -> {
            scope.nested(portal -> portal.eq(ServiceRequest::getChannel, PORTAL_CHANNEL)
                    .isNotNull(ServiceRequest::getPortalUserId));
            if (since != null) {
                scope.or().ge(ServiceRequest::getCreatedAt, since);
            }
        });
    }

    /** portalから起票された問い合わせか（社内起票ではportal_user_idが設定されない）。 */
    public static boolean isPortalOriginated(ServiceRequest request) {
        return request != null && PORTAL_CHANNEL.equals(request.getChannel()) && request.getPortalUserId() != null;
    }

    /** 公開開始時刻をtenantのtimezoneのローカル日時へ変換する（未設定なら null）。 */
    LocalDateTime visibleSinceAt(ZoneId zone) {
        OffsetDateTime visibleSince = current();
        if (visibleSince == null) {
            return null;
        }
        ZoneId effective = zone != null ? zone : ZoneId.of("Asia/Tokyo");
        return visibleSince.atZoneSameInstant(effective).toLocalDateTime();
    }

    /** ログ用の説明。 */
    public String describe() {
        if (source != null) {
            return "portal起票、または承認で変える公開範囲（" + FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE
                    + "。判定のたびに読む）以降に作成された問い合わせ";
        }
        return fixedVisibleSince == null
                ? "portal起票のみ（" + VISIBLE_SINCE_PROPERTY + " 未設定）"
                : "portal起票、または " + fixedVisibleSince + " 以降に作成された問い合わせ";
    }

    static OffsetDateTime parseVisibleSince(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException(VISIBLE_SINCE_PROPERTY
                    + " はoffset付きのISO-8601日時（例: 2027-04-01T00:00:00+09:00）で指定してください", invalid);
        }
    }

    /** 今の公開開始時刻（DB の源は snapshot の設定を読み、解釈は値ごとに1回）。 */
    private OffsetDateTime current() {
        if (source == null) {
            return fixedVisibleSince;
        }
        String raw;
        try {
            raw = source.setting(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE).orElse(null);
        } catch (RuntimeException unreadable) {
            log.warn("[顧客ポータル問い合わせ] 公開範囲を読めないため portal 起票の問い合わせだけを出します: exceptionClass={}",
                    unreadable.getClass().getSimpleName());
            return null;
        }
        Parsed cached = parsed;
        if (cached != null && Objects.equals(cached.raw(), raw)) {
            return cached.value();
        }
        OffsetDateTime value = null;
        if (StringUtils.hasText(raw)) {
            try {
                value = OffsetDateTime.parse(raw.trim());
            } catch (DateTimeParseException invalid) {
                log.warn("[顧客ポータル問い合わせ] 承認で変える公開範囲を解釈できないため portal 起票の問い合わせだけを出します"
                        + "（fail-closed）: setting={}", FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE);
            }
        }
        parsed = new Parsed(raw, value);
        return value;
    }

    private static String propertyValue(FeatureSwitchProperties properties) {
        return properties == null || properties.getNf02CustomerPortal() == null
                ? null : properties.getNf02CustomerPortal().getVisibleSince();
    }

    /** 値の文字列と解釈の結果。 */
    private record Parsed(String raw, OffsetDateTime value) {
    }
}
