package com.ses.service.survey;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * サーベイサービス（T092 / engineer-self-service-portal-v2 B2）。
 * 集計: 未回答は平均値の母数へ含めない（design §6.1）。最低回答数config未満のsegmentは非表示（design §5）。
 * confidential（質問・コメント）はHR/管理者のみ可視。回答はconsent必須・template versionを固定する。
 */
public interface SurveyService {

    // ---- template（HR/管理者） ----
    Page<TemplateDto> pageTemplates(long current, long size);

    TemplateDto createTemplate(String templateKey, String title, String description,
                               List<QuestionDef> questions);

    TemplateDto updateTemplate(Long id, String title, String description, List<QuestionDef> questions);

    // ---- campaign（HR/管理者） ----
    Page<CampaignDto> pageCampaigns(long current, long size);

    CampaignDto createCampaign(Long templateId, String title, LocalDate periodFrom, LocalDate periodTo);

    /** DRAFT→ACTIVE。配信対象（アカウント連携済み要員）へSURVEY_CAMPAIGN通知を発行する。 */
    CampaignDto activateCampaign(Long id);

    CampaignDto closeCampaign(Long id);

    // ---- 本人（要員） ----
    List<CampaignDto> myActiveCampaigns(Long engineerId);

    MyCampaignDetail myCampaignDetail(Long engineerId, Long campaignId);

    /** 回答を一括upsertする。consent必須。template_versionは回答時点のversionに固定する。 */
    void submitAnswers(Long engineerId, Long campaignId, boolean consent, List<AnswerInput> answers);

    // ---- 集計（HR/管理者/マネージャー） ----
    AggregateResult aggregate(Long campaignId);

    /** 個別回答一覧（HR/管理者のみ。confidential commentもHR/管理者のみ）。 */
    List<ResponseView> responses(Long campaignId);

    record QuestionDef(String key, String text, String type, boolean confidential) {
    }

    record TemplateDto(Long id, String templateKey, String title, String description, String status,
                       List<QuestionDef> questions, Integer version) {
    }

    record CampaignDto(Long id, Long templateId, String title, LocalDate periodFrom, LocalDate periodTo,
                       String status, Integer templateVersion) {
    }

    record MyCampaignDetail(Long campaignId, String title, LocalDate periodFrom, LocalDate periodTo,
                            List<QuestionDef> questions, Map<String, Integer> answers,
                            Integer templateVersion, boolean consentFlag) {
    }

    record AnswerInput(String questionKey, Integer answerValue, String comment, String commentVisibility) {
    }

    record AggregateResult(Long campaignId, String title, List<QuestionAggregate> questions,
                           List<OrganizationSegment> segments, int minAnswers) {
    }

    record QuestionAggregate(String questionKey, String text, boolean confidential, boolean hidden,
                             long answeredCount, java.math.BigDecimal average, long commentCount) {
    }

    record OrganizationSegment(Long organizationId, String organizationName, boolean hidden, long answeredEngineers,
                               List<QuestionAggregate> questions) {
    }

    record ResponseView(Long responseId, Long engineerId, String engineerName, String questionKey,
                        Integer answerValue, String comment, String commentVisibility, boolean consentFlag) {
    }
}
