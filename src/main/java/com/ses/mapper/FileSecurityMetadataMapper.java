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
}
