package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EngineerAccountLinkServiceImpl implements EngineerAccountLinkService {

    private final EngineerAccountLinkMapper linkMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerAccountLink link(Long engineerId, Long sysUserId, Long linkedBy) {
        SysUser user = sysUserMapper.selectById(sysUserId);
        if (user == null) {
            throw BusinessException.of("error.engineerAccount.userNotFound");
        }
        if (!"要員".equals(user.getRole())) {
            throw BusinessException.of("error.engineerAccount.roleNotEngineer");
        }
        if (linkMapper.selectByUserId(sysUserId) != null) {
            throw BusinessException.of("error.engineerAccount.userAlreadyLinked");
        }
        if (linkMapper.selectByEngineerId(engineerId) != null) {
            throw BusinessException.of("error.engineerAccount.engineerAlreadyLinked");
        }
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        link.setLinkedBy(linkedBy);
        linkMapper.insert(link);
        return link;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlinkByEngineerId(Long engineerId) {
        EngineerAccountLink link = linkMapper.selectByEngineerId(engineerId);
        if (link != null) {
            linkMapper.deleteById(link.getId());
        }
    }

    @Override
    public Long findEngineerIdByUserId(Long sysUserId) {
        EngineerAccountLink link = linkMapper.selectByUserId(sysUserId);
        return link != null ? link.getEngineerId() : null;
    }

    @Override
    public EngineerAccountLink findByEngineerId(Long engineerId) {
        return linkMapper.selectByEngineerId(engineerId);
    }

    @Override
    public boolean isUserLinked(Long sysUserId) {
        return linkMapper.selectByUserId(sysUserId) != null;
    }
}
