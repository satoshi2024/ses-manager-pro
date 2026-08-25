package com.ses.service.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.ContractComplianceSnapshot;
import com.ses.entity.ComplianceSnapshotOperation;
import com.ses.entity.Workplace;
import com.ses.mapper.ComplianceSnapshotOperationMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.ContractComplianceSnapshotMapper;
import com.ses.mapper.WorkplaceMapper;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T064 B1: compliance snapshotの作成・再利用（design §5.4・field-mapping §4.1/§4.2）。
 *  - snapshotはUNIQUE(contract_id, snapshot_version)のappend-only（DB triggerでUPDATE/DELETE拒否）。
 *  - 生成の冪等キーは「profile内容hash」: 同じ内容のprofileからの生成は最新snapshotを再利用する。
 *  - operation row（operation_id一意）＋profile current pointerのversion CASで競合を制御し、
 *    失敗時は同一transactionで全rollback（orphan 0、A/B/Aの履歴を保持）。
 */
@Service
@RequiredArgsConstructor
public class ComplianceSnapshotWriter {

    /** profileHash計算から除外する管理field。 */
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "id", "tenantId", "contractId", "currentSnapshotId", "currentSnapshotVersion",
            "version", "createdAt", "updatedAt", "deletedFlag");

    private final ContractComplianceProfileMapper profileMapper;
    private final ContractComplianceSnapshotMapper snapshotMapper;
    private final ComplianceSnapshotOperationMapper operationMapper;
    private final WorkplaceMapper workplaceMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 契約のcompliance snapshotを保証する。
     * 最新snapshotのhashが現在profileの内容hashと一致すれば再利用（冪等）、
     * 異なれば新versionを作成してcurrent pointerをCASで進める。
     */
    @Transactional(rollbackFor = Exception.class)
    public ContractComplianceSnapshot ensureSnapshot(Contract contract, ContractComplianceProfile profile) {
        String hash = profileHash(profile);
        ContractComplianceSnapshot latest = latestSnapshot(contract.getId());
        if (latest != null && hash.equals(latest.getSnapshotHash())) {
            return latest;
        }
        int nextVersion = latest == null ? 1 : latest.getSnapshotVersion() + 1;

        ContractComplianceSnapshot snapshot = new ContractComplianceSnapshot();
        copyFromProfile(snapshot, contract, profile);
        snapshot.setTenantId("default");
        snapshot.setContractId(contract.getId());
        snapshot.setSnapshotVersion(nextVersion);
        snapshot.setSnapshotHash(hash);
        snapshot.setSnapshotAt(LocalDateTime.now());
        try {
            snapshotMapper.insert(snapshot);
        } catch (DuplicateKeyException e) {
            // 並行生成: 同一versionのINSERT競合。相手が同じ内容なら最新を再利用する。
            ContractComplianceSnapshot concurrent = latestSnapshot(contract.getId());
            if (concurrent != null && hash.equals(concurrent.getSnapshotHash())) {
                return concurrent;
            }
            throw BusinessException.of(409, "contract.compliance.snapshotConflict");
        }

        ComplianceSnapshotOperation operation = new ComplianceSnapshotOperation();
        operation.setOperationId("GEN:" + contract.getId() + ":" + nextVersion);
        operation.setScopeType("CONTRACT");
        operation.setContractId(contract.getId());
        operation.setExpectedVersion(profile.getCurrentSnapshotVersion());
        operation.setResultingSnapshotId(snapshot.getId());
        operation.setRequestHash(hash);
        operation.setStatus("SUCCEEDED");
        try {
            operationMapper.insert(operation);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.snapshotConflict");
        }

        profile.setCurrentSnapshotId(snapshot.getId());
        profile.setCurrentSnapshotVersion(nextVersion);
        int rows = profileMapper.updateById(profile);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.snapshotConflict");
        }
        return snapshot;
    }

    /** 最新snapshot（version降順の先頭）。 */
    public ContractComplianceSnapshot latestSnapshot(Long contractId) {
        List<ContractComplianceSnapshot> list = snapshotMapper.selectList(
                new LambdaQueryWrapper<ContractComplianceSnapshot>()
                        .eq(ContractComplianceSnapshot::getContractId, contractId)
                        .orderByDesc(ContractComplianceSnapshot::getSnapshotVersion)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** profileの業務fieldから決定的内容hashを計算する（冪等キー）。 */
    public String profileHash(ContractComplianceProfile profile) {
        String canonical = java.util.Arrays.stream(ContractComplianceProfile.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> !EXCLUDED_FIELDS.contains(name))
                .sorted(Comparator.naturalOrder())
                .map(name -> name + "=" + readField(profile, name))
                .collect(Collectors.joining("|"));
        return sha256(canonical);
    }

    private String readField(ContractComplianceProfile profile, String fieldName) {
        try {
            java.lang.reflect.Field field = ContractComplianceProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(profile);
            return value == null ? "∅" : String.valueOf(value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("fieldがentityに存在しません: " + fieldName, e);
        }
    }

    private void copyFromProfile(ContractComplianceSnapshot snapshot, Contract contract, ContractComplianceProfile profile) {
        BeanUtils.copyProperties(profile, snapshot);
        // profileの管理列（id/created_at等）をsnapshotへ複写しない。versionは@Version制御へ委ねる。
        snapshot.setId(null);
        snapshot.setCreatedAt(null);
        snapshot.setUpdatedAt(null);
        snapshot.setContractNo(contract.getContractNo());
        snapshot.setContractDate(contract.getContractDate());
        snapshot.setDispatchFrom(profile.getDispatchPeriodStart());
        snapshot.setDispatchTo(profile.getDispatchPeriodEnd());
        // 当事者（派遣元=自社）はcompany系m_system_configからsnapshot化する（field-mapping FM-C-01）。
        snapshot.setPartyName(systemConfigService.getString("company.name", null));
        snapshot.setPartyAddress(systemConfigService.getString("company.address", null));
        snapshot.setPartyRepresentative(systemConfigService.getString("company.representative", null));
        if (profile.getWorkplaceId() != null) {
            Workplace workplace = workplaceMapper.selectById(profile.getWorkplaceId());
            if (workplace != null) {
                snapshot.setWorkplaceName(workplace.getName());
                snapshot.setWorkplaceAddress(workplace.getAddress());
                snapshot.setWorkplaceDepartment(workplace.getOrganizationUnit());
                snapshot.setWorkplacePhone(workplace.getPhone());
                snapshot.setOrganizationUnit(workplace.getOrganizationUnit());
            }
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash計算に失敗しました", e);
        }
    }

    /** 現在のprofile内容が最新snapshotと同一か（帳票生成の冪等判定用）。 */
    public boolean isCurrentProfileSnapshotted(Contract contract, ContractComplianceProfile profile) {
        ContractComplianceSnapshot latest = latestSnapshot(contract.getId());
        return latest != null && profileHash(profile).equals(latest.getSnapshotHash());
    }
}
