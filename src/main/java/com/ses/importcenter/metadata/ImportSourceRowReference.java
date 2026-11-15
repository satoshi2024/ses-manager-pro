package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * preview・error exportへ返すsource行参照（W3-12。W3-05 review P2-1）。
 *
 * <p>検証行に保存するsource行hash（{@code t_import_validation_row.source_row_sha256}）は鍵の無いSHA-256で、
 * 原本の再読込・seal・再計算での整合確認（内部fence）に使う。これを画面・CSVへそのまま出すと、previewに出る
 * 平文列・部分値と組み合わせて、マスクした値（担当者名・電話等）を手元で総当たりできてしまう。そのため外部へは、
 * 保存済みhashを秘密鍵付きのHMAC-SHA256で写した値（行参照）だけを返す。鍵を知らなければ平文から計算し直せない。
 *
 * <p>入力は「domain・tenant（長さ前置）・job ID・保存済みhash（32 byte）」の連結。同じjobの中ではpreviewと
 * error exportで同じ値になり行を照合でき、別job・別tenantでは同じ内容の行でも別の値になる（job間で突合できない）。
 * 保存値は変えないため、既存jobの整合確認・照合・内部の比較には影響しない。鍵を差し替えると表示値は全て変わり、
 * 以前に出力したerror CSVの値とは照合できなくなる。
 *
 * <p>鍵は {@value #PROPERTY}（環境変数 {@code IMPORT_ROW_REFERENCE_HMAC_KEY}）。Base64（標準・URL-safeのどちらでも可）で、
 * 復号後 {@value #MIN_KEY_BYTES} byte以上。prodでは開発用の既定鍵を使えない。鍵が使えない間は、preview・
 * error exportを監査・原本読込より前に503（{@code error.import.rowReferenceUnavailable}）で拒否する（fail-closed）。
 * NF-06をONにしたprodの起動検査は {@link ImportRowReferenceKeyStartupValidator}。鍵の値はlog・例外messageへ出さない。
 */
@Component
public class ImportSourceRowReference {
    /** 鍵の設定キー。 */
    public static final String PROPERTY = "app.import-center.row-reference.hmac-key";
    /** 復号後の鍵の最小長（byte）。 */
    static final int MIN_KEY_BYTES = 32;
    /** {@code application.yml} の開発用既定鍵。prodでは使えない（起動検査と実行時の両方で拒否する）。 */
    static final String DEVELOPMENT_ONLY_KEY = "ZGV2LW9ubHktbmYwNi1yb3ctcmVmZXJlbmNlLWtleS1ub3QtZm9yLXByb2Q=";
    static final String UNAVAILABLE_MESSAGE_KEY = "error.import.rowReferenceUnavailable";
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN = "SES-NF06-SOURCE-ROW-REFERENCE-V1".getBytes(StandardCharsets.US_ASCII);
    private static final int ROW_HASH_BYTES = 32;

    /** 鍵の状態。値そのものは持たない（log・監視へ出してよい固定値）。 */
    public enum KeyState { AVAILABLE, MISSING, INVALID, DEVELOPMENT_KEY_IN_PRODUCTION }

    private final SecretKeySpec key;
    private final KeyState state;

    @Autowired
    public ImportSourceRowReference(Environment environment) {
        this(environment.getProperty(PROPERTY), environment.acceptsProfiles(Profiles.of("prod")));
    }

    /** test・起動検査用。{@code production} はprod profileで動いているか。 */
    ImportSourceRowReference(String configuredKey, boolean production) {
        KeyState resolved;
        SecretKeySpec spec = null;
        if (configuredKey == null || configuredKey.isBlank()) {
            resolved = KeyState.MISSING;
        } else if (production && DEVELOPMENT_ONLY_KEY.equals(configuredKey.trim())) {
            resolved = KeyState.DEVELOPMENT_KEY_IN_PRODUCTION;
        } else {
            byte[] decoded = decode(configuredKey.trim());
            if (decoded == null || decoded.length < MIN_KEY_BYTES) {
                resolved = KeyState.INVALID;
            } else {
                spec = new SecretKeySpec(decoded, ALGORITHM);
                resolved = KeyState.AVAILABLE;
            }
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
        this.key = spec;
        this.state = resolved;
    }

    public KeyState state() {
        return state;
    }

    /**
     * jobごとの行参照の生成器を返す。鍵が使えなければ503。返り値は1つのthreadの中だけで使う
     * （{@link Mac} はthread-safeでない）。
     */
    public Scope forJob(String tenantId, long jobId) {
        if (state != KeyState.AVAILABLE || key == null || tenantId == null) {
            throw BusinessException.of(503, UNAVAILABLE_MESSAGE_KEY);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] tenant = tenantId.getBytes(StandardCharsets.UTF_8);
            byte[] prefix = ByteBuffer.allocate(DOMAIN.length + Integer.BYTES + tenant.length + Long.BYTES)
                    .put(DOMAIN).putInt(tenant.length).put(tenant).putLong(jobId).array();
            return new Scope(mac, prefix);
        } catch (GeneralSecurityException failure) {
            throw BusinessException.of(503, UNAVAILABLE_MESSAGE_KEY);
        }
    }

    private static byte[] decode(String value) {
        for (char c : value.toCharArray()) {
            if (Character.isWhitespace(c)) return null;
        }
        try {
            return value.indexOf('-') >= 0 || value.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(value)
                    : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 1つのjobの行参照を作る。保存済みsource行hash（SHA-256の16進64桁）を受け取り、行参照（16進64桁）を返す。 */
    public static final class Scope {
        private final Mac mac;
        private final byte[] prefix;

        private Scope(Mac mac, byte[] prefix) {
            this.mac = mac;
            this.prefix = prefix;
        }

        public String reference(String storedRowSha256Hex) {
            byte[] rowHash;
            try {
                rowHash = HexFormat.of().parseHex(storedRowSha256Hex);
            } catch (RuntimeException malformed) {
                throw BusinessException.of(409, "error.import.validationRowMismatch");
            }
            if (rowHash.length != ROW_HASH_BYTES) {
                throw BusinessException.of(409, "error.import.validationRowMismatch");
            }
            mac.update(prefix);
            mac.update(rowHash);
            return HexFormat.of().formatHex(mac.doFinal());
        }
    }
}
