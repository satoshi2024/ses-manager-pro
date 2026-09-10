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
        long contracts = contractMapper.selectCount(new LambdaQueryWrapper<Contract>().eq(Contract::getProjectId, projectId));
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
            projectSkillServiceProvider.ifAvailable(service -> service.replaceSkills(dto.getId(), dto.getSkills()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProjectWithSkills(com.ses.dto.project.ProjectSaveDto dto) {
        Project old = this.getById(dto.getId());
        if (old != null && old.getCustomerId() != null && !old.getCustomerId().equals(dto.getCustomerId())) {
            long contracts = contractMapper.selectCount(new LambdaQueryWrapper<Contract>().eq(Contract::getProjectId, dto.getId()));
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
            projectSkillServiceProvider.ifAvailable(service -> service.replaceSkills(dto.getId(), dto.getSkills()));
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
}
