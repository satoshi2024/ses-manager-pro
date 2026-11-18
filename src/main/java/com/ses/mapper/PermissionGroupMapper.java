package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PermissionGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionGroupMapper extends BaseMapper<PermissionGroup> {

    /**
     * NF07 W-5 の不変条件の違反候補: 指定tenantの有効なgroupのうち、全体baseline（{@code action_key='*'}の許可）を持ち、
     * {@code privacy-admin} ではなく、{@code privacy.*} の拒否行を持たないものの group key。
     *
     * <p>prod 起動時に {@code ProductionSecurityConfigurationValidator} が1件でもあれば起動を止める
     * （production-bridge-phased-plan.md §3.1.3）。論理削除済みの行は無いものとして扱う。
     */
    @Select("""
        SELECT g.group_key
        FROM m_permission_group g
        WHERE g.tenant_id = #{tenantId}
          AND g.enabled = 1
          AND g.deleted_flag = 0
          AND g.group_key <> 'privacy-admin'
          AND EXISTS (SELECT 1 FROM t_permission_group_action a
                      WHERE a.tenant_id = g.tenant_id AND a.group_id = g.id
                        AND a.action_key = '*' AND a.deny_flag = 0 AND a.deleted_flag = 0)
          AND NOT EXISTS (SELECT 1 FROM t_permission_group_action d
                          WHERE d.tenant_id = g.tenant_id AND d.group_id = g.id
                            AND d.action_key = 'privacy.*' AND d.deny_flag = 1 AND d.deleted_flag = 0)
        ORDER BY g.group_key
        """)
    List<String> selectWildcardGroupKeysMissingPrivacyDeny(@Param("tenantId") String tenantId);

    /**
     * NF07 の flag ON の前提（W3-14b、production-bridge-phased-plan.md §5.1）: 指定tenantの {@code privacy-admin} groupが
     * 有効・未削除で、{@code privacy.*} の明示許可を持ち、{@code '*'}・{@code privacy.} 接頭辞の拒否行を1件も持たない件数。
     * 1でなければ所属者は privacy を使えない（{@code AuthorizationServiceImpl} は拒否を優先する）。
     */
    @Select("""
        SELECT COUNT(*)
        FROM m_permission_group g
        WHERE g.tenant_id = #{tenantId}
          AND g.group_key = 'privacy-admin'
          AND g.enabled = 1
          AND g.deleted_flag = 0
          AND EXISTS (SELECT 1 FROM t_permission_group_action a
                      WHERE a.tenant_id = g.tenant_id AND a.group_id = g.id
                        AND a.action_key = 'privacy.*' AND a.deny_flag = 0 AND a.deleted_flag = 0)
          AND NOT EXISTS (SELECT 1 FROM t_permission_group_action d
                          WHERE d.tenant_id = g.tenant_id AND d.group_id = g.id
                            AND (d.action_key = '*' OR d.action_key LIKE 'privacy.%')
                            AND d.deny_flag = 1 AND d.deleted_flag = 0)
        """)
    Long countPrivacyAdminGroupsGrantingPrivacy(@Param("tenantId") String tenantId);

    /**
     * NF07 の flag ON の前提（W3-14b、review P3-2）: 指定tenantの有効なgroupのうち、{@code privacy-admin} 以外で
     * {@code privacy.} 接頭辞の許可行（{@code privacy.*}・{@code privacy.<x>}）を持つものの group key。
     *
     * <p>{@code AuthorizationServiceImpl} は privacy の許可候補を {@code {actionKey, privacy.*}} に限るため、
     * {@code '*'} の検査（{@link #selectWildcardGroupKeysMissingPrivacyDeny}）だけでは、privacy-admin を複製した group 等の
     * 許可を検出できない。論理削除済みの行は無いものとして扱う（評価側と同じ範囲）。
     */
    @Select("""
        SELECT DISTINCT g.group_key
        FROM m_permission_group g
        JOIN t_permission_group_action a ON a.tenant_id = g.tenant_id AND a.group_id = g.id
        WHERE g.tenant_id = #{tenantId}
          AND g.enabled = 1
          AND g.deleted_flag = 0
          AND g.group_key <> 'privacy-admin'
          AND a.action_key LIKE 'privacy.%'
          AND a.deny_flag = 0
          AND a.deleted_flag = 0
        ORDER BY g.group_key
        """)
    List<String> selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin(@Param("tenantId") String tenantId);
}
