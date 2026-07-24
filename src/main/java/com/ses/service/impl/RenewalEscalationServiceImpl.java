package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.entity.Contract;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.RenewalEscalationService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link RenewalEscalationService} の実装（FR-06）。
 * 段階設定（{@code m_system_config.renewal.escalation-days} 例 "30:営業,14:上長"）に従い、
 * 更新期限のN日前に到達しても未対応の契約を、担当営業→上長（管理者/マネージャー、組織階層が
 * 無いため固定）の順で通知する。
 *
 * <p>「未対応」= renewal_decision が NULL（継続確定/更新不要の明示判断なし）かつ、
 * 更新ドラフトがあっても未確定（{@code RenewalCalendarServiceImpl} と同一の判定基準）。
 * dedupe は月粒度（{@code dedupeKey} に年月を含める）で、対応済みになった契約は次回実行の
 * 抽出クエリ自体に含まれなくなるため自然に通知が止まる。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenewalEscalationServiceImpl implements RenewalEscalationService {

    private static final String CONFIG_KEY = "renewal.escalation-days";
    private static final String DEFAULT_STAGES = "30:営業,14:上長";
    private static final String ROLE_SALES = "営業";
    private static final String ROLE_SUPERIOR = "上長";

    private final ContractMapper contractMapper;
    private final SysUserMapper sysUserMapper;
    private final SystemConfigService systemConfigService;
    private final NotificationService notificationService;

    @Override
    public int escalateUnhandled() {
        List<Stage> stages = parseStages(systemConfigService.getString(CONFIG_KEY, DEFAULT_STAGES));
        if (stages.isEmpty()) {
            return 0;
        }

        List<Contract> candidates = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getStatus, StatusConstants.CONTRACT_ACTIVE)
                .isNotNull(Contract::getEndDate)
                .isNull(Contract::getRenewalDecision));
        if (candidates.isEmpty()) {
            return 0;
        }

        Set<Long> confirmedOriginalIds = resolveConfirmedOriginalIds(candidates);
        LocalDate today = LocalDate.now();
        String monthKey = YearMonth.now().toString();
        int notified = 0;

        for (Contract c : candidates) {
            if (confirmedOriginalIds.contains(c.getId())) {
                continue; // 対応済み(更新ドラフト確定)はエスカレーション対象外
            }
            for (Stage stage : stages) {
                LocalDate escalationDate = c.getEndDate().minusDays(stage.days());
                if (today.isBefore(escalationDate)) {
                    continue;
                }
                notified += notifyStage(c, stage, monthKey);
            }
        }
        return notified;
    }

    private int notifyStage(Contract c, Stage stage, String monthKey) {
        String dedupeKey = "RENEWAL_ESCALATION:" + c.getId() + ":" + stage.days() + ":" + monthKey;
        String message = "[\"notification.msg.RENEWAL_ESCALATION\", \"" + emptyToDash(c.getContractNo()) + "\", \""
                + stage.days() + "\", \"" + c.getEndDate() + "\"]";
        String title = "契約更新エスカレーション";

        if (ROLE_SALES.equals(stage.role())) {
            // 未帰属(salesUserId=null)は全体通知にフォールバック(CONTRACT_END等と同じ規約)
            notificationService.publishToUser(c.getSalesUserId(), "RENEWAL_ESCALATION", title, message,
                    NotificationLinks.CONTRACT_RENEWAL_CALENDAR, dedupeKey);
            return 1;
        }
        if (ROLE_SUPERIOR.equals(stage.role())) {
            List<SysUser> superiors = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getRole, StatusConstants.ROLE_ADMIN, StatusConstants.ROLE_MANAGER)
                    .eq(SysUser::getStatus, 1));
            for (SysUser u : superiors) {
                notificationService.publishToUser(u.getId(), "RENEWAL_ESCALATION", title, message,
                        NotificationLinks.CONTRACT_RENEWAL_CALENDAR, dedupeKey);
            }
            return superiors.size();
        }
        log.warn("renewal.escalation-days に未知の通知先ロールが指定されています: {}", stage.role());
        return 0;
    }

    /** 更新ドラフトが確定済み(準備中以外)である元契約IDの集合。 */
    private Set<Long> resolveConfirmedOriginalIds(List<Contract> candidates) {
        List<Long> ids = candidates.stream().map(Contract::getId).collect(Collectors.toList());
        List<ContractDraftStatusDto> drafts = contractMapper.selectDraftStatusesByOriginalIds(ids);
        Map<Long, Boolean> confirmedByOriginalId = new HashMap<>();
        for (ContractDraftStatusDto d : drafts) {
            boolean confirmed = !StatusConstants.CONTRACT_PREPARING.equals(d.getStatus());
            confirmedByOriginalId.merge(d.getRenewedFromContractId(), confirmed, (a, b) -> a || b);
        }
        return confirmedByOriginalId.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private String emptyToDash(String v) {
        return (v == null || v.isEmpty()) ? "-" : v;
    }

    private List<Stage> parseStages(String config) {
        List<Stage> stages = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return stages;
        }
        for (String part : config.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] kv = trimmed.split(":", 2);
            if (kv.length != 2) continue;
            try {
                int days = Integer.parseInt(kv[0].trim());
                String role = kv[1].trim();
                if (!role.isEmpty()) {
                    stages.add(new Stage(days, role));
                }
            } catch (NumberFormatException e) {
                log.warn("renewal.escalation-days の段階設定が不正です: {}", trimmed);
            }
        }
        return stages;
    }

    private record Stage(int days, String role) {
    }
}
