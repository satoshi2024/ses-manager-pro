package com.ses.common.i18n;

import java.util.HashMap;
import java.util.Map;

public class EnumMappings {
    public static final Map<String, Map<String, String>> GROUPS = new HashMap<>();

    static {
        Map<String, String> userRole = new HashMap<>();
        userRole.put("管理者", "admin");
        userRole.put("営業", "sales");
        userRole.put("HR", "hr");
        userRole.put("マネージャー", "manager");
        GROUPS.put("userRole", userRole);

        Map<String, String> customerTrustLevel = new HashMap<>();
        customerTrustLevel.put("S", "S");
        customerTrustLevel.put("A", "A");
        customerTrustLevel.put("B", "B");
        customerTrustLevel.put("C", "C");
        GROUPS.put("customerTrustLevel", customerTrustLevel);

        Map<String, String> gender = new HashMap<>();
        gender.put("男性", "male");
        gender.put("女性", "female");
        GROUPS.put("gender", gender);

        Map<String, String> employmentType = new HashMap<>();
        employmentType.put("正社員", "regular");
        employmentType.put("契約社員", "contract");
        employmentType.put("BP", "bp");
        GROUPS.put("employmentType", employmentType);

        Map<String, String> engineerStatus = new HashMap<>();
        engineerStatus.put("稼動中", "active");
        engineerStatus.put("退場予定", "retiring");
        engineerStatus.put("Bench", "bench");
        engineerStatus.put("提案中", "proposing");
        GROUPS.put("engineerStatus", engineerStatus);

        Map<String, String> skillCategory = new HashMap<>();
        skillCategory.put("言語", "lang");
        skillCategory.put("FW", "fw");
        skillCategory.put("DB", "db");
        skillCategory.put("クラウド", "cloud");
        skillCategory.put("OS", "os");
        skillCategory.put("ツール", "tool");
        skillCategory.put("その他", "other");
        GROUPS.put("skillCategory", skillCategory);

        Map<String, String> proficiency = new HashMap<>();
        proficiency.put("初級", "beginner");
        proficiency.put("中級", "intermediate");
        proficiency.put("上級", "advanced");
        GROUPS.put("proficiency", proficiency);

        Map<String, String> remoteType = new HashMap<>();
        remoteType.put("フル出社", "none");
        remoteType.put("フルリモート", "full");
        remoteType.put("ハイブリッド", "hybrid");
        GROUPS.put("remoteType", remoteType);

        Map<String, String> projectStatus = new HashMap<>();
        projectStatus.put("募集中", "open");
        projectStatus.put("選考中", "selecting");
        projectStatus.put("充足", "filled");
        projectStatus.put("クローズ", "closed");
        GROUPS.put("projectStatus", projectStatus);

        Map<String, String> priority = new HashMap<>();
        priority.put("通常", "normal");
        priority.put("急募", "urgent");
        priority.put("高利益", "high_profit");
        GROUPS.put("priority", priority);

        Map<String, String> proposalStatus = new HashMap<>();
        proposalStatus.put("書類選考中", "doc_screening");
        proposalStatus.put("一次面接", "first_interview");
        proposalStatus.put("二次面接", "second_interview");
        proposalStatus.put("結果待ち", "waiting");
        proposalStatus.put("成約", "won");
        proposalStatus.put("見送り", "lost");
        GROUPS.put("proposalStatus", proposalStatus);

        Map<String, String> contractType = new HashMap<>();
        contractType.put("準委任", "ses");
        contractType.put("請負", "contract");
        contractType.put("派遣", "dispatch");
        GROUPS.put("contractType", contractType);

        Map<String, String> contractStatus = new HashMap<>();
        contractStatus.put("準備中", "draft");
        contractStatus.put("稼動中", "active");
        contractStatus.put("終了", "completed");
        contractStatus.put("解約", "cancelled");
        GROUPS.put("contractStatus", contractStatus);

        Map<String, String> aiRequestType = new HashMap<>();
        aiRequestType.put("マッチング", "matching");
        aiRequestType.put("スキルシート", "skillsheet");
        aiRequestType.put("営業メール", "sales_email");
        GROUPS.put("aiRequestType", aiRequestType);

        Map<String, String> emailTemplateType = new HashMap<>();
        emailTemplateType.put("提案", "proposal");
        emailTemplateType.put("面接依頼", "interview");
        emailTemplateType.put("お礼", "thanks");
        emailTemplateType.put("フォローアップ", "followup");
        emailTemplateType.put("その他", "other");
        GROUPS.put("emailTemplateType", emailTemplateType);
    }
}
