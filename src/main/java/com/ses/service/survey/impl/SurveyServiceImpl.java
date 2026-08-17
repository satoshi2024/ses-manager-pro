package com.ses.service.survey.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SurveyCampaign;
import com.ses.entity.SurveyResponse;
import com.ses.entity.SurveyTemplate;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SurveyCampaignMapper;
import com.ses.mapper.SurveyResponseMapper;
import com.ses.mapper.SurveyTemplateMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import com.ses.service.survey.SurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * サーベイサービス実装（T092 / design §5/§6.1）。
 * 未回答（answer_value IS NULL）は平均値の母数から除外する。最低回答数未満のsegmentは非表示。
 * comment_visibility=CONFIDENTIALのコメントは集計へ含めず、個別表示もHR/管理者のみ。
 */
@Service
@RequiredArgsConstructor
public class SurveyServiceImpl implements SurveyService {

    private static final int MAX_QUESTIONS = 20;
    private static final Set<String> QUESTION_TYPES = Set.of("SCALE1_5", "COMMENT", "SCALE1_5_COMMENT");
    private static final String MIN_ANSWERS_KEY = "survey.min-answers";

    private final SurveyTemplateMapper templateMapper;
    private final SurveyCampaignMapper campaignMapper;
    private final SurveyResponseMapper responseMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerAccountLinkService accountLinkService;
    private final EngineerAccountLinkMapper accountLinkMapper;
    private final OrganizationUnitMapper organizationUnitMapper;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    // ----------------------------------------------------------------
    // template
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<TemplateDto> pageTemplates(long current, long size) {
        Page<SurveyTemplate> page = templateMapper.selectPage(PageUtils.safePage(current, size),
                new LambdaQueryWrapper<SurveyTemplate>().orderByDesc(SurveyTemplate::getId));
        Page<TemplateDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toTemplateDto).toList());
        return result;
    }

    @Override
    @Transactional
    public TemplateDto createTemplate(String templateKey, String title, String description,
                                      List<QuestionDef> questions) {
        requireManagementRole();
        validateTemplate(templateKey, title, questions);
        SurveyTemplate template = SurveyTemplate.builder()
                .templateKey(templateKey.trim())
                .title(title.trim())
                .description(description == null ? null : description.trim())
                .questionsJson(writeJson(questions))
                .status("DRAFT")
                .version(0)
                .createdBy(SecurityUtils.currentUserId())
                .build();
        templateMapper.insert(template);
        return toTemplateDto(template);
    }

    @Override
    @Transactional
    public TemplateDto updateTemplate(Long id, String title, String description, List<QuestionDef> questions) {
        requireManagementRole();
        SurveyTemplate template = requireTemplate(id);
        if (!"DRAFT".equals(template.getStatus())) {
            throw BusinessException.of(400, "error.survey.templateNotDraft");
        }
        validateTemplate(template.getTemplateKey(), title, questions);
        int version = template.getVersion() == null ? 0 : template.getVersion();
        int updated = templateMapper.update(null, new UpdateWrapper<SurveyTemplate>()
                .eq("id", id).eq("version", version)
                .set("title", title.trim())
                .set("description", description == null ? null : description.trim())
                .set("questions_json", writeJson(questions))
                .set("version", version + 1)
                .set("updated_at", java.time.LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toTemplateDto(requireTemplate(id));
    }

    // ----------------------------------------------------------------
    // campaign
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<CampaignDto> pageCampaigns(long current, long size) {
        Page<SurveyCampaign> page = campaignMapper.selectPage(PageUtils.safePage(current, size),
                new LambdaQueryWrapper<SurveyCampaign>().orderByDesc(SurveyCampaign::getId));
        Page<CampaignDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(c -> new CampaignDto(c.getId(), c.getTemplateId(), c.getTitle(), c.getPeriodFrom(),
                        c.getPeriodTo(), c.getStatus(), templateVersionOf(c.getTemplateId())))
                .toList());
        return result;
    }

    @Override
    @Transactional
    public CampaignDto createCampaign(Long templateId, String title, LocalDate periodFrom, LocalDate periodTo) {
        requireManagementRole();
        SurveyTemplate template = requireTemplate(templateId);
        if (!"ACTIVE".equals(template.getStatus()) && !"DRAFT".equals(template.getStatus())) {
            throw BusinessException.of(400, "error.survey.templateNotDraft");
        }
        if (title == null || title.isBlank()) {
            throw BusinessException.of(400, "error.survey.titleRequired");
        }
        if (periodFrom != null && periodTo != null && periodFrom.isAfter(periodTo)) {
            throw BusinessException.of(400, "error.survey.invalidPeriod");
        }
        SurveyCampaign campaign = SurveyCampaign.builder()
                .templateId(templateId)
                .title(title.trim())
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .templateSnapshotJson(template.getQuestionsJson())
                .status("DRAFT")
                .createdBy(SecurityUtils.currentUserId())
                .build();
        campaignMapper.insert(campaign);
        return toCampaignDto(campaign);
    }

    @Override
    @Transactional
    public CampaignDto activateCampaign(Long id) {
        requireManagementRole();
        SurveyCampaign campaign = requireCampaign(id);
        if (!"DRAFT".equals(campaign.getStatus())) {
            throw BusinessException.of(400, "error.survey.invalidTransition",
                    campaign.getStatus(), "ACTIVE");
        }
        SurveyTemplate template = requireTemplate(campaign.getTemplateId());
        int updated = campaignMapper.update(null, new UpdateWrapper<SurveyCampaign>()
                .eq("id", id).eq("status", "DRAFT")
                .set("status", "ACTIVE")
                .set("template_snapshot_json", template.getQuestionsJson())
                .set("updated_at", java.time.LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        notifyCampaign(campaign);
        return toCampaignDto(requireCampaign(id));
    }

    @Override
    @Transactional
    public CampaignDto closeCampaign(Long id) {
        requireManagementRole();
        SurveyCampaign campaign = requireCampaign(id);
        if (!"ACTIVE".equals(campaign.getStatus())) {
            throw BusinessException.of(400, "error.survey.invalidTransition",
                    campaign.getStatus(), "CLOSED");
        }
        int updated = campaignMapper.update(null, new UpdateWrapper<SurveyCampaign>()
                .eq("id", id).eq("status", "ACTIVE")
                .set("status", "CLOSED")
                .set("updated_at", java.time.LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toCampaignDto(requireCampaign(id));
    }

    // ----------------------------------------------------------------
    // 本人
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CampaignDto> myActiveCampaigns(Long engineerId) {
        LocalDate today = LocalDate.now(clock);
        return campaignMapper.selectList(new LambdaQueryWrapper<SurveyCampaign>()
                        .eq(SurveyCampaign::getStatus, "ACTIVE")
                        .and(w -> w.isNull(SurveyCampaign::getPeriodFrom).or().le(SurveyCampaign::getPeriodFrom, today))
                        .and(w -> w.isNull(SurveyCampaign::getPeriodTo).or().ge(SurveyCampaign::getPeriodTo, today))
                        .orderByDesc(SurveyCampaign::getId))
                .stream()
                .map(c -> new CampaignDto(c.getId(), c.getTemplateId(), c.getTitle(), c.getPeriodFrom(),
                        c.getPeriodTo(), c.getStatus(), templateVersionOf(c.getTemplateId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MyCampaignDetail myCampaignDetail(Long engineerId, Long campaignId) {
        SurveyCampaign campaign = requireCampaign(campaignId);
        if (!"ACTIVE".equals(campaign.getStatus())) {
            throw BusinessException.of(400, "error.survey.notActive");
        }
        LocalDate today = LocalDate.now(clock);
        if ((campaign.getPeriodFrom() != null && today.isBefore(campaign.getPeriodFrom()))
                || (campaign.getPeriodTo() != null && today.isAfter(campaign.getPeriodTo()))) {
            throw BusinessException.of(400, "error.survey.outOfPeriod");
        }
        String questionsJson = (campaign.getTemplateSnapshotJson() != null && !campaign.getTemplateSnapshotJson().isBlank())
                ? campaign.getTemplateSnapshotJson()
                : requireTemplate(campaign.getTemplateId()).getQuestionsJson();
        List<QuestionDef> questions = readQuestions(questionsJson);
        Map<String, Integer> answers = responseMapper.selectList(new LambdaQueryWrapper<SurveyResponse>()
                        .eq(SurveyResponse::getCampaignId, campaignId)
                        .eq(SurveyResponse::getEngineerId, engineerId))
                .stream().collect(Collectors.toMap(SurveyResponse::getQuestionKey,
                        r -> r.getAnswerValue() == null ? -1 : r.getAnswerValue()));
        boolean consent = responseMapper.selectCount(new LambdaQueryWrapper<SurveyResponse>()
                .eq(SurveyResponse::getCampaignId, campaignId)
                .eq(SurveyResponse::getEngineerId, engineerId)
                .eq(SurveyResponse::getConsentFlag, 1)) > 0;
        return new MyCampaignDetail(campaignId, campaign.getTitle(), campaign.getPeriodFrom(), campaign.getPeriodTo(),
                questions, answers, templateVersionOf(campaign.getTemplateId()), consent);
    }

    @Override
    @Transactional
    public void submitAnswers(Long engineerId, Long campaignId, boolean consent, List<AnswerInput> answers) {
        SurveyCampaign campaign = requireCampaign(campaignId);
        if (!"ACTIVE".equals(campaign.getStatus())) {
            throw BusinessException.of(400, "error.survey.notActive");
        }
        LocalDate today = LocalDate.now(clock);
        if ((campaign.getPeriodFrom() != null && today.isBefore(campaign.getPeriodFrom()))
                || (campaign.getPeriodTo() != null && today.isAfter(campaign.getPeriodTo()))) {
            throw BusinessException.of(400, "error.survey.outOfPeriod");
        }
        if (!consent) {
            throw BusinessException.of(400, "error.survey.consentRequired");
        }
        if (answers == null || answers.isEmpty()) {
            throw BusinessException.of(400, "error.survey.answersRequired");
        }
        String questionsJson = (campaign.getTemplateSnapshotJson() != null && !campaign.getTemplateSnapshotJson().isBlank())
                ? campaign.getTemplateSnapshotJson()
                : requireTemplate(campaign.getTemplateId()).getQuestionsJson();
        Map<String, QuestionDef> defs = readQuestions(questionsJson).stream()
                .collect(Collectors.toMap(QuestionDef::key, Function.identity()));
        Integer version = templateVersionOf(campaign.getTemplateId());
        int v = version == null ? 0 : version;
        for (AnswerInput input : answers) {
            QuestionDef def = defs.get(input.questionKey());
            if (def == null) {
                throw BusinessException.of(400, "error.survey.unknownQuestion", input.questionKey());
            }
            validateAnswer(def, input);
            SurveyResponse existing = responseMapper.selectOne(new LambdaQueryWrapper<SurveyResponse>()
                    .eq(SurveyResponse::getCampaignId, campaignId)
                    .eq(SurveyResponse::getEngineerId, engineerId)
                    .eq(SurveyResponse::getQuestionKey, input.questionKey()));
            String visibility = switch (def.type()) {
                case "COMMENT", "SCALE1_5_COMMENT" -> normalizeVisibility(input.commentVisibility(), def.confidential());
                default -> "PUBLIC";
            };
            if (existing == null) {
                responseMapper.insert(SurveyResponse.builder()
                        .campaignId(campaignId)
                        .engineerId(engineerId)
                        .questionKey(input.questionKey())
                        .answerValue(input.answerValue())
                        .comment(trimToNull(input.comment()))
                        .commentVisibility(visibility)
                        .consentFlag(1)
                        .templateVersion(v)
                        .build());
            } else {
                responseMapper.update(null, new UpdateWrapper<SurveyResponse>()
                        .eq("id", existing.getId())
                        .set("answer_value", input.answerValue())
                        .set("comment", trimToNull(input.comment()))
                        .set("comment_visibility", visibility)
                        .set("consent_flag", 1)
                        .set("template_version", v)
                        .set("updated_at", java.time.LocalDateTime.now(clock)));
            }
        }
    }

    // ----------------------------------------------------------------
    // 集計
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AggregateResult aggregate(Long campaignId) {
        SurveyCampaign campaign = requireCampaign(campaignId);
        String questionsJson = (campaign.getTemplateSnapshotJson() != null && !campaign.getTemplateSnapshotJson().isBlank())
                ? campaign.getTemplateSnapshotJson()
                : requireTemplate(campaign.getTemplateId()).getQuestionsJson();
        List<QuestionDef> questions = readQuestions(questionsJson);
        int minAnswers = Math.max(1, systemConfigService.getInt(MIN_ANSWERS_KEY, 3));
        boolean confidentialVisible = isHrOrAdmin();

        Set<Long> visibleEngineerIds = visibleEngineers();
        if (visibleEngineerIds != null && visibleEngineerIds.isEmpty()) {
            return new AggregateResult(campaignId, campaign.getTitle(), List.of(), List.of(), minAnswers,
                    new RetentionRiskSummary(0, 0, null, List.of(), true));
        }

        LambdaQueryWrapper<SurveyResponse> responseQuery = new LambdaQueryWrapper<SurveyResponse>()
                .eq(SurveyResponse::getCampaignId, campaignId);
        if (visibleEngineerIds != null) {
            responseQuery.in(SurveyResponse::getEngineerId, visibleEngineerIds);
        }
        List<SurveyResponse> responses = responseMapper.selectList(responseQuery);

        Map<Long, Long> orgOfEngineer = new java.util.HashMap<>();
        responses.stream().map(SurveyResponse::getEngineerId).distinct().forEach(id -> {
            Engineer e = engineerMapper.selectById(id);
            orgOfEngineer.put(id, e == null || e.getOrganizationId() == null ? 0L : e.getOrganizationId());
        });

        List<QuestionAggregate> overall = aggregateQuestions(questions, responses, confidentialVisible, minAnswers);

        // 組織segmentごとの集計（マネージャーは配下のみ表示。design §5の匿名閾値適用）
        Map<Long, List<SurveyResponse>> byOrg = responses.stream()
                .collect(Collectors.groupingBy(r -> orgOfEngineer.getOrDefault(r.getEngineerId(), 0L)));
        List<OrganizationSegment> segments = byOrg.entrySet().stream()
                .map(e -> {
                    long answeredEngineers = e.getValue().stream().map(SurveyResponse::getEngineerId).distinct().count();
                    boolean hidden = answeredEngineers < minAnswers;
                    return new OrganizationSegment(e.getKey(), organizationName(e.getKey()), hidden, answeredEngineers,
                            hidden ? List.of() : aggregateQuestions(questions, e.getValue(), confidentialVisible, minAnswers));
                })
                .sorted(Comparator.comparing(OrganizationSegment::organizationId))
                .toList();

        // リテンションリスク集計（R1-P1-10）
        RetentionRiskSummary retentionRisk = computeRetentionRisk(questions, responses, minAnswers, confidentialVisible);

        return new AggregateResult(campaignId, campaign.getTitle(), overall, segments, minAnswers, retentionRisk);
    }

    private RetentionRiskSummary computeRetentionRisk(List<QuestionDef> questions, List<SurveyResponse> responses,
                                                      int minAnswers, boolean confidentialVisible) {
        long answeredEngineers = responses.stream().map(SurveyResponse::getEngineerId).distinct().count();
        if (answeredEngineers < minAnswers) {
            return new RetentionRiskSummary(answeredEngineers, 0, null, List.of(), true);
        }
        List<RiskFactor> riskFactors = new ArrayList<>();
        List<Integer> allScores = new ArrayList<>();
        Map<Long, List<Integer>> engineerScores = new java.util.HashMap<>();

        for (QuestionDef q : questions) {
            if (q.confidential() && !confidentialVisible) {
                continue;
            }
            if ("SCALE1_5".equals(q.type()) || "SCALE1_5_COMMENT".equals(q.type())) {
                List<SurveyResponse> qResponses = responses.stream()
                        .filter(r -> r.getQuestionKey().equals(q.key()) && r.getAnswerValue() != null)
                        .toList();
                if (!qResponses.isEmpty()) {
                    double avg = qResponses.stream().mapToInt(SurveyResponse::getAnswerValue).average().orElse(0.0);
                    BigDecimal qAvg = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
                    if (avg < 3.0) {
                        riskFactors.add(new RiskFactor(q.key(), q.text(), qAvg, "スコア平均が基準値(3.0)未満です"));
                    }
                    for (SurveyResponse r : qResponses) {
                        allScores.add(r.getAnswerValue());
                        engineerScores.computeIfAbsent(r.getEngineerId(), k -> new ArrayList<>()).add(r.getAnswerValue());
                    }
                }
            }
        }
        long atRiskCount = engineerScores.values().stream()
                .filter(scores -> scores.stream().mapToInt(Integer::intValue).average().orElse(5.0) < 2.5)
                .count();
        BigDecimal overallAvg = allScores.isEmpty() ? null
                : BigDecimal.valueOf(allScores.stream().mapToInt(Integer::intValue).average().orElse(0.0))
                .setScale(2, RoundingMode.HALF_UP);

        return new RetentionRiskSummary(answeredEngineers, atRiskCount, overallAvg, riskFactors, false);
    }

    private List<QuestionAggregate> aggregateQuestions(List<QuestionDef> questions, List<SurveyResponse> responses,
                                                       boolean confidentialVisible, int minAnswers) {
        List<QuestionAggregate> result = new ArrayList<>();
        for (QuestionDef def : questions) {
            boolean confidential = def.confidential();
            if (confidential && !confidentialVisible) {
                result.add(new QuestionAggregate(def.key(), def.text(), true, true, 0, null, 0));
                continue;
            }
            List<SurveyResponse> answered = responses.stream()
                    .filter(r -> r.getQuestionKey().equals(def.key()) && r.getAnswerValue() != null)
                    .toList();
            long commentCount = responses.stream()
                    .filter(r -> r.getQuestionKey().equals(def.key())
                            && r.getComment() != null && !r.getComment().isBlank()
                            && (confidentialVisible || !"CONFIDENTIAL".equals(r.getCommentVisibility())))
                    .count();
            boolean hidden = answered.size() < minAnswers;
            BigDecimal average = null;
            if (!hidden && !answered.isEmpty()) {
                average = BigDecimal.valueOf(answered.stream().mapToInt(SurveyResponse::getAnswerValue).sum())
                        .divide(BigDecimal.valueOf(answered.size()), 2, RoundingMode.HALF_UP);
            }
            result.add(new QuestionAggregate(def.key(), def.text(), confidential, hidden,
                    answered.size(), average, commentCount));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseView> responses(Long campaignId) {
        if (!isHrOrAdmin()) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        requireCampaign(campaignId);
        List<SurveyResponse> responses = responseMapper.selectList(new LambdaQueryWrapper<SurveyResponse>()
                .eq(SurveyResponse::getCampaignId, campaignId)
                .orderByAsc(SurveyResponse::getEngineerId));
        Map<Long, String> names = responses.stream().map(SurveyResponse::getEngineerId).distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> {
                            Engineer e = engineerMapper.selectById(id);
                            return e == null ? "" : (e.getFullName() == null ? "" : e.getFullName());
                        }));
        return responses.stream().map(r -> new ResponseView(r.getId(), r.getEngineerId(),
                        names.getOrDefault(r.getEngineerId(), ""), r.getQuestionKey(), r.getAnswerValue(),
                        r.getComment(), r.getCommentVisibility(), r.getConsentFlag() != null && r.getConsentFlag() == 1))
                .toList();
    }

    // ----------------------------------------------------------------
    // 内部
    // ----------------------------------------------------------------

    private void validateTemplate(String templateKey, String title, List<QuestionDef> questions) {
        if (templateKey == null || templateKey.isBlank() || templateKey.length() > 50) {
            throw BusinessException.of(400, "error.survey.templateKeyRequired");
        }
        if (title == null || title.isBlank()) {
            throw BusinessException.of(400, "error.survey.titleRequired");
        }
        if (questions == null || questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            throw BusinessException.of(400, "error.survey.questionsRequired");
        }
        Set<String> keys = new java.util.HashSet<>();
        for (QuestionDef q : questions) {
            if (q == null || q.key() == null || q.key().isBlank() || q.key().length() > 50
                    || q.text() == null || q.text().isBlank()
                    || !QUESTION_TYPES.contains(q.type())) {
                throw BusinessException.of(400, "error.survey.invalidQuestion");
            }
            if (!keys.add(q.key())) {
                throw BusinessException.of(400, "error.survey.duplicateQuestionKey", q.key());
            }
        }
    }

    private void validateAnswer(QuestionDef def, AnswerInput input) {
        boolean scale = "SCALE1_5".equals(def.type()) || "SCALE1_5_COMMENT".equals(def.type());
        boolean comment = "COMMENT".equals(def.type()) || "SCALE1_5_COMMENT".equals(def.type());
        if (scale && (input.answerValue() == null || input.answerValue() < 1 || input.answerValue() > 5)) {
            throw BusinessException.of(400, "error.survey.invalidAnswer");
        }
        if (!scale && input.answerValue() != null) {
            throw BusinessException.of(400, "error.survey.invalidAnswer");
        }
        if (comment && input.comment() != null && input.comment().length() > 1000) {
            throw BusinessException.of(400, "error.survey.commentTooLong");
        }
        if (!comment && input.comment() != null) {
            throw BusinessException.of(400, "error.survey.invalidComment");
        }
    }

    private String normalizeVisibility(String visibility, boolean confidentialQuestion) {
        if (confidentialQuestion || "CONFIDENTIAL".equals(visibility)) {
            return "CONFIDENTIAL";
        }
        return "PUBLIC";
    }

    private boolean isHrOrAdmin() {
        String role = SecurityUtils.currentRole();
        return "HR".equals(role) || "管理者".equals(role);
    }

    /** template/campaignの作成・配信・締切はHR/管理者のみ（design §6.2。controllerに加えてserviceでもfail-closed）。 */
    private void requireManagementRole() {
        if (!isHrOrAdmin()) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    /** マネージャーは配下要員の回答だけを集計対象にする（HR/管理者は全件）。 */
    private Set<Long> visibleEngineers() {
        String role = SecurityUtils.currentRole();
        Set<Long> all = accountLinkMapper.selectList(new LambdaQueryWrapper<EngineerAccountLink>())
                .stream().map(EngineerAccountLink::getEngineerId).collect(Collectors.toSet());
        if ("HR".equals(role) || "管理者".equals(role)) {
            return all;
        }
        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) {
                return all;
            }
            Set<Long> scoped = organizationScopeService.allowedEngineerIds(LocalDate.now(clock));
            return scoped == null ? Set.of() : scoped;
        }
        return Set.of();
    }

    private String organizationName(Long organizationId) {
        if (organizationId == null || organizationId == 0L) {
            return "未所属";
        }
        com.ses.entity.OrganizationUnit org = organizationUnitMapper.selectById(organizationId);
        return org == null ? "組織#" + organizationId : org.getName();
    }

    private void notifyCampaign(SurveyCampaign campaign) {
        List<EngineerAccountLink> links = accountLinkMapper.selectList(new LambdaQueryWrapper<EngineerAccountLink>());
        for (EngineerAccountLink link : links) {
            String message = "[\"notification.msg.SURVEY_CAMPAIGN\", \"" + campaign.getTitle() + "\"]";
            notificationService.publishToUser(link.getSysUserId(), "SURVEY_CAMPAIGN", "サーベイの回答をお願いします",
                    message, "/my/surveys", "survey-campaign:" + campaign.getId(), "mySurveys");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer templateVersionOf(Long templateId) {
        SurveyTemplate template = templateMapper.selectById(templateId);
        return template == null ? null : template.getVersion();
    }

    private SurveyTemplate requireTemplate(Long id) {
        SurveyTemplate template = id == null ? null : templateMapper.selectById(id);
        if (template == null) {
            throw BusinessException.of(404, "error.survey.templateNotFound");
        }
        return template;
    }

    private SurveyCampaign requireCampaign(Long id) {
        SurveyCampaign campaign = id == null ? null : campaignMapper.selectById(id);
        if (campaign == null) {
            throw BusinessException.of(404, "error.survey.campaignNotFound");
        }
        return campaign;
    }

    private TemplateDto toTemplateDto(SurveyTemplate template) {
        return new TemplateDto(template.getId(), template.getTemplateKey(), template.getTitle(),
                template.getDescription(), template.getStatus(), readQuestions(template.getQuestionsJson()),
                template.getVersion());
    }

    private CampaignDto toCampaignDto(SurveyCampaign campaign) {
        return new CampaignDto(campaign.getId(), campaign.getTemplateId(), campaign.getTitle(),
                campaign.getPeriodFrom(), campaign.getPeriodTo(), campaign.getStatus(),
                templateVersionOf(campaign.getTemplateId()));
    }

    private List<QuestionDef> readQuestions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("サーベイJSONのシリアライズに失敗しました", e);
        }
    }
}
