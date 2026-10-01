package com.ses.service.certification;

import com.ses.entity.LifecycleCase;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** 資格一覧・詳細・通知が共有する、ライフサイクルのas-of解決器。 */
@Component
public class CertificationLifecycleStateResolver {

    public Resolution resolve(List<LifecycleCase> cases, LocalDate asOf) {
        if (asOf == null) {
            return new Resolution("ACTIVE", false);
        }
        String state = "ACTIVE";
        boolean reinstatement = false;
        if (cases == null) {
            return new Resolution(state, false);
        }
        List<LifecycleCase> ordered = cases.stream()
                .filter(item -> item != null && !"CANCELLED".equals(item.getStatus()))
                .filter(item -> item.getAnchorDate() != null && !item.getAnchorDate().isAfter(asOf))
                .sorted(Comparator.comparing(LifecycleCase::getAnchorDate)
                        .thenComparing(item -> item.getId() == null ? 0L : item.getId()))
                .toList();
        for (LifecycleCase item : ordered) {
            String type = item.getLifecycleType();
            if ("LEAVE".equals(type)) {
                LocalDate leaveEnd = item.getCompletedAt() == null ? null : item.getCompletedAt().toLocalDate();
                if (leaveEnd == null || asOf.isBefore(leaveEnd)) {
                    if ("ACTIVE".equals(item.getStatus()) || "ON_HOLD".equals(item.getStatus())
                            || "COMPLETED".equals(item.getStatus())) {
                        state = "ON_LEAVE";
                        reinstatement = false;
                    }
                } else if ("COMPLETED".equals(item.getStatus()) && "ON_LEAVE".equals(state)) {
                    state = "ACTIVE";
                    reinstatement = false;
                }
                continue;
            }
            // 完了日時がasOfより後の状態確定は、過去照会に影響させない。
            if (item.getCompletedAt() != null && item.getCompletedAt().toLocalDate().isAfter(asOf)) {
                continue;
            }
            if ("REINSTATEMENT".equals(type) && "COMPLETED".equals(item.getStatus())) {
                state = "ACTIVE";
                reinstatement = true;
            } else if ("RESIGNATION".equals(type) && "COMPLETED".equals(item.getStatus())) {
                state = "RESIGNED";
                reinstatement = false;
            } else if ("REINSTATEMENT".equals(type) || "RESIGNATION".equals(type)) {
                state = "PENDING";
                reinstatement = false;
            }
        }
        return new Resolution(state, reinstatement);
    }

    public record Resolution(String state, boolean reinstatement) { }
}
