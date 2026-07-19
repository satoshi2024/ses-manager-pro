package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Long projectId = Long.valueOf(id.toString());
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
        this.save(project);
        dto.setId(project.getId());
        if (dto.getSkills() != null) {
            com.ses.service.ProjectSkillService projectSkillService = 
                    org.springframework.web.context.support.WebApplicationContextUtils
                    .getRequiredWebApplicationContext(
                        ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest().getServletContext()
                    ).getBean(com.ses.service.ProjectSkillService.class);
            projectSkillService.replaceSkills(dto.getId(), dto.getSkills());
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
        boolean updated = this.updateById(project);
        if (dto.getSkills() != null) {
            com.ses.service.ProjectSkillService projectSkillService = 
                    org.springframework.web.context.support.WebApplicationContextUtils
                    .getRequiredWebApplicationContext(
                        ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest().getServletContext()
                    ).getBean(com.ses.service.ProjectSkillService.class);
            projectSkillService.replaceSkills(dto.getId(), dto.getSkills());
        }
        return updated;
    }
}




