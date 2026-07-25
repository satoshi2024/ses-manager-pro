-- スキルシートの様式（テンプレート）定義。
-- 値は JSON 配列で、id が SkillSheetGenerator の描画分岐と対応する。
-- 既定値は com.ses.common.constant.SkillSheetConstants.DEFAULT_TEMPLATES_JSON と同内容に保つこと。
INSERT IGNORE INTO m_system_config (config_key, config_value, description)
VALUES ('skillsheet.templates',
        '[{"id":"STANDARD","name":"自社標準"},{"id":"SIMPLE","name":"簡易"},{"id":"CLIENT_A","name":"客先A様式"}]',
        'スキルシートの様式定義(JSON形式)');
