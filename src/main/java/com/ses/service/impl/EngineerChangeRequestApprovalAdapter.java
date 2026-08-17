package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.EngineerSkill;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerCareerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.changerequest.EngineerChangeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 変更申請を既存approval engineへ接続するadapter（T089 / design §6.3）。
 * requestType=profile.change / skill.change / career.change、targetType=CHANGE_REQUEST。
 * 最終承認時だけ反映transactionを実行する。承認前はEngineer masterを一切変更しない（R5）。
 * targetVersionにはmaster fingerprint（承認対象masterが外部更新されると不一致になる）を使用し、
 * 競合時はengineがconflictへ遷移して再申請を要求する（自動マージしない）。
 * 反映は1回だけ（状態CAS+version CAS。既に反映済ならno-opでなくエラー）。
 */
@Component
@RequiredArgsConstructor
public class EngineerChangeRequestApprovalAdapter implements ApprovalTargetAdapter {

    public static final String TYPE_PROFILE = EngineerChangeRequestService.TYPE_PROFILE;
    public static final String TYPE_SKILL = EngineerChangeRequestService.TYPE_SKILL;
    public static final String TYPE_CAREER = EngineerChangeRequestService.TYPE_CAREER;

    private static final Set<String> PROFILE_ALLOWED = Set.of(
            "fullName", "fullNameKana", "initialName", "gender", "birthDate", "nationality",
            "nearestStation", "prefecture", "railwayCompany", "expectedUnitPrice",
            "availableDate", "experienceYears", "japaneseLevel", "resumeSummary",
            "email", "phone");

