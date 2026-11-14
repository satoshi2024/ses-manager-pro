package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.entity.ServiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
 * 公開開始時刻が未設定・空なら1だけ（fail-closed）。不正な値は起動時に失敗させる（公開範囲を推測しない）。
 * 作成日時（{@code created_at}）はtenantのtimezoneのローカル日時で保存されるため、公開開始時刻は同じtimezoneへ
 * 変換して比較する。内部サービスデスクの経路はこのpolicyを使わない。
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

    private final OffsetDateTime visibleSince;

    @Autowired
    public PortalServiceRequestVisibilityPolicy(FeatureSwitchProperties properties) {
        this(parseVisibleSince(properties == null || properties.getNf02CustomerPortal() == null
                ? null : properties.getNf02CustomerPortal().getVisibleSince()));
        log.info("[顧客ポータル問い合わせ] portalへ出す範囲: {}", describe());
    }

    public PortalServiceRequestVisibilityPolicy(OffsetDateTime visibleSince) {
        this.visibleSince = visibleSince;
    }

    /** 公開開始時刻（未設定なら空）。 */
    public Optional<OffsetDateTime> visibleSince() {
        return Optional.ofNullable(visibleSince);
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

    /** 公開開始時刻をtenantのtimezoneのローカル日時へ変換する（未設定ならnull）。 */
    LocalDateTime visibleSinceAt(ZoneId zone) {
        if (visibleSince == null) {
            return null;
        }
        ZoneId effective = zone != null ? zone : ZoneId.of("Asia/Tokyo");
        return visibleSince.atZoneSameInstant(effective).toLocalDateTime();
    }

    /** ログ用の説明。 */
    public String describe() {
        return visibleSince == null
                ? "portal起票のみ（" + VISIBLE_SINCE_PROPERTY + " 未設定）"
                : "portal起票、または " + visibleSince + " 以降に作成された問い合わせ";
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
}
