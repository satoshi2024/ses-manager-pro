package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.PrivacyExportPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DSAR の開示 package（t_privacy_export_package）。状態の更新は service の条件付き CAS だけで行う。
 *
 * <p>tenant を問わない照会は次の3つだけで、どれも保存名・状態しか返さない。
 * <ul>
 *   <li>{@link #countByStoredNames}: 汎用 file 経路の遮断（{@code FileScopeValidationService}。PURGED の行も数える）</li>
 *   <li>{@link #selectLiveStoredNames}: 孤児清掃からの保護（{@code PrivacyExportPackageFileReferenceProvider}。PURGED は数えない）</li>
 *   <li>{@link #selectPurgeDue}: 期限超過・終了した case の package の削除（{@code PrivacyExportPackagePurgeScheduler}）</li>
 * </ul>
 */
@Mapper
public interface PrivacyExportPackageMapper extends BaseMapper<PrivacyExportPackage> {

    /** 保存名が DSAR package のものか（tenant・状態を問わない。{@code idx_privacy_export_package_stored_name} を使う）。 */
    @Select("SELECT COUNT(*) FROM t_privacy_export_package WHERE stored_name IN (#{storedName}, #{normalizedName})")
    long countByStoredNames(@Param("storedName") String storedName, @Param("normalizedName") String normalizedName);

    /** PURGED でない package の保存名（全 tenant）。 */
    @Select("SELECT stored_name FROM t_privacy_export_package WHERE status <> 'PURGED'")
    List<String> selectLiveStoredNames();

    /**
     * 削除すべき package（PURGED でなく、{@code purge_after} を過ぎたか、request が終了状態のもの）。全 tenant を id 順に返す。
     */
    @Select("SELECT p.id, p.tenant_id, p.request_id, p.stored_name, p.status, p.purge_after "
            + "FROM t_privacy_export_package p "
            + "JOIN t_privacy_request r ON r.tenant_id = p.tenant_id AND r.id = p.request_id "
            + "WHERE p.status <> 'PURGED' "
            + "AND (p.purge_after <= #{now} OR r.status IN ('CONFIRMED', 'BLOCKED', 'CANCELLED', 'UNKNOWN', 'PARTIAL_FAILED')) "
            + "ORDER BY p.id LIMIT #{limit}")
    List<PrivacyExportPackage> selectPurgeDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
