package com.ses.service.ai.impl;

import com.ses.service.ai.AiTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * AIテキスト生成のモック実装。
 * 他のプロバイダが選択されなかったときに使用される（フォールバック）。
 * テスト・開発環境でAIコスト無しに機能を動作させるためのダミー実装。
 */
@Slf4j
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")
public class MockAiTextServiceImpl implements AiTextService {

    private static final String MOCK_RESPONSE = """
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
                {"name": "Java", "proficiency": "上級", "experienceYears": 5},
                {"name": "Spring Boot", "proficiency": "上級", "experienceYears": 4},
                {"name": "MySQL", "proficiency": "中級", "experienceYears": 3}
              ],
              "careers": [
                {
                  "periodFrom": "2020-04-01",
                  "periodTo": "2024-03-31",
                  "projectName": "金融システム刷新プロジェクト",
                  "clientIndustry": "金融",
                  "role": "バックエンドエンジニア",
                  "techStack": "Java, Spring Boot, Oracle DB",
                  "description": "銀行の基幹システム刷新に携わり、APIサーバーの設計・実装を担当。",
                  "teamSize": 10
                }
              ],
              "warnings": []
            }
            """;

    @Override
    public String generate(String prompt) {
        log.debug("MockAiTextServiceImpl: モック応答を返します（promptLength={})", prompt.length());
        return MOCK_RESPONSE;
    }
}
