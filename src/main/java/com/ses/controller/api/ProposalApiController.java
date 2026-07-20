package com.ses.controller.api;

import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.MailService;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ses.common.result.ApiResult;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Proposal;
import com.ses.dto.common.StatusChangeRequest;
import jakarta.validation.Valid;

/**
 * 提案APIコントローラー
 */
@RestController
@RequestMapping("/api/proposals")
@RequiredArgsConstructor
public class ProposalApiController {

    private final ProposalService proposalService;
    private final EngineerService engineerService;
    private final ProjectService projectService;
    private final CustomerService customerService;
    private final MailService mailService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    /**
     * かんばんリスト取得
     *
     * @return 提案かんばんDTOリスト
     */
    @GetMapping("/{id}")
    public ApiResult<com.ses.dto.proposal.ProposalQuotationPresetDto> getPreset(@PathVariable Long id) {
        Proposal p = proposalService.getById(id);
        if (p == null) {
            throw BusinessException.of(404, "error.proposal.notFound");
        }
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedProposalIds();
            if (!allowed.contains(p.getId())) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
        }
        com.ses.dto.proposal.ProposalQuotationPresetDto dto = new com.ses.dto.proposal.ProposalQuotationPresetDto();
        dto.setId(p.getId());
        dto.setEngineerId(p.getEngineerId());
        dto.setProjectId(p.getProjectId());
        dto.setProposedUnitPrice(p.getProposedUnitPrice());
        Project proj = projectService.getById(p.getProjectId());
        if (proj != null) {
            dto.setCustomerId(proj.getCustomerId());
            dto.setProjectName(proj.getProjectName());
        }
        return ApiResult.success(dto);
    }

    @GetMapping("/kanban")
    public ApiResult<List<ProposalKanbanDto>> getKanbanList() {
        List<ProposalKanbanDto> list = proposalService.getKanbanList();
        // データスコープ: 営業ロール制限時は自分∪担当要員の提案のみ（カンバンは非ページングのためJava側で絞る）。
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedProposalIds();
            list = list.stream().filter(p -> allowed.contains(p.getId())).collect(java.util.stream.Collectors.toList());
        }
        return ApiResult.success(list);
    }

    /**
     * ステータス変更
     *
     * @param id 提案ID
     * @param request body (ステータスを含む)
     * @return 結果
     */
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        if (dataScopeService.isScoped() && !dataScopeService.allowedProposalIds().contains(id)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        proposalService.changeStatus(id, request.getStatus());
        return ApiResult.success(true);
    }

    /**
     * 新規提案
     */
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody Proposal proposal) {
        com.ses.common.util.EntityProtectUtil.protectForCreate(proposal);
        // 参照する要員・案件が担当スコープ内であることを検証する（担当外ID参照の登録IDOR防止 / R3R-32）。
        if (dataScopeService.isScoped()) {
            if (proposal.getEngineerId() != null) dataScopeService.assertAllowedEngineer(proposal.getEngineerId());
            if (proposal.getProjectId() != null) dataScopeService.assertAllowedProject(proposal.getProjectId());
        }
        return ApiResult.success(proposalService.save(proposal));
    }

    /**
     * 提案メール送信。
     * テンプレートIDと宛先を受け取り、提案から変数（要員名・案件名・顧客名・提案単価）を
     * 解決してメールを送信する。宛先未指定時は顧客担当者メールを使う。
     *
     * @param id  提案ID
     * @param req body: { templateId, to(任意) }
     */
    @PostMapping("/{id}/send-mail")
    public ApiResult<MailDispatchResult> sendMail(@PathVariable Long id, @RequestBody Map<String, String> req) {
        if (dataScopeService.isScoped() && !dataScopeService.allowedProposalIds().contains(id)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        Proposal proposal = proposalService.getById(id);
        if (proposal == null) {
            throw BusinessException.of("error.proposal.notFound");
        }
        String templateIdStr = req.get("templateId");
        if (!StringUtils.hasText(templateIdStr)) {
            throw BusinessException.of("error.proposal.templateNotSelected");
        }
        Long templateId = Long.valueOf(templateIdStr);

        Engineer engineer = engineerService.getById(proposal.getEngineerId());
        Project project = projectService.getById(proposal.getProjectId());
        Customer customer = project != null ? customerService.getById(project.getCustomerId()) : null;

        Map<String, String> params = new HashMap<>();
        params.put("engineerName", engineer != null ? engineer.getFullName() : "");
        params.put("projectName", project != null ? project.getProjectName() : "");
        params.put("customerName", customer != null ? customer.getCompanyName() : "");
        params.put("contactPerson", customer != null && customer.getContactPerson() != null ? customer.getContactPerson() : "");
        params.put("unitPrice", proposal.getProposedUnitPrice() != null ? proposal.getProposedUnitPrice().toPlainString() : "");

        String to = req.get("to");
        if (!StringUtils.hasText(to)) {
            to = customer != null ? customer.getContactEmail() : null;
        }
        if (!StringUtils.hasText(to)) {
            throw BusinessException.of("error.proposal.emailNotSpecified");
        }

        return ApiResult.success(mailService.sendWithTemplate(templateId, params, to));
    }
}






