package com.ses.dto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 契約画面の登録・更新 payload。
 * <p>
 * {@link com.ses.entity.Contract} の {@code FieldStrategy.ALWAYS} 列のうち、本 DTO が運ぶものは
 * JSON にキーが来た時点で「payload 出現」とみなし、明示 null ならクリアを許可する。
 * 本 DTO に存在しない ALWAYS 列（{@code positionId} / {@code renewalDecision}）は画面保存では
 * 触れないため、更新時はサービス側で行ロック後の旧値から回填する（CON-01）。
 */
@Getter
@Setter
public class ContractSaveDto {

    /**
     * 本 DTO が運ぶ ALWAYS フィールド名。コントローラ経由の更新ではこれらを「payload 出現」と扱う。
     * ここに無い ALWAYS 列は未出現として old から回填する。
     */
    public static final Set<String> SAVE_PAYLOAD_ALWAYS_FIELDS = Set.of(
            "endDate",
            "settlementHoursMin",
            "settlementHoursMax",
            "fractionRule",
            "salesUserId",
            "commissionBaseType",
            "commissionRate",
            "acceptanceExemptionReason");

    /** Contract エンティティ上の ALWAYS フィールド名一覧（回填対象の照合用）。 */
    public static final Set<String> ALL_ALWAYS_FIELDS = Set.of(
            "positionId",
            "endDate",
            "settlementHoursMin",
            "settlementHoursMax",
            "fractionRule",
            "salesUserId",
            "commissionBaseType",
            "commissionRate",
            "acceptanceExemptionReason",
            "renewalDecision");

    private Long id;
    private String contractNo;
    private Long proposalId;

    @NotNull(message = "要員は必須です")
    private Long engineerId;

    @NotNull(message = "案件は必須です")
    private Long projectId;

    @NotNull(message = "顧客は必須です")
    private Long customerId;

    private String contractType;

    @NotNull(message = "契約開始日は必須です")
    private LocalDate startDate;

    private LocalDate endDate;

    @NotNull(message = "売上単価は必須です")
    @PositiveOrZero(message = "売上単価は0以上で入力してください")
    private BigDecimal sellingPrice;

    @NotNull(message = "原価は必須です")
    @PositiveOrZero(message = "原価は0以上で入力してください")
    private BigDecimal costPrice;
    /** 明示指定時のみ。NULLは要員既定原価部門へフォールバックする。 */
    private Long costCenterId;

    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private String fractionRule;
    private Integer autoRenew;
    private String status;
    private String remarks;
    private Boolean directCommandFlag;
    private Long salesUserId;
    private String commissionBaseType;

    @PositiveOrZero(message = "インセンティブ率は0以上で入力してください")
    private BigDecimal commissionRate;

    private Long renewedFromContractId;
    private Long quotationId;
    /** 検収要否（false=検収不要契約。理由必須）。 */
    private Boolean acceptanceRequired;
    /** 検収不要理由（acceptanceRequired=false時は必須。R3.3）。 */
    private String acceptanceExemptionReason;

    /**
     * Jackson が setter を呼んだ ALWAYS フィールド（キー出現＝明示 null もクリア可）。
     * 未出現キーはここに入らない。
     */
    @JsonIgnore
    private final Set<String> presentAlwaysFields = new HashSet<>();

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        presentAlwaysFields.add("endDate");
    }

    public void setSettlementHoursMin(BigDecimal settlementHoursMin) {
        this.settlementHoursMin = settlementHoursMin;
        presentAlwaysFields.add("settlementHoursMin");
    }

    public void setSettlementHoursMax(BigDecimal settlementHoursMax) {
        this.settlementHoursMax = settlementHoursMax;
        presentAlwaysFields.add("settlementHoursMax");
    }

    public void setFractionRule(String fractionRule) {
        this.fractionRule = fractionRule;
        presentAlwaysFields.add("fractionRule");
    }

    public void setSalesUserId(Long salesUserId) {
        this.salesUserId = salesUserId;
        presentAlwaysFields.add("salesUserId");
    }

    public void setCommissionBaseType(String commissionBaseType) {
        this.commissionBaseType = commissionBaseType;
        presentAlwaysFields.add("commissionBaseType");
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
        presentAlwaysFields.add("commissionRate");
    }

    public void setAcceptanceExemptionReason(String acceptanceExemptionReason) {
        this.acceptanceExemptionReason = acceptanceExemptionReason;
        presentAlwaysFields.add("acceptanceExemptionReason");
    }

    /** JSON に出現した ALWAYS フィールド名（変更不可ビュー）。 */
    @JsonIgnore
    public Set<String> getPresentAlwaysFields() {
        return Collections.unmodifiableSet(presentAlwaysFields);
    }

    @AssertTrue(message = "契約終了日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @AssertTrue(message = "精算基準時間の上限は下限以上を指定してください")
    public boolean isSettlementHoursRangeValid() {
        return settlementHoursMin == null || settlementHoursMax == null
                || settlementHoursMin.compareTo(settlementHoursMax) <= 0;
    }
}
