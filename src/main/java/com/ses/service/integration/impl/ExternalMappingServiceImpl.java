package com.ses.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalMapping;
import com.ses.mapper.ExternalMappingMapper;
import com.ses.service.integration.ExternalMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalMappingServiceImpl extends ServiceImpl<ExternalMappingMapper, ExternalMapping>
        implements ExternalMappingService {

    @Override
    public ExternalMapping getMapping(Long connectionId, String objectType, String internalCode) {
        if (connectionId == null || objectType == null || internalCode == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<ExternalMapping>()
                .eq(ExternalMapping::getConnectionId, connectionId)
                .eq(ExternalMapping::getObjectType, objectType)
                .eq(ExternalMapping::getInternalCode, internalCode));
    }

    @Override
    @Transactional
    public void saveOrUpdateMapping(ExternalMapping mapping) {
        if (mapping.getConnectionId() == null || mapping.getObjectType() == null || mapping.getInternalCode() == null) {
            throw new BusinessException(400, "マッピングの必須項目(connectionId, objectType, internalCode)が不足しています");
        }
        ExternalMapping existing = getMapping(mapping.getConnectionId(), mapping.getObjectType(), mapping.getInternalCode());
        if (existing != null) {
            existing.setExternalId(mapping.getExternalId());
            existing.setExternalCode(mapping.getExternalCode());
            existing.setInternalId(mapping.getInternalId());
            // 再設定時は未検証状態へ戻す（ただし呼び出し元でverified_atが指定されている場合は維持）
            if (mapping.getVerifiedAt() != null) {
                existing.setVerifiedAt(mapping.getVerifiedAt());
                existing.setPayloadSnapshot(mapping.getPayloadSnapshot());
            } else {
                existing.setVerifiedAt(null);
            }
            updateById(existing);
        } else {
            save(mapping);
        }
    }

    @Override
    @Transactional
    public void verifyMapping(Long mappingId, String payloadSnapshot) {
        ExternalMapping mapping = getById(mappingId);
        if (mapping == null) {
            throw new BusinessException(404, "マッピングが見つかりません (id=" + mappingId + ")");
        }
        mapping.setPayloadSnapshot(payloadSnapshot);
        mapping.setVerifiedAt(LocalDateTime.now());
        updateById(mapping);
    }

    @Override
    public void assertMappingVerified(Long connectionId, String objectType, String internalCode) {
        ExternalMapping mapping = getMapping(connectionId, objectType, internalCode);
        if (mapping == null) {
            throw new BusinessException(400, String.format(
                    "外部マッピングが未登録です [種別: %s, 内部キー: %s]。財務設定からマッピングを行ってください。",
                    objectType, internalCode));
        }
        if (mapping.getVerifiedAt() == null) {
            throw new BusinessException(400, String.format(
                    "外部マッピングが未検証です [種別: %s, 内部キー: %s, 外部ID: %s]。送信前にマスタ照合を行ってください。",
                    objectType, internalCode, mapping.getExternalId()));
        }
    }

    @Override
    public List<ExternalMapping> listByConnection(Long connectionId, String objectType) {
        LambdaQueryWrapper<ExternalMapping> wrapper = new LambdaQueryWrapper<ExternalMapping>()
                .eq(ExternalMapping::getConnectionId, connectionId);
        if (objectType != null && !objectType.isBlank()) {
            wrapper.eq(ExternalMapping::getObjectType, objectType);
        }
        return list(wrapper.orderByAsc(ExternalMapping::getObjectType).orderByAsc(ExternalMapping::getInternalCode));
    }
}
