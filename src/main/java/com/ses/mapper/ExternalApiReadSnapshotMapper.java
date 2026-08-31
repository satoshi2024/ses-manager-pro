package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiSnapshotItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** A1の短期materialized read snapshot。raw entityやrequest payloadは保存しない。 */
@Mapper
public interface ExternalApiReadSnapshotMapper {

    @Insert("""
            INSERT INTO t_api_read_snapshot
                (snapshot_id, client_id, tenant_id, legal_entity_id, route_template, scope_digest,
                 as_of, expires_at, created_at)
            VALUES
                (#{snapshotId}, #{clientId}, #{tenantId}, #{legalEntityId}, #{routeTemplate}, #{scopeDigest},
                 #{asOf}, #{expiresAt}, CURRENT_TIMESTAMP)
            """)
    int insertSnapshot(@Param("snapshotId") String snapshotId,
                       @Param("clientId") String clientId,
                       @Param("tenantId") String tenantId,
                       @Param("legalEntityId") Long legalEntityId,
                       @Param("routeTemplate") String routeTemplate,
                       @Param("scopeDigest") String scopeDigest,
                       @Param("asOf") Instant asOf,
                       @Param("expiresAt") Instant expiresAt);

    @Insert("""
            INSERT INTO t_api_read_snapshot_item
                (snapshot_id, resource_id, payload_json, created_at)
            VALUES
                (#{snapshotId}, #{resourceId}, #{payloadJson}, CURRENT_TIMESTAMP)
            """)
    int insertItem(@Param("snapshotId") String snapshotId,
                   @Param("resourceId") Long resourceId,
                   @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT i.resource_id AS resourceId, i.payload_json AS payloadJson
            FROM t_api_read_snapshot_item i
            JOIN t_api_read_snapshot s ON s.snapshot_id = i.snapshot_id
            WHERE i.snapshot_id = #{snapshotId}
              AND s.expires_at > CURRENT_TIMESTAMP
              AND i.resource_id < #{afterId}
            ORDER BY i.resource_id DESC
            LIMIT #{limit}
            """)
    List<ExternalApiSnapshotItem> selectItemsAfter(@Param("snapshotId") String snapshotId,
                                                   @Param("afterId") Long afterId,
                                                   @Param("limit") int limit);

    /** expiry index順の有限batch。header削除時のFK cascadeでitemも同じbounded snapshot集合だけを削除する。 */
    @Select("""
            SELECT snapshot_id
            FROM t_api_read_snapshot
            WHERE expires_at <= #{now}
            ORDER BY expires_at, snapshot_id
            LIMIT #{limit}
            """)
    List<String> selectExpiredSnapshotIds(@Param("now") Instant now, @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM t_api_read_snapshot
            WHERE snapshot_id IN
            <foreach collection="snapshotIds" item="snapshotId" open="(" separator="," close=")">
                #{snapshotId}
            </foreach>
            </script>
            """)
    int deleteSnapshotsById(@Param("snapshotIds") List<String> snapshotIds);
}
