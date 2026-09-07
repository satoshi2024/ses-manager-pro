package com.ses.service.ai.impl;

/**
 * mock/rule 時および gemini+外部送信禁止時の決定的応答。
 */
public final class MockAiResponses {

    private MockAiResponses() {
    }

    public static String generate(String prompt) {
        if (prompt != null && prompt.contains("[TASK:MANAGEMENT_COPILOT]")) {
            String claimKeysJson = extractCopilotClaimKeysJson(prompt);
            return """
                    {
                      "summaryText": "登録された指標キーを確認しました。数値は画面の指標カードを参照してください。",
                      "claimKeys": %s
                    }
                    """.formatted(claimKeysJson);
        }
        if (prompt != null && (prompt.contains("提案メール") || prompt.contains("[TASK:PROPOSAL_DRAFT]"))) {
            return """
                    {
                      "emailText": "この度はお世話になります。株式会社SESの営業担当です。貴社の「Webシステム開発」案件につきまして、弊社の優秀なエンジニア（Y.T）をご提案させていただきます。",
                      "matchReason": "JavaおよびSpring BootでのAPI開発経験が豊富であり、貴社案件の要件に合致しています。",
                      "sellingPoints": "コミュニケーション能力が高く、チーム開発でのリーダー経験もあります。",
                      "matchScore": 85,
                      "reason": "必須スキルと単価が適合しています。",
                      "sellingPoints": "即戦力として期待できます。",
                      "score": 85
                    }
                    """;
        }
        return """
            {
              "engineer": {
                "fullName": "山田 太郎",
                "fullNameKana": "ヤマダ タロウ",
                "gender": "男性",
                "birthDate": "1990-01-01",
                "nationality": "日本",
                "japaneseLevel": "ネイティブ",
                "experienceYears": 5,
                "expectedUnitPrice": 700000,
                "resumeSummary": "JavaおよびSpring Bootを中心としたバックエンド開発経験5年。"
              },
              "skills": [
                {"name": "Java", "proficiency": "上級", "experienceYears": 5}
              ],
              "careers": [],
              "warnings": [],
              "reason": "必須スキルと単価が適合しています。",
              "sellingPoints": "即戦力として期待できます。",
              "score": 80,
              "emailText": "ご提案申し上げます。",
              "matchReason": "スキルが適合しています。",
              "matchScore": 80
            }
            """;
    }

    private static String extractCopilotClaimKeysJson(String prompt) {
        for (String line : prompt.split("\n")) {
            if (line.startsWith("summary.claimKeys=")) {
                String keys = line.substring("summary.claimKeys=".length()).trim();
                if (!keys.isBlank()) {
                    String joined = java.util.Arrays.stream(keys.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .map(s -> "\"" + s + "\"")
                            .collect(java.util.stream.Collectors.joining(","));
                    if (!joined.isBlank()) {
                        return "[" + joined + "]";
                    }
                }
            }
        }
        return "[\"kpi.utilization\"]";
    }
}
