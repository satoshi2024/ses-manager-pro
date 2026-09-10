package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.ProjectService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;

/**
 * 案件サービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {

    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final CustomerMapper customerMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.ProjectSkillService> projectSkillServiceProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.LegalEntityContextService legalEntityContextService;

    /** generic service.saveも顧客の権威法人を通すため、取込/変換の迂回保存を許さない。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Project entity) {
        bindLegalEntity(entity, entity == null ? null : entity.getCustomerId(), null);
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(Project entity) {
        if (entity == null || entity.getId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        Project old = getById(entity.getId());
        if (old == null) throw BusinessException.of(404, "error.scope.notFound");
        Long customerId = entity.getCustomerId() == null ? old.getCustomerId() : entity.getCustomerId();
        bindLegalEntity(entity, customerId, old);
        return super.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Long projectId = Long.valueOf(id.toString());
        Project current = getById(projectId);
        if (current == null) return false;
        bindLegalEntity(current, current.getCustomerId(), current);
        long contracts = contractMapper.selectCountForTenant(
                new LambdaQueryWrapper<Contract>().eq(Contract::getProjectId, projectId),
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
        if (contracts > 0) {
            throw BusinessException.of("error.project.delete.hasContract");
        }
        long openProposals = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getProjectId, projectId)
                .notIn(Proposal::getStatus, List.of("成約", "見送り")));
        if (openProposals > 0) {
            throw BusinessException.of("error.project.delete.hasProposal");
        }
        return super.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveProjectWithSkills(com.ses.dto.project.ProjectSaveDto dto) {
        Project project = new Project();
        org.springframework.beans.BeanUtils.copyProperties(dto, project);
        bindLegalEntity(project, dto.getCustomerId(), null);
        this.save(project);
        dto.setId(project.getId());
        if (dto.getSkills() != null) {
            replaceProjectSkills(dto.getId(), dto.getSkills(), "案件作成");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProjectWithSkills(com.ses.dto.project.ProjectSaveDto dto) {
        Project old = this.getById(dto.getId());
        if (old != null && old.getCustomerId() != null && !old.getCustomerId().equals(dto.getCustomerId())) {
            long contracts = contractMapper.selectCountForTenant(
                    new LambdaQueryWrapper<Contract>().eq(Contract::getProjectId, dto.getId()),
                    com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
            if (contracts > 0) {
                throw BusinessException.of(409, "error.project.update.hasContract");
            }
            long openProposals = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                    .eq(Proposal::getProjectId, dto.getId())
                    .notIn(Proposal::getStatus, List.of("成約", "見送り")));
            if (openProposals > 0) {
                throw BusinessException.of(409, "error.project.update.hasProposal");
            }
        }
        Project project = new Project();
        org.springframework.beans.BeanUtils.copyProperties(dto, project);
        bindLegalEntity(project, dto.getCustomerId(), old);
        boolean updated = this.updateById(project);
        if (dto.getSkills() != null) {
            replaceProjectSkills(dto.getId(), dto.getSkills(), "案件更新");
        }
        return updated;
    }

    private void bindLegalEntity(Project project, Long customerId, Project old) {
        if (legalEntityContextService == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null || customer.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(customer.getLegalEntityId());
        if (old != null) {
            legalEntityContextService.assertSame(old.getLegalEntityId(), customer.getLegalEntityId());
        }
        project.setLegalEntityId(customer.getLegalEntityId());
    }

    private void replaceProjectSkills(Long projectId, List<com.ses.entity.ProjectSkill> skills, String reason) {
        com.ses.entity.Project current = baseMapper.selectByIdForTenant(
                projectId, AccountingTenantContextHolder.requireTenantContext());
        if (current == null || current.getVersion() == null) {
            throw BusinessException.of(404, "error.project.notFound");
        }
        com.ses.dto.skill.SkillReplaceRequest request = new com.ses.dto.skill.SkillReplaceRequest();
        request.setExpectedVersion(current.getVersion());
        request.setReason(reason);
        request.setSkills(skills.stream().map(skill -> {
            com.ses.dto.skill.SkillReplaceRequest.SkillItem item =
                    new com.ses.dto.skill.SkillReplaceRequest.SkillItem();
            item.setSkillId(skill.getSkillId());
            item.setRequiredLevel(skill.getRequiredLevel());
            item.setIsMust(skill.getIsMust());
            return item;
        }).toList());
        projectSkillServiceProvider.ifAvailable(service -> service.replaceSkills(projectId, request));
    }
}
