package com.ses.dto.project;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.ses.entity.ProjectSkill;

@Data
public class ProjectSaveDto {
    private Long id;
    
    @NotBlank(message = "案件名は必須です")
    private String projectName;

    @NotNull(message = "顧客は必須です")
    private Long customerId;

    private String commercialFlow;
    private String description;
    private Integer requiredCount;
    private BigDecimal unitPriceMin;
    private BigDecimal unitPriceMax;
    private String workLocation;
    private String remoteType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String priority;
    private String remarks;
    
    private List<ProjectSkill> skills;

    @AssertTrue(message = "単価上限は下限以上の値を指定してください")
    public boolean isUnitPriceRangeValid() {
        return unitPriceMin == null || unitPriceMax == null
                || unitPriceMin.compareTo(unitPriceMax) <= 0;
    }

    @AssertTrue(message = "終了予定日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
