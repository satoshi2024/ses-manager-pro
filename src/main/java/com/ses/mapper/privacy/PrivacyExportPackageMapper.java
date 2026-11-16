package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FileSecurityMetadata;
import com.ses.entity.privacy.PrivacyExportPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DSAR の開示 package（t_privacy_export_package）。状態の更新は service の条件付き CAS だけで行う。
 *
 * <p>tenant を問わない照会は次の4つだけで、どれも保存名・状態しか返さない。
 * <ul>
 *   <li>{@link #countByStoredNames}: 汎用 file 経路の遮断（{@code FileScopeValidationService}。package の行は PURGED も数え、
 *       行を書けなかった package も {@code t_file_security_metadata.file_kind='PRIVACY_EXPORT'} で数える。review P2-1 (b)）</li>
 *   <li>{@link #selectLiveStoredNames}: 孤児清掃からの保護（{@code PrivacyExportPackageFileReferenceProvider}。PURGED は数えない）</li>
 *   <li>{@link #selectPurgeDue}: 期限超過・終了した case の package の削除（{@code PrivacyExportPackagePurgeScheduler}）</li>
 *   <li>{@link #selectOrphanFiles}: PURGED でない package の行を持たない PRIVACY_EXPORT の file の削除（同。review P2-1 (c)）</li>
 * </ul>
 */
@Mapper
public interface PrivacyExportPackageMapper extends BaseMapper<PrivacyExportPackage> {

    /**
     * 保存名が DSAR package のものか（tenant・状態を問わない）。package の行（{@code idx_privacy_export_package_stored_name}）と、
     * 種別が PRIVACY_EXPORT の file metadata（論理削除済みを含む）の件数の和。
     */
    @Select("SELECT (SELECT COUNT(*) FROM t_privacy_export_package WHERE stored_name IN (#{storedName}, #{normalizedName})) "
            + "+ (SELECT COUNT(*) FROM t_file_security_metadata WHERE stored_name IN (#{storedName}, #{normalizedName}) "
            + "AND file_kind = 'PRIVACY_EXPORT')")
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

    /**
     * PURGED でない package の行を持たない、論理削除されていない PRIVACY_EXPORT の file metadata（全 tenant、id 順）。
     * 草案の保存の直後で package の行を書く前の file を消さないよう、DB の時計で {@code graceHours} 時間より前に作られた行だけを返す
     * （application と DB の時刻帯の違いに依らない）。
     */
    @Select("SELECT m.id, m.tenant_id, m.stored_name FROM t_file_security_metadata m "
            + "WHERE m.file_kind = 'PRIVACY_EXPORT' AND m.deleted_flag = 0 "
            + "AND m.created_at <= TIMESTAMPADD(HOUR, #{negativeGraceHours}, LOCALTIMESTAMP) "
            + "AND NOT EXISTS (SELECT 1 FROM t_privacy_export_package p WHERE p.tenant_id = m.tenant_id "
            + "AND p.stored_name = m.stored_name AND p.status <> 'PURGED') "
            + "ORDER BY m.id LIMIT #{limit}")
    List<FileSecurityMetadata> selectOrphanFiles(@Param("negativeGraceHours") int negativeGraceHours,
                                                 @Param("limit") int limit);
}
