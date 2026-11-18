package com.ses.privacy.production;

import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * NF07 F2 の本番 provider registry（production-bridge-phased-plan.md §3.2）。合成層の {@code PrivacyProviderRegistry}
 * とは別の型で、合成層には手を入れない（I-6）。
 *
 * <p>構築時（起動時）に次を検査し、1つでも満たさなければ起動を止める。DB には触れない。
 * <ul>
 *   <li>provider ID の形式（小文字英数字と {@code -}）と重複</li>
 *   <li>外部 provider（Gemini・CloudSign・freee・mailbox・backup/replica）の登録拒否（第1期は scope 外として常に呼ばない）</li>
 *   <li>data element の宣言: 分類が UNKNOWN でないこと、provider 内・provider 間で同じ data element を重ねないこと</li>
 *   <li>列 allow-list: 秘密を示す列名（パスワード・secret・token・MFA・recovery code・nonce・hash 等）を含めないこと</li>
 * </ul>
 * 未登録の provider を要求した場合は {@link #PROVIDER_UNREGISTERED} で拒否する（mock で代替しない）。
 */
@Component
public class ProductionPrivacyProviderRegistry {

    /** 未登録 provider の理由コード（§5.2）。 */
    public static final String PROVIDER_UNREGISTERED = "PROVIDER_UNREGISTERED";

    /** 第1期に登録しない外部 provider を示す ID の区切り単位（{@code -} で区切った語と照合する）。 */
    static final Set<String> EXTERNAL_PROVIDER_MARKERS = Set.of("gemini", "cloudsign", "freee", "mailbox", "mail",
            "imap", "pop3", "backup", "replica", "external", "openai", "llm");

    /** allow-list に置けない列名の断片（小文字で照合する）。 */
    static final List<String> SECRET_COLUMN_MARKERS = List.of("password", "passwd", "secret", "token", "totp", "mfa",
            "recovery", "credential", "nonce", "_hash", "salt", "private_key", "api_key");

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private final Map<String, ProductionPrivacyProvider> providers;

    @Autowired
    public ProductionPrivacyProviderRegistry(ObjectProvider<ProductionPrivacyProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public ProductionPrivacyProviderRegistry(List<? extends ProductionPrivacyProvider> providers) {
        Map<String, ProductionPrivacyProvider> byId = new TreeMap<>();
        List<ProviderDataElement> claimed = new ArrayList<>();
        for (ProductionPrivacyProvider provider : providers == null ? List.<ProductionPrivacyProvider>of() : providers) {
            String id = validateProviderId(provider);
            if (byId.containsKey(id)) {
                throw new IllegalStateException("privacy provider が重複しています: " + id);
            }
            validateTables(id, provider.tables());
            validateElements(id, provider.dataElements(), claimed);
            byId.put(id, provider);
        }
        this.providers = Collections.unmodifiableMap(byId);
    }

    /** 登録済み provider（provider ID の昇順）。 */
    public List<ProductionPrivacyProvider> providers() {
        return List.copyOf(providers.values());
    }

    public Optional<ProductionPrivacyProvider> find(String providerId) {
        return providerId == null ? Optional.empty() : Optional.ofNullable(providers.get(providerId));
    }

    /** 登録済み provider を返す。未登録なら {@link ProviderUnregisteredException}。 */
    public ProductionPrivacyProvider require(String providerId) {
        return find(providerId).orElseThrow(() -> new ProviderUnregisteredException(providerId));
    }

    /** data element を扱う provider（宣言は provider 間で重ならないため高々1件）。 */
    public Optional<ProductionPrivacyProvider> providerFor(String dataElementId) {
        if (dataElementId == null) {
            return Optional.empty();
        }
        return providers.values().stream()
                .filter(provider -> provider.dataElements().stream().anyMatch(e -> e.matches(dataElementId)))
                .findFirst();
    }

    /** provider が data element を宣言していればその宣言。 */
    public static Optional<ProviderDataElement> declaration(ProductionPrivacyProvider provider, String dataElementId) {
        if (provider == null || dataElementId == null) {
            return Optional.empty();
        }
        return provider.dataElements().stream().filter(e -> e.matches(dataElementId)).findFirst();
    }

    /**
     * data element の DSAR 交付区分（A2b の {@code ThirdPartyRedactor} へ渡す値）。provider の宣言が無ければ
     * {@link DsarDeliveryClass#MIXED_THIRD_PARTY}（自動では交付しない）。
     */
    public DsarDeliveryClass deliveryClassOf(String dataElementId) {
        return providerFor(dataElementId)
                .flatMap(provider -> declaration(provider, dataElementId))
                .map(ProviderDataElement::deliveryClass)
                .orElse(DsarDeliveryClass.MIXED_THIRD_PARTY);
    }

    private static String validateProviderId(ProductionPrivacyProvider provider) {
        if (provider == null) {
            throw new IllegalStateException("privacy provider が null です");
        }
        String id = provider.providerId();
        if (id == null || !PROVIDER_ID.matcher(id).matches()) {
            throw new IllegalStateException("privacy provider ID の形式が不正です: " + id);
        }
        for (String part : id.split("-")) {
            if (EXTERNAL_PROVIDER_MARKERS.contains(part)) {
                throw new IllegalStateException("外部 provider は第1期に登録しません（scope外）: " + id);
            }
        }
        return id;
    }

    private static void validateTables(String id, List<ProviderTable> tables) {
        if (tables == null || tables.isEmpty()) {
            throw new IllegalStateException("privacy provider は読む表と列の allow-list を持つ必要があります: " + id);
        }
        for (ProviderTable table : tables) {
            for (String column : table.columns()) {
                String lower = column.toLowerCase(Locale.ROOT);
                for (String marker : SECRET_COLUMN_MARKERS) {
                    if (lower.contains(marker)) {
                        throw new IllegalStateException("秘密を示す列は allow-list に置けません: " + id + " "
                                + table.table() + "." + column);
                    }
                }
            }
        }
    }

    private static void validateElements(String id, List<ProviderDataElement> elements,
                                         List<ProviderDataElement> claimed) {
        if (elements == null || elements.isEmpty()) {
            throw new IllegalStateException("privacy provider は data element を宣言する必要があります: " + id);
        }
        List<ProviderDataElement> own = new ArrayList<>();
        for (ProviderDataElement element : elements) {
            if (element.attribution() == SubjectAttribution.UNKNOWN) {
                throw new IllegalStateException("provider の data element は分類が必要です（UNKNOWN 不可）: " + id + " "
                        + element.dataElementId());
            }
            for (ProviderDataElement other : own) {
                if (element.overlaps(other)) {
                    throw new IllegalStateException("provider 内で data element が重なっています: " + id + " "
                            + element.dataElementId());
                }
            }
            for (ProviderDataElement other : claimed) {
                if (element.overlaps(other)) {
                    throw new IllegalStateException("data element を2つの provider が扱っています: "
                            + element.dataElementId());
                }
            }
            own.add(element);
        }
        claimed.addAll(own);
    }

    /** 未登録 provider の要求（理由コード {@link #PROVIDER_UNREGISTERED}）。 */
    public static final class ProviderUnregisteredException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String providerId;

        public ProviderUnregisteredException(String providerId) {
            super(PROVIDER_UNREGISTERED + ": " + providerId, null, false, false);
            this.providerId = providerId;
        }

        public String providerId() {
            return providerId;
        }

        public String reasonCode() {
            return PROVIDER_UNREGISTERED;
        }
    }
}