    private final EngineerChangeRequestMapper changeRequestMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final EngineerCareerMapper engineerCareerMapper;
    private final EngineerService engineerService;
    private final EngineerSkillService engineerSkillService;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    private final NotificationService notificationService;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public String requestType() {
        return TYPE_PROFILE;
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of(TYPE_PROFILE, TYPE_SKILL, TYPE_CAREER);
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        EngineerChangeRequest request = require(targetId);
        if (!EngineerChangeRequestService.STATUS_DRAFT.equals(request.getStatus())
                && !EngineerChangeRequestService.STATUS_APPLIED.equals(request.getStatus())) {
            throw BusinessException.of(400, "error.changeRequest.invalidTransition",
                    request.getStatus(), EngineerChangeRequestService.STATUS_APPLIED);
        }
        Map<String, Object> payload = readJson(request.getPayloadJson());
        Map<String, Object> diff = readJson(request.getDiffJson());
        Engineer engineer = engineerOf(request);
        return new ApprovalSnapshot(fingerprint(request, engineer), null,
                engineer == null ? null : engineer.getOrganizationId(), payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        EngineerChangeRequest request = require(targetId);
        return fingerprint(request, engineerOf(request));
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        if (snapshot == null || snapshot.targetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyApproved(ApprovalRequest request) {
        if (request == null || request.getTargetId() == null || request.getTargetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        EngineerChangeRequest change = require(request.getTargetId());
        requireVersion(request, change);

        // 反映は承認済み（申請中）からのみ。既に反映済/取下げはfail-closed。
        if (!EngineerChangeRequestService.STATUS_APPLIED.equals(change.getStatus())) {
            throw BusinessException.of(400, "error.changeRequest.invalidTransition",
                    change.getStatus(), EngineerChangeRequestService.STATUS_REFLECTED);
        }

        // master更新（承認前は一切変更しない R5）。承認済→反映は1回だけのtransaction。
        switch (change.getRequestType()) {
            case TYPE_PROFILE -> applyProfile(change);
            case TYPE_SKILL -> applySkills(change);
            case TYPE_CAREER -> applyCareers(change);
            default -> throw BusinessException.of(400, "error.changeRequest.invalidType");
        }

        // 反映済へ状態CAS（version CASで二重反映を防ぐ。design §6.3「反映済 終端」）。
        int version = value(change.getVersion());
        int updated = changeRequestMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<EngineerChangeRequest>()
                .eq("id", change.getId())
                .eq("status", EngineerChangeRequestService.STATUS_APPLIED)
                .eq("version", version)
                .set("status", EngineerChangeRequestService.STATUS_REFLECTED)
                .set("applied_at", java.time.LocalDateTime.now())
                .set("version", version + 1)
                .set("updated_at", java.time.LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }

        notifyApplied(change);
    }

    /** 本人へ反映通知（approval engineのAPPROVAL_*はmenuKey=approvalで要員不可視のため別途発行）。 */
    private void notifyApplied(EngineerChangeRequest change) {
        Long sysUserId = linkedUserId(change.getEngineerId());
        if (sysUserId == null) {
            return;
        }
        String prefix = switch (change.getRequestType()) {
            case TYPE_SKILL -> "スキル";
            case TYPE_CAREER -> "職務経歴";
            default -> "プロフィール";
        };
        String message = "[\"notification.msg.CHANGE_REQUEST_APPLIED\", \"" + prefix + "\"]";
        notificationService.publishToUser(sysUserId, "CHANGE_REQUEST_APPLIED", "変更申請が反映されました",
                message, "/my/profile", "change-request-applied:" + change.getId(), "myProfile");
    }

    private void applyProfile(EngineerChangeRequest change) {
        Engineer engineer = engineerOf(change);
        Map<String, Object> payload = readJson(change.getPayloadJson());
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!PROFILE_ALLOWED.contains(e.getKey())) {
                continue; // validatePayloadで拒否済み。防御的にスキップ（fail-safe）。
            }
            if ("email".equals(e.getKey())) {
                String email = str(e.getValue());
                if (email != null && !email.isBlank()) {
                    Long userId = linkedUserId(change.getEngineerId());
                    if (userId != null) {
                        com.ses.entity.SysUser u = sysUserMapper.selectById(userId);
                        if (u != null) {
                            u.setEmail(email);
                            sysUserMapper.updateById(u);
                        }
                    }
                }
            } else {
                applyField(engineer, e.getKey(), e.getValue());
            }
        }
        engineerService.updateWithStatusGuard(engineer);
    }

    private void applyField(Engineer engineer, String field, Object value) {
        try {
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (java.lang.reflect.Method m : Engineer.class.getMethods()) {
                if (m.getName().equals(setter) && m.getParameterCount() == 1) {
                    Object converted = convert(m.getParameterTypes()[0], value);
                    m.invoke(engineer, converted);
                    return;
                }
            }
        } catch (Exception ignored) {
            // 未対応fieldは無視（validatePayloadで拒否済み）
        }
    }

    private Object convert(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == java.math.BigDecimal.class) {
            return new java.math.BigDecimal(String.valueOf(value));
        }
        if (type == Integer.class || type == int.class) {
            return ((Number) value).intValue();
        }
        if (type == java.time.LocalDate.class) {
            return java.time.LocalDate.parse(String.valueOf(value));
        }
        return value;
    }

    private void applySkills(EngineerChangeRequest change) {
        Map<String, Object> payload = readJson(change.getPayloadJson());
        Object raw = payload.get("skills");
        if (!(raw instanceof List<?> list)) {
            throw BusinessException.of(400, "error.changeRequest.skillsRequired");
        }
        List<EngineerSkill> skills = list.stream().map(item -> {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) item;
            EngineerSkill skill = new EngineerSkill();
            skill.setEngineerId(change.getEngineerId());
            skill.setSkillId(Long.valueOf(String.valueOf(m.get("skillId"))));
            skill.setProficiency(m.get("proficiency") == null ? null : String.valueOf(m.get("proficiency")));
            skill.setExperienceYears(m.get("experienceYears") == null ? null
                    : Integer.valueOf(String.valueOf(m.get("experienceYears"))));
            return skill;
        }).toList();
        engineerSkillService.replaceSkills(change.getEngineerId(), new java.util.ArrayList<>(skills));
    }

