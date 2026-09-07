package com.ses.dto.ai;

/** citation再認可結果。scope外IDや直接URLは含めない。 */
public record ResolvedCitationDto(
        String key,
        String label,
        String route,
        boolean available
) {
    public static ResolvedCitationDto unavailable(String key) {
        return new ResolvedCitationDto(key, null, null, false);
    }
}
