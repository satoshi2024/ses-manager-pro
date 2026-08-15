package com.ses.dto.staffing;

import com.ses.entity.AllocationPlan;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 配置カード/タイムライン項目（T077 A1）。
 * entityは画面へ直接公開せず、表示名と承認状態を付与したDTOを返す。
 */
public class AllocationCardDto {

    private Long id;
    private Long engineerId;
    private String engineerName;
    private Long positionId;
    private String positionNo;
    private String roleName;
    private Long projectId;
    private String projectName;
    private String allocationType;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal allocationPercent;
    private String status;
    private Long sourceContractId;
    private String exceptionReason;
    private Long approvalRequestId;
    /** 過配賦例外の承認状態（approved/pending/rejected/withdrawn/null）。 */
    private String approvalStatus;
    /** 楽観ロック（更新時に返却）。 */
    private Integer version;

    public AllocationCardDto() {
    }

    public AllocationCardDto(AllocationPlan plan) {
        this.id = plan.getId();
        this.engineerId = plan.getEngineerId();
        this.positionId = plan.getPositionId();
        this.allocationType = plan.getAllocationType();
        this.startDate = plan.getStartDate();
        this.endDate = plan.getEndDate();
        this.allocationPercent = plan.getAllocationPercent();
        this.status = plan.getStatus();
        this.sourceContractId = plan.getSourceContractId();
        this.exceptionReason = plan.getExceptionReason();
        this.approvalRequestId = plan.getApprovalRequestId();
        this.version = plan.getVersion();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEngineerId() {
        return engineerId;
    }

    public void setEngineerId(Long engineerId) {
        this.engineerId = engineerId;
    }

    public String getEngineerName() {
        return engineerName;
    }

    public void setEngineerName(String engineerName) {
        this.engineerName = engineerName;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public String getPositionNo() {
        return positionNo;
    }

    public void setPositionNo(String positionNo) {
        this.positionNo = positionNo;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getAllocationType() {
        return allocationType;
    }

    public void setAllocationType(String allocationType) {
        this.allocationType = allocationType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getAllocationPercent() {
        return allocationPercent;
    }

    public void setAllocationPercent(BigDecimal allocationPercent) {
        this.allocationPercent = allocationPercent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSourceContractId() {
        return sourceContractId;
    }

    public void setSourceContractId(Long sourceContractId) {
        this.sourceContractId = sourceContractId;
    }

    public String getExceptionReason() {
        return exceptionReason;
    }

    public void setExceptionReason(String exceptionReason) {
        this.exceptionReason = exceptionReason;
    }

    public Long getApprovalRequestId() {
        return approvalRequestId;
    }

    public void setApprovalRequestId(Long approvalRequestId) {
        this.approvalRequestId = approvalRequestId;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
