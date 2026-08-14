package com.ses.service.cloudsign;

import com.ses.dto.cloudsign.ConfirmedSendRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 送信payloadのcanonical hash（HFP-02-AC-03-05）。
 * source/recipient/title/optionsを正規化してSHA-256を算出し、queue後の変更を検出する。
 * 宛先メール等のPIIはhash値にのみ残し、平文を保存・ログしない。
 */
public final class CloudSignPayloadHasher {

    private CloudSignPayloadHasher() {
    }

    public static String hash(ConfirmedSendRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("contractNo=").append(request.contractNo()).append('\n');
        canonical.append("templateVersion=").append(request.templateVersion()).append('\n');
        canonical.append("recipientName=").append(normalize(request.recipientName())).append('\n');
        canonical.append("recipientEmail=").append(normalize(request.recipientEmail())).append('\n');
        canonical.append("title=").append(normalize(request.title())).append('\n');
        canonical.append("languageCode=").append(request.languageCode());
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 公式が全角スペースを半角へ変換する契約に合わせ、正規化して比較する。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u3000', ' ').trim();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte v : digest) {
                sb.append(String.format("%02x", v));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
