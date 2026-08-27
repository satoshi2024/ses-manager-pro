package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;

import java.time.LocalDate;
import java.util.List;

/**
 * ライフサイクル案件サービス
 */
public interface LifecycleCaseService extends IService<LifecycleCase> {

    LifecycleCaseDto createCase(Long applicantUserId, CreateLifecycleCaseCommand cmd);

    LifecycleCaseDto getCaseDetail(Long caseId, SysUser currentUser);

    List<LifecycleCaseDto> listCases(String lifecycleType,
                                     String status,
                                     Long engineerId,
                                     LocalDate fromDate,
                                     LocalDate toDate,
                                     SysUser currentUser);

    void holdCase(Long caseId, Long userId, String reason);

    void resumeCase(Long caseId, Long userId);

    void completeCase(Long caseId, Long userId);

    void cancelCase(Long caseId, Long userId, String reason);

    ResignationGateResultDto evaluateResignationGate(Long caseId, SysUser currentUser);
}
