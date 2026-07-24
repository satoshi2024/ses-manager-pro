package com.ses.common.constant;

/**
 * スキルシート様式（テンプレート）定数インターフェース
 * 様式IDと既定の様式定義を一元管理する
 */
public interface SkillSheetConstants {

    // ========== システム設定キー ==========

    /** 様式定義（JSON配列）を保持する m_system_config のキー */
    String CONFIG_KEY_TEMPLATES = "skillsheet.templates";

    // ========== 様式ID ==========

    /** 様式: 自社標準（全項目を出力する） */
    String TEMPLATE_STANDARD = "STANDARD";

    /** 様式: 簡易（最寄駅・チーム規模を省く） */
    String TEMPLATE_SIMPLE = "SIMPLE";

    /** 様式: 客先A（自社標準＋備考欄） */
    String TEMPLATE_CLIENT_A = "CLIENT_A";

    /**
     * m_system_config に様式定義が無い場合の既定値。
     * V49__add_skillsheet_templates_config.sql の初期値と同じ内容を保つこと。
     */
    String DEFAULT_TEMPLATES_JSON =
            "[{\"id\":\"STANDARD\",\"name\":\"自社標準\"},"
            + "{\"id\":\"SIMPLE\",\"name\":\"簡易\"},"
            + "{\"id\":\"CLIENT_A\",\"name\":\"客先A様式\"}]";
}
