package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.service.ExternalAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountServiceImpl extends ServiceImpl<ExternalAccountReferenceMapper, ExternalAccountReference> implements ExternalAccountService {

    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final ExternalAccountSystemMapper externalAccountSystemMapper;

    @Override
    @Transactional
    public ExternalAccountReference registerAccountReference(Long systemId,
                                                              String accountIdentifier,
                                                              String assigneeType,
                                                              Long assigneeId,
                                                              String permissionLevel,
                                                              Long actorUserId) {
        if (systemId == null) {
            throw new BusinessException("外部システムIDは必須です。");
        }
        if (!StringUtils.hasText(accountIdentifier)) {
            throw new BusinessException("外部アカウント識別子は必須です。");
        }
        if (!StringUtils.hasText(assigneeType) || assigneeId == null) {
            throw new BusinessException("紐付け先（要員/ユーザー）は必須です。");
        }

        ExternalAccountSystem system = externalAccountSystemMapper.selectById(systemId);
        if (system == null) {
            throw new BusinessException("指定された外部システムが見つかりません。");
        }

        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(systemId)
                .accountIdentifier(accountIdentifier.trim())
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .permissionLevel(permissionLevel)
                .status("ACTIVE")
                .provisionedAt(LocalDateTime.now())
                .build();
        save(ref);

        log.info("External account reference registered: id={}, system={}, identifier={}",
                ref.getId(), system.getSystemCode(), accountIdentifier);
        return ref;
    }

    @Override
    @Transactional
    public ExternalAccountReference updateAccountReference(Long id,
                                                            String accountIdentifier,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }

        if (StringUtils.hasText(accountIdentifier)) {
            current.setAccountIdentifier(accountIdentifier.trim());
        }
        if (StringUtils.hasText(permissionLevel)) {
            current.setPermissionLevel(permissionLevel);
        }
        updateById(current);
        return current;
    }

    @Override
    @Transactional
    public ExternalAccountReference confirmRevoke(Long id, Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(current.getStatus())) {
            return current;
        }

        int updated = externalAccountReferenceMapper.confirmRevokeWithCas(
                id, LocalDateTime.now(), actorUserId, current.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "アカウント参照情報が他で更新されました。再読み込みしてください。");
        }

        log.info("External account reference confirmed revoked: id={}, actorUserId={}", id, actorUserId);
        return getById(id);
    }

    @Override
    @Transactional
    public ExternalAccountReference changeStatus(Long id, String status, Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(status)) {
            return confirmRevoke(id, actorUserId);
        }
        current.setStatus(status);
        updateById(current);
        return current;
    }

    @Override
    public List<ExternalAccountReference> getActiveAccountsByAssignee(String assigneeType, Long assigneeId) {
        return externalAccountReferenceMapper.selectActiveByAssignee(assigneeType, assigneeId);
    }

    @Override
    public List<ExternalAccountSystem> getAllSystems() {
        return externalAccountSystemMapper.selectList(new LambdaQueryWrapper<ExternalAccountSystem>()
                .eq(ExternalAccountSystem::getIsActive, 1)
                .orderByAsc(ExternalAccountSystem::getId));
    }

    @Override
    @Transactional
    public ExternalAccountSystem saveSystem(ExternalAccountSystem system) {
        if (!StringUtils.hasText(system.getSystemCode())) {
            throw new BusinessException("システムコードは必須です。");
        }
        if (!StringUtils.hasText(system.getSystemName())) {
            throw new BusinessException("システム名称は必須です。");
        }
        if (system.getId() == null) {
            externalAccountSystemMapper.insert(system);
        } else {
            externalAccountSystemMapper.updateById(system);
        }
        return system;
    }

    @Override
    public IPage<ExternalAccountReference> searchAccounts(int page, int size, Long systemId, String assigneeType, Long assigneeId, String status) {
        Page<ExternalAccountReference> pageable = new Page<>(page, size);
        LambdaQueryWrapper<ExternalAccountReference> wrapper = new LambdaQueryWrapper<>();
        if (systemId != null) {
            wrapper.eq(ExternalAccountReference::getSystemId, systemId);
        }
        if (StringUtils.hasText(assigneeType)) {
            wrapper.eq(ExternalAccountReference::getAssigneeType, assigneeType);
        }
        if (assigneeId != null) {
            wrapper.eq(ExternalAccountReference::getAssigneeId, assigneeId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(ExternalAccountReference::getStatus, status);
        }
        wrapper.orderByDesc(ExternalAccountReference::getId);
        return page(pageable, wrapper);
    }
}
