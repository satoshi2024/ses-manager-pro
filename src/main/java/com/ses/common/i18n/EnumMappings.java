package com.ses.common.i18n;

import java.util.HashMap;
import java.util.Map;

public class EnumMappings {
    // Map<group, Map<dbValue, code>>
    public static final Map<String, Map<String, String>> GROUPS = new HashMap<>();

    static {
        // Engineer Status
        Map<String, String> engineerStatus = new HashMap<>();
        engineerStatus.put("稼動中", "active");
        engineerStatus.put("待機", "bench");
        engineerStatus.put("営業中", "sales");
        engineerStatus.put("離任予定", "retiring");
        engineerStatus.put("退職", "resigned");
        GROUPS.put("engineerStatus", engineerStatus);

        // Project Status
        Map<String, String> projectStatus = new HashMap<>();
        projectStatus.put("提案中", "proposing");
        projectStatus.put("稼動中", "active");
        projectStatus.put("終了", "completed");
        projectStatus.put("保留", "onhold");
        projectStatus.put("失注", "lost");
        GROUPS.put("projectStatus", projectStatus);

        // Proposal Status
        Map<String, String> proposalStatus = new HashMap<>();
        proposalStatus.put("未提案", "unproposed");
        proposalStatus.put("面談調整中", "arranging");
        proposalStatus.put("面談済", "interviewed");
        proposalStatus.put("結果待ち", "waiting");
        proposalStatus.put("成約", "won");
        proposalStatus.put("見送り", "lost");
        GROUPS.put("proposalStatus", proposalStatus);

        // Contract Status
        Map<String, String> contractStatus = new HashMap<>();
        contractStatus.put("作成中", "draft");
        contractStatus.put("締結済", "signed");
        contractStatus.put("終了", "completed");
        GROUPS.put("contractStatus", contractStatus);

        // User Role
        Map<String, String> userRole = new HashMap<>();
        userRole.put("管理者", "admin");
        userRole.put("営業", "sales");
        userRole.put("事務", "clerk");
        GROUPS.put("userRole", userRole);

        // Contract Type
        Map<String, String> contractType = new HashMap<>();
        contractType.put("SES", "ses");
        contractType.put("受託", "contract");
        contractType.put("派遣", "dispatch");
        GROUPS.put("contractType", contractType);

        // Gender
        Map<String, String> gender = new HashMap<>();
        gender.put("男性", "male");
        gender.put("女性", "female");
        gender.put("その他", "other");
        GROUPS.put("gender", gender);

        // Employment Type
        Map<String, String> employmentType = new HashMap<>();
        employmentType.put("正社員", "regular");
        employmentType.put("契約社員", "contract");
        employmentType.put("個人事業主", "freelance");
        employmentType.put("BP", "bp");
        GROUPS.put("employmentType", employmentType);

        // Priority
        Map<String, String> priority = new HashMap<>();
        priority.put("高", "high");
        priority.put("中", "medium");
        priority.put("低", "low");
        GROUPS.put("priority", priority);

        // Remote Type
        Map<String, String> remoteType = new HashMap<>();
        remoteType.put("フルリモート", "full");
        remoteType.put("一部リモート", "partial");
        remoteType.put("出社", "none");
        GROUPS.put("remoteType", remoteType);

        // Proficiency
        Map<String, String> proficiency = new HashMap<>();
        proficiency.put("上級", "advanced");
        proficiency.put("中級", "intermediate");
        proficiency.put("初級", "beginner");
        GROUPS.put("proficiency", proficiency);
    }
}
