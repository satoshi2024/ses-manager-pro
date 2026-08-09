package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * rule共通処理（severity・message解決・enabled判定）。
 * severityは既存4 ruleと同じ "warning" を維持する。
 */
@Component
public abstract class AbstractComplianceRule implements ComplianceRule {

    public static final String SEVERITY_WARNING = "warning";

    protected final SystemConfigService systemConfigService;
    protected final MessageSource messageSource;

    protected AbstractComplianceRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        this.systemConfigService = systemConfigService;
        this.messageSource = messageSource;
    }

    /** rule有効化config key。既定はcodeから生成（SETTLEMENT_MISMATCHは既存keyを維持するためoverride）。 */
    protected String enabledKey() {
        return "compliance.rule." + code().toLowerCase().replace('_', '-') + ".enabled";
    }

    @Override
    public List<ComplianceFinding> evaluate(Contract contract, ComplianceRuleContext context) {
        if (!"true".equalsIgnoreCase(systemConfigService.getString(enabledKey(), "true"))) {
            return List.of();
        }
        if (!appliesTo(contract)) {
            return List.of();
        }
        return evaluateEnabled(contract, context);
    }

    protected abstract List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context);

    protected int configInt(String key, int defaultValue) {
        return systemConfigService.getInt(key, defaultValue);
    }

    protected String configString(String key, String defaultValue) {
        return systemConfigService.getString(key, defaultValue);
    }

    protected ComplianceFinding finding(ComplianceRuleContext context, String code, Long contractId, String fingerprint,
                                        LocalDate dueDate, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        String messageKey = "compliance.finding." + toCamelCase(code);
        String message = messageSource.getMessage(messageKey, args, messageKey, locale);
        ComplianceFinding finding = new ComplianceFinding(code, SEVERITY_WARNING, message, contractId,
                fingerprint, dueDate);
        return finding;
    }

    /** TIER_EXCEEDED → tierExceeded（既存message key "compliance.finding.tierExceeded" と一致させる） */
    private String toCamelCase(String code) {
        String[] parts = code.toLowerCase().split("_");
        StringBuilder builder = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
