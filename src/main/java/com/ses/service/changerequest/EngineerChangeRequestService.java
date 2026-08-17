package com.ses.service.changerequest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * プロフィール/スキル変更申請サービス（T089 / engineer-self-service-portal-v2 A1）。
 * 状態機械: 下書き→申請中→承認済→反映済 / 取下げ（design §6.3）。
 * 承認は既存approval engine（requestType=profile.change / skill.change / career.change、targetType=CHANGE_REQUEST）。
 * 承認前はEngineer masterを一切変更しない（R5）。反映は最終承認時1回だけ、状態CAS+master fingerprint再検証で行う。
 * payloadは request_type ごとのDTO allowlist を通過した値のみを保存する（任意JSON→entity反映の禁止）。
 * 本人scopeは engineer-account link から解決し、リクエストのengineerIdを信用しない。
 */
public interface EngineerChangeRequestService {

    String TYPE_PROFILE = "profile.change";
    String TYPE_SKILL = "skill.change";
    String TYPE_CAREER = "career.change";

    String STATUS_DRAFT = "下書き";
    String STATUS_APPLIED = "申請中";
    String STATUS_APPROVED = "承認済";
    String STATUS_REFLECTED = "反映済";
    String STATUS_WITHDRAWN = "取下げ";

    /** 本人の一覧（自分分のみ）。 */
    Page<ChangeRequestDto> pageOwn(Long engineerId, String status, long current, long size);

    ChangeRequestDto detailOwn(Long engineerId, Long id);

    /** 下書き作成。payloadはtype別allowlistを通過した値のみを保存する。 */
    ChangeRequestDto createDraft(Long engineerId, String requestType, Map<String, Object> payload);

    /** 下書き→申請中。approval engineへ申請しapproval_request_idを記録（同一transaction）。 */
    ChangeRequestDto submit(Long engineerId, Long id);

    /** 申請中→取下げ（approval in_review時のみ）。 */
    ChangeRequestDto withdraw(Long engineerId, Long id);

    /** 差戻し/競合（approval status=returned/conflict）からの再申請。 */
    ChangeRequestDto resubmit(Long engineerId, Long id);

    /** 管理一覧。HR/管理者=全件、マネージャー=組織scope配下。 */
    Page<ChangeRequestDto> pageManagement(String engineerName, String requestType, String status,
                                          long current, long size);

    ChangeRequestDto detailManagement(Long id);

    /** 本人レスポンス（自分のプロフィール・skills・careers・担当営業・現在契約の公開条件）。 */
    MyProfileView myProfile(Long engineerId);

    /** スキルシートpreview（公開項目のみ。fingerprint/確認状態を含む）。 */
    SkillSheetPreview skillSheetPreview(Long engineerId);

    /** previewで確認したfingerprintを文書台帳(SKILL_SHEET)へ固定しt_document_linkへ確認日時を記録する。 */
    SkillSheetConfirmResult confirmSkillSheet(Long engineerId, String fingerprint);

    /** 本人の変更申請件数（my dashboard表示用）。 */
    long pendingChangeRequestCount(Long engineerId);

    record ChangeRequestDto(Long id, String requestType, String status, String payloadJson, String diffJson,
                            Long approvalRequestId, String approvalStatus, java.time.LocalDateTime appliedAt,
                            boolean unappliedApproved, java.time.LocalDateTime createdAt, String engineerName) {
    }

    record MyProfileView(Long engineerId, String fullName, String fullNameKana, String initialName, String gender,
                         java.time.LocalDate birthDate, String nationality, String nearestStation, String prefecture,
                         String railwayCompany, String employmentType, String status,
                         java.math.BigDecimal expectedUnitPrice, java.time.LocalDate availableDate,
                         Integer experienceYears, String japaneseLevel, String resumeSummary,
                         java.util.List<com.ses.dto.engineer.EngineerSkillDetailDto> skills,
                         java.util.List<com.ses.entity.EngineerCareer> careers,
                         String primarySalesUserName, Long primarySalesUserId,
                         java.util.List<PublicContract> contracts, long pendingChangeRequests) {
    }

    record PublicContract(String contractNo, String projectName, String customerName,
                          java.time.LocalDate startDate, java.time.LocalDate endDate,
                          String contractType, String status, String jobDescription, String workLocation) {
    }

    record SkillSheetPreview(String engineerName, String nearestStation, String prefecture, String railwayCompany,
                             java.time.LocalDate availableDate, String japaneseLevel, String resumeSummary,
                             java.util.List<SkillSheetSkillRow> skills, java.util.List<SkillSheetCareerRow> careers,
                             String fingerprint, java.time.LocalDateTime confirmedAt, String confirmedVersion,
                             boolean current) {
    }

    record SkillSheetSkillRow(String skillName, String category, String proficiency, Integer experienceYears) {
    }

    record SkillSheetCareerRow(java.time.LocalDate periodFrom, java.time.LocalDate periodTo, String projectName,
                               String clientIndustry, String role, String description, String techStack, Integer teamSize) {
    }

    record SkillSheetConfirmResult(String fingerprint, java.time.LocalDateTime confirmedAt) {
    }
}
