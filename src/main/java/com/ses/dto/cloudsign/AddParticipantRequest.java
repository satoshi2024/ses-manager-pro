package com.ses.dto.cloudsign;

/**
 * POST /documents/{id}/participants の form request。
 * 単一宛先送信では name/email を必須とし（公式400説明: email/nameの空・不正はerror）、
 * language_code は ja 既定（公式schemaのdefault）。
 */
public record AddParticipantRequest(
        String name,
        String email,
        String organization,
        String languageCode) {

    public AddParticipantRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("宛先名は必須です");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("宛先メールは必須です");
        }
        if (languageCode == null || languageCode.isBlank()) {
            languageCode = "ja";
        }
    }
}