    private void applyCareers(EngineerChangeRequest change) {
        Map<String, Object> payload = readJson(change.getPayloadJson());
        Object raw = payload.get("careers");
        if (!(raw instanceof List<?> list)) {
            throw BusinessException.of(400, "error.changeRequest.careersRequired");
        }
        engineerCareerMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerCareer>()
                .eq(EngineerCareer::getEngineerId, change.getEngineerId()));
        List<EngineerCareer> careers = list.stream().map(item -> {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) item;
            EngineerCareer career = new EngineerCareer();
            career.setEngineerId(change.getEngineerId());
            career.setPeriodFrom(asDate(m.get("periodFrom")));
            career.setPeriodTo(asDate(m.get("periodTo")));
            career.setProjectName(str(m.get("projectName")));
            career.setClientIndustry(str(m.get("clientIndustry")));
            career.setRole(str(m.get("role")));
            career.setDescription(str(m.get("description")));
            career.setTechStack(str(m.get("techStack")));
            career.setTeamSize(m.get("teamSize") == null ? null : Integer.valueOf(String.valueOf(m.get("teamSize"))));
            return career;
        }).toList();
        for (EngineerCareer career : careers) {
            engineerCareerMapper.insert(career);
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private java.time.LocalDate asDate(Object value) {
        return value == null ? null : java.time.LocalDate.parse(String.valueOf(value));
    }

    private Long linkedUserId(Long engineerId) {
        com.ses.entity.EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        return link == null ? null : link.getSysUserId();
    }

    /**
     * 承認対象masterのfingerprint。profile/skill/careerが外部（HR等）から同時更新されると
     * 値が変わるため、approval engineのtargetVersion照合で「master側の同時更新」を検出する
     * （design §6.3「承認済→反映済: 状態CAS＋対象entityのversion再検証」）。
     */
    private long fingerprint(EngineerChangeRequest change, Engineer engineer) {
        if (engineer == null) {
            return 0L;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(engineer.getId()).append('|').append(engineer.getUpdatedAt());
        switch (change.getRequestType()) {
            case TYPE_PROFILE -> {
                for (String field : PROFILE_ALLOWED.stream().sorted().toList()) {
                    sb.append('|').append(field).append('=').append(fieldValue(engineer, field));
                }
            }
            case TYPE_SKILL -> {
                List<EngineerSkill> rows = engineerSkillMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSkill>()
                                .eq(EngineerSkill::getEngineerId, engineer.getId())
                                .orderByAsc(EngineerSkill::getSkillId));
                for (EngineerSkill s : rows) {
                    sb.append("|s").append(s.getSkillId()).append(':')
                            .append(s.getProficiency() == null ? "" : s.getProficiency()).append(':')
                            .append(s.getExperienceYears());
                }
            }
            case TYPE_CAREER -> {
                List<EngineerCareer> rows = engineerCareerMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerCareer>()
                                .eq(EngineerCareer::getEngineerId, engineer.getId())
                                .orderByAsc(EngineerCareer::getId));
                for (EngineerCareer c : rows) {
                    sb.append("|c").append(c.getId()).append(':')
                            .append(c.getPeriodFrom()).append(':')
                            .append(c.getPeriodTo()).append(':')
                            .append(c.getProjectName() == null ? "" : c.getProjectName()).append(':')
                            .append(c.getClientIndustry() == null ? "" : c.getClientIndustry()).append(':')
                            .append(c.getRole() == null ? "" : c.getRole()).append(':')
                            .append(c.getDescription() == null ? "" : c.getDescription()).append(':')
                            .append(c.getTechStack() == null ? "" : c.getTechStack()).append(':')
                            .append(c.getTeamSize() == null ? "" : c.getTeamSize());
                }
            }
            default -> {
            }
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // SHA-256先頭8byteをlongへ（32bitハッシュの衝突回避のため）。
            long result = 0L;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (digest[i] & 0xFFL);
            }
            return result;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private Object fieldValue(Engineer engineer, String field) {
        try {
            var method = Engineer.class.getMethod("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
            return method.invoke(engineer);
        } catch (Exception e) {
            return null;
        }
    }

    private EngineerChangeRequest require(Long targetId) {
        EngineerChangeRequest change = targetId == null ? null : changeRequestMapper.selectById(targetId);
        if (change == null) {
            throw BusinessException.of(404, "error.changeRequest.notFound");
        }
        return change;
    }

    private void requireVersion(ApprovalRequest request, EngineerChangeRequest change) {
        long current = fingerprint(change, engineerOf(change));
        if (!Objects.equals(request.getTargetVersion(), current)) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private Engineer engineerOf(EngineerChangeRequest change) {
        return change.getEngineerId() == null ? null : engineerMapper.selectById(change.getEngineerId());
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw BusinessException.of(400, "error.changeRequest.payloadRequired");
        }
    }
}
