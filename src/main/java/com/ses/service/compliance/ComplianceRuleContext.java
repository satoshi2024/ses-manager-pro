package com.ses.service.compliance;

import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.DocumentDelivery;
import com.ses.entity.WorkRecordDaily;

import java.util.List;

/**
 * rule評価の読み取り専用context（純データ）。
 * 既存4 ruleに必要なBP tier、新ruleに必要なprofile/delivery/work record/契約chainを保持する。
 * organizationUnitは就業事業所マスタ（m_workplace.organization_unit）からengineが解決する。
 * サービス依存は各ruleが自前で注入する（AbstractComplianceRule）。
 */
public class ComplianceRuleContext {

    /** 既存4 rule用のBP支払階層（maxLayer） */
    private final int maxLayer;
    private final ContractComplianceProfile profile;
    private final List<DocumentDelivery> deliveries;
    private final List<WorkRecordDaily> workRecordDailies;
    private final List<LimitationDateCalculator.ChainContract> contractChain;
    private final String organizationUnit;

    private ComplianceRuleContext(Builder builder) {
        this.maxLayer = builder.maxLayer;
        this.profile = builder.profile;
        this.deliveries = builder.deliveries;
        this.workRecordDailies = builder.workRecordDailies;
        this.contractChain = builder.contractChain;
        this.organizationUnit = builder.organizationUnit;
    }

    public int maxLayer() {
        return maxLayer;
    }

    public ContractComplianceProfile profile() {
        return profile;
    }

    public List<DocumentDelivery> deliveries() {
        return deliveries;
    }

    public List<WorkRecordDaily> workRecordDailies() {
        return workRecordDailies;
    }

    public List<LimitationDateCalculator.ChainContract> contractChain() {
        return contractChain;
    }

    public String organizationUnit() {
        return organizationUnit;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxLayer;
        private ContractComplianceProfile profile;
        private List<DocumentDelivery> deliveries = List.of();
        private List<WorkRecordDaily> workRecordDailies = List.of();
        private List<LimitationDateCalculator.ChainContract> contractChain = List.of();
        private String organizationUnit;

        public Builder maxLayer(int maxLayer) {
            this.maxLayer = maxLayer;
            return this;
        }

        public Builder profile(ContractComplianceProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder deliveries(List<DocumentDelivery> deliveries) {
            this.deliveries = deliveries == null ? List.of() : deliveries;
            return this;
        }

        public Builder workRecordDailies(List<WorkRecordDaily> workRecordDailies) {
            this.workRecordDailies = workRecordDailies == null ? List.of() : workRecordDailies;
            return this;
        }

        public Builder contractChain(List<LimitationDateCalculator.ChainContract> contractChain) {
            this.contractChain = contractChain == null ? List.of() : contractChain;
            return this;
        }

        public Builder organizationUnit(String organizationUnit) {
            this.organizationUnit = organizationUnit;
            return this;
        }

        public ComplianceRuleContext build() {
            return new ComplianceRuleContext(this);
        }
    }
}
