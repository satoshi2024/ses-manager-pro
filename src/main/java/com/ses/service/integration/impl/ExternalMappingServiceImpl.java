package com.ses.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.mapper.ExternalMappingMapper;
import com.ses.service.accounting.AccountingMappingType;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalMappingServiceImpl extends ServiceImpl<ExternalMappingMapper, ExternalMapping>
        implements ExternalMappingService {

    private final IntegrationConnectionService connectionService;
    private final ApplicationContext applicationContext;

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

    /**
     * マッピングを登録・更新する。
     *
     * <p>検証状態（verified_at / payload_snapshot）は外部マスタ照合だけが付与する。リクエスト本文の値は受け付けず、
     * 外部IDが変わった場合は未検証へ戻す（未照合のIDが「検証済」のまま送信ガードを通るのを防ぐ）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdateMapping(ExternalMapping mapping) {
        String internalCode = trimToNull(mapping.getInternalCode());
        String externalId = trimToNull(mapping.getExternalId());
        if (mapping.getConnectionId() == null || mapping.getObjectType() == null
                || internalCode == null || externalId == null) {
            throw BusinessException.of(400, "error.accounting.mappingRequired");
        }
        if (AccountingMappingType.parse(mapping.getObjectType()).isEmpty()) {
            throw BusinessException.of(400, "error.accounting.mappingTypeUnsupported");
        }
        ExternalMapping existing = getMapping(mapping.getConnectionId(), mapping.getObjectType(), internalCode);
        if (existing != null) {
            boolean externalIdChanged = !Objects.equals(existing.getExternalId(), externalId);
            existing.setExternalId(externalId);
            existing.setExternalCode(mapping.getExternalCode());
            existing.setInternalId(mapping.getInternalId());
            updateById(existing);
            if (externalIdChanged) {
                // update-strategy: not_null のため entity の null では列を消せない。列を明示して未検証へ戻す。
                clearVerification(existing.getId(), null);
            }
        } else {
            mapping.setId(null);
            mapping.setInternalCode(internalCode);
            mapping.setExternalId(externalId);
            mapping.setVerifiedAt(null);
            mapping.setPayloadSnapshot(null);
            save(mapping);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyMapping(Long mappingId, String payloadSnapshot) {
        ExternalMapping mapping = getById(mappingId);
        if (mapping == null) {
            throw BusinessException.of(404, "error.accounting.mappingNotFound");
        }
        mapping.setPayloadSnapshot(payloadSnapshot);
        mapping.setVerifiedAt(LocalDateTime.now());
        updateById(mapping);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerifyOutcome verifyAndSnapshotMappingScoped(Long mappingId, String tenantId) {
        String effectiveTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        // R4-R3: mapping取得時点でconnection.tenant_idをJOINし、取得後のJava側filterに依存しない。
        ExternalMapping mapping = baseMapper.selectByIdScopedToTenant(mappingId, effectiveTenant);
        if (mapping == null) {
            return VerifyOutcome.NOT_FOUND;
        }
        IntegrationConnection conn = connectionService.getByIdScoped(mapping.getConnectionId(), effectiveTenant, null);
        if (conn == null) {
            return VerifyOutcome.NOT_FOUND;
        }

        if (AccountingMappingType.parse(mapping.getObjectType()).isEmpty()) {
            clearVerification(mapping.getId(), failureSnapshot(conn, "UNSUPPORTED_OBJECT_TYPE"));
            return VerifyOutcome.UNSUPPORTED_TYPE;
        }

        AccountingProvider provider = resolveProvider(conn.getProvider());
        boolean verified = provider.verifyMaster(conn, mapping.getObjectType(), mapping.getExternalId(), mapping.getExternalCode());
        if (!verified) {
            // 失敗を確定させる（以前の検証済みを残すと、照合できないIDが送信ガードを通過する）。
            clearVerification(mapping.getId(), failureSnapshot(conn, "EXTERNAL_MASTER_NOT_FOUND"));
            log.warn("外部マスタ照合に失敗したためマッピングを未検証へ戻しました: mappingId={} objectType={}",
                    mapping.getId(), mapping.getObjectType());
            return VerifyOutcome.REJECTED;
        }

        String snapshot = String.format("{\"verified\":true,\"provider\":\"%s\",\"objectType\":\"%s\",\"externalId\":\"%s\",\"verifiedAt\":\"%s\"}",
                conn.getProvider(), mapping.getObjectType(), mapping.getExternalId(), LocalDateTime.now());
        mapping.setPayloadSnapshot(snapshot);
        mapping.setVerifiedAt(LocalDateTime.now());
        updateById(mapping);
        return VerifyOutcome.VERIFIED;
    }

    @Override
    public void assertMappingVerified(Long connectionId, String objectType, String internalCode) {
        MappingCheck check = inspect(connectionId, objectType, internalCode);
        if (check.ok()) {
            return;
        }
        // 種別はenum引数で渡す（SafeErrorPolicyは文字列引数を伏せるため）。内部キーは送信プレビューで確認できる。
        Object typeArg = AccountingMappingType.parse(objectType).<Object>map(t -> t).orElse("-");
        String key = "MISSING".equals(check.status())
                ? "error.accounting.mappingMissing" : "error.accounting.mappingUnverified";
        throw BusinessException.of(400, key, typeArg);
    }

    @Override
    public MappingCheck inspect(Long connectionId, String objectType, String internalCode) {
        ExternalMapping mapping = getMapping(connectionId, objectType, internalCode);
        if (mapping == null) {
            return new MappingCheck(objectType, internalCode, null, "MISSING");
        }
        return new MappingCheck(objectType, internalCode, mapping.getExternalId(),
                mapping.getVerifiedAt() == null ? "UNVERIFIED" : "OK");
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

    @Override
    public List<ExternalMapping> listByConnectionsScoped(Long connectionId, String objectType, java.util.Set<Long> allowedConnectionIds) {
        if (allowedConnectionIds == null) {
            return listByConnection(connectionId, objectType);
        }
        if (allowedConnectionIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        LambdaQueryWrapper<ExternalMapping> wrapper = new LambdaQueryWrapper<ExternalMapping>()
                .eq(ExternalMapping::getConnectionId, connectionId)
                .in(ExternalMapping::getConnectionId, allowedConnectionIds);
        if (objectType != null && !objectType.isBlank()) {
            wrapper.eq(ExternalMapping::getObjectType, objectType);
        }
        return list(wrapper.orderByAsc(ExternalMapping::getObjectType).orderByAsc(ExternalMapping::getInternalCode));
    }

    /** verified_at を NULL へ戻す。snapshot が null なら payload_snapshot も消す。 */
    private void clearVerification(Long mappingId, String snapshot) {
        update(new LambdaUpdateWrapper<ExternalMapping>()
                .eq(ExternalMapping::getId, mappingId)
                .set(ExternalMapping::getVerifiedAt, null)
                .set(ExternalMapping::getPayloadSnapshot, snapshot)
                .set(ExternalMapping::getUpdatedAt, LocalDateTime.now()));
    }

    private static String failureSnapshot(IntegrationConnection conn, String reason) {
        return String.format("{\"verified\":false,\"provider\":\"%s\",\"reason\":\"%s\",\"checkedAt\":\"%s\"}",
                conn.getProvider(), reason, LocalDateTime.now());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AccountingProvider resolveProvider(String providerName) {
        Map<String, AccountingProvider> providers = applicationContext.getBeansOfType(AccountingProvider.class);
        for (AccountingProvider p : providers.values()) {
            if (p.providerName().equalsIgnoreCase(providerName)) {
                return p;
            }
        }
        // デフォルトは freee
        if (providers.containsKey("freeeAccountingProvider")) {
            return providers.get("freeeAccountingProvider");
        }
        throw new BusinessException(500, "対応するプロバイダが存在しません: " + providerName);
    }
}
