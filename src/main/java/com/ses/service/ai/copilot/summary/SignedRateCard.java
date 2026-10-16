package com.ses.service.ai.copilot.summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 署名検証前のrate-card DTO。単独では費用計算に利用できない。 */
final class SignedRateCard {
    enum Dimension { PROMPT, CACHED, CANDIDATE, THINKING }

    private final String cardId;
    private final String version;
    private final String provider;
    private final String modelRevision;
    private final String region;
    private final String tier;
    private final String currency;
    private final Instant effectiveFrom;
    private final Instant effectiveUntil;
    private final String priceUnit;
    private final RoundingMode roundingMode;
    private final String keyId;
    private final Map<Dimension, BigDecimal> rates;
    private final byte[] signature;

    SignedRateCard(String cardId, String version, String provider, String modelRevision,
                   String region, String tier, String currency, Instant effectiveFrom,
                   Instant effectiveUntil, String priceUnit, RoundingMode roundingMode,
                   String keyId, Map<Dimension, BigDecimal> rates, byte[] signature) {
        this.cardId = Objects.requireNonNull(cardId);
        this.version = Objects.requireNonNull(version);
        this.provider = Objects.requireNonNull(provider);
        this.modelRevision = Objects.requireNonNull(modelRevision);
        this.region = Objects.requireNonNull(region);
        this.tier = Objects.requireNonNull(tier);
        this.currency = Objects.requireNonNull(currency);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        this.effectiveUntil = Objects.requireNonNull(effectiveUntil);
        this.priceUnit = Objects.requireNonNull(priceUnit);
        this.roundingMode = Objects.requireNonNull(roundingMode);
        this.keyId = Objects.requireNonNull(keyId);
        EnumMap<Dimension, BigDecimal> copy = new EnumMap<>(Dimension.class);
        copy.putAll(Objects.requireNonNull(rates));
        this.rates = Collections.unmodifiableMap(copy);
        this.signature = signature == null ? null : signature.clone();
    }

    String cardId() { return cardId; }
    String version() { return version; }
    String provider() { return provider; }
    String modelRevision() { return modelRevision; }
    String region() { return region; }
    String tier() { return tier; }
    String currency() { return currency; }
    Instant effectiveFrom() { return effectiveFrom; }
    Instant effectiveUntil() { return effectiveUntil; }
    String priceUnit() { return priceUnit; }
    RoundingMode roundingMode() { return roundingMode; }
    String keyId() { return keyId; }
    Map<Dimension, BigDecimal> rates() { return rates; }
    byte[] signature() { return signature == null ? null : signature.clone(); }
}
