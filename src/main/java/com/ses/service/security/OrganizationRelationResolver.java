package com.ses.service.security;

import com.ses.entity.OrganizationRelationHistory;
import com.ses.entity.OrganizationUnit;
import com.ses.mapper.OrganizationRelationHistoryMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 組織の親子・状態をasOfで解決する共通ロジック。
 *
 * <p>{@code m_organization_unit}の現在値だけを見ると、統合・状態変更のあと
 * 過去日付の照会結果まで変わってしまう（第十三次Review P1-1）。
 * {@link OrganizationRelationHistoryMapper#selectAsOf(LocalDate)} が返す行は
 * すでにSQL側で「履歴が見つかった場合は履歴値、無ければ現在値」を解決済みなので、
 * 呼び出し側は素直にマップから読むだけでよい（ここでCOALESCEし直すと、履歴が
 * 見つかったのにその版の値が明示的なNULLだったケースを現在値へ誤ってフォールバック
 * させてしまう）。
 *
 * <p>{@link com.ses.service.impl.OrganizationServiceImpl}（組織ツリー・子孫解決）と
 * {@link com.ses.service.security.impl.OrganizationScopeServiceImpl}（一覧・件数・export）の
 * 両方が同じ解決規則を使うことを保証するために共通化する。
 */
public final class OrganizationRelationResolver {

    private OrganizationRelationResolver() {
    }

    /** 指定日時点の親子・状態を組織IDごとに引ける形で取得する。mapperがnullなら空。 */
    public static Map<Long, OrganizationRelationHistory> asOfMap(
            OrganizationRelationHistoryMapper mapper, LocalDate asOf) {
        if (mapper == null) {
            return Map.of();
        }
        Map<Long, OrganizationRelationHistory> map = new HashMap<>();
        for (OrganizationRelationHistory row : mapper.selectAsOf(asOf)) {
            if (row.getOrganizationId() != null) {
                map.put(row.getOrganizationId(), row);
            }
        }
        return map;
    }

    /** asOf時点の状態。履歴に該当行が無い（=queryの対象外だった）場合のみ現在値。 */
    public static String status(Map<Long, OrganizationRelationHistory> relations, OrganizationUnit unit) {
        OrganizationRelationHistory row = relations.get(unit.getId());
        return row == null || row.getStatus() == null ? unit.getStatus() : row.getStatus();
    }

    /** asOf時点の親組織ID。履歴に該当行が無い場合のみ現在値。 */
    public static Long parentId(Map<Long, OrganizationRelationHistory> relations, OrganizationUnit unit) {
        OrganizationRelationHistory row = relations.get(unit.getId());
        return row == null ? unit.getParentId() : row.getParentId();
    }
}
