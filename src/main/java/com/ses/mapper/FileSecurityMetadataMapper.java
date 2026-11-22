package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FileSecurityMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FileSecurityMetadataMapper extends BaseMapper<FileSecurityMetadata> {

    @Select("SELECT * FROM t_file_security_metadata WHERE tenant_id = #{tenantId} "
            + "AND stored_name = #{storedName} AND deleted_flag = 0 LIMIT 1")
    FileSecurityMetadata selectByStoredName(@Param("tenantId") String tenantId,
                                            @Param("storedName") String storedName);

    /**
     * 保存名の metadata の行の数（全 tenant・論理削除済みを含む）。登録されなかった実体の削除（{@code discardUnregistered}）が、行の
     * 無いことを確かめてから消すために使う（review rb-fe-a1 1a4fd48d 差分 P3-N1）。
     */
    @Select("SELECT COUNT(*) FROM t_file_security_metadata WHERE stored_name = #{storedName}")
    long countAnyByStoredName(@Param("storedName") String storedName);

    @Update("UPDATE t_file_security_metadata SET storage_state = #{storageState}, "
            + "scan_status = #{scanStatus}, scanned_at = #{scannedAt}, scanner_version = #{scannerVersion}, "
            + "rejection_reason = #{rejectionReason}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0")
    int updateScanResult(@Param("id") Long id,
                         @Param("storageState") String storageState,
                         @Param("scanStatus") String scanStatus,
                         @Param("scannedAt") LocalDateTime scannedAt,
                         @Param("scannerVersion") String scannerVersion,
                         @Param("rejectionReason") String rejectionReason);

    @Update("UPDATE t_file_security_metadata SET deleted_flag = 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    int markDeletedForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
