package com.ses.dto.cloudsign;

/**
 * 送信確認modalでユーザーが明示確認したpayload（HFP-02-AC-03-04/05）。
 * queue受付時にcanonical hashを計算して永続化し、worker実行時に原本/宛先が変わっていれば送信を止める。
 */
public record ConfirmedSendRequest(
        String contractNo,
        Integer templateVersion,
        String recipientName,
        String recipientEmail,
        String title,
        String languageCode) {

    public ConfirmedSendRequest {
        if (contractNo == null || contractNo.isBlank()) {
            throw new IllegalArgumentException("契約番号がありません");
        }
        if (templateVersion == null || templateVersion <= 0) {
            throw new IllegalArgumentException("テンプレートversionがありません");
        }
        if (recipientName == null || recipientName.isBlank()) {
            throw new IllegalArgumentException("宛先名がありません");
        }
        if (recipientEmail == null || recipientEmail.isBlank() || !recipientEmail.contains("@")) {
            throw new IllegalArgumentException("宛先メールがありません");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("送信タイトルがありません");
        }
        if (languageCode == null || languageCode.isBlank()) {
            languageCode = "ja";
        }
        if (!java.util.Set.of("ja", "en", "zh-CHS", "zh-CHT").contains(languageCode)) {
            throw new IllegalArgumentException("送信言語が不正です: " + languageCode);
        }
    }
}
