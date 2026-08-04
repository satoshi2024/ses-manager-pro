package com.ses.service.approval;

import com.ses.dto.approval.ApprovalDelegationRequest;
import com.ses.dto.approval.ApprovalDelegationView;
import com.ses.dto.approval.ApprovalRoutePreviewRequest;
import com.ses.dto.approval.ApprovalRoutePreviewView;
import com.ses.dto.approval.ApprovalRouteSaveRequest;
import com.ses.dto.approval.ApprovalResponsibilitySaveRequest;
import com.ses.dto.approval.ApprovalResponsibilityView;
import com.ses.dto.approval.ApprovalRouteView;

import java.time.LocalDate;
import java.util.List;

/** 管理者向けroute version/代理設定管理。 */
public interface ApprovalAdministrationService {
    List<ApprovalRouteView> listRoutes(LocalDate asOf);
    ApprovalRouteView createRouteVersion(ApprovalRouteSaveRequest request, Long actorId);
    ApprovalRoutePreviewView preview(ApprovalRoutePreviewRequest request);
    List<ApprovalResponsibilityView> listResponsibilities(LocalDate asOf);
    ApprovalResponsibilityView createResponsibility(ApprovalResponsibilitySaveRequest request, Long actorId);
    void deleteResponsibility(Long id);
    List<ApprovalDelegationView> listDelegations();
    ApprovalDelegationView createDelegation(ApprovalDelegationRequest request, Long actorId);
    void deleteDelegation(Long id);
}
