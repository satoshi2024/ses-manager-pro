package com.ses.service.impl;

import com.ses.common.constant.RenewalState;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.dto.contract.RenewalCalendarItemDto;
import com.ses.dto.contract.RenewalCalendarResponseDto;
import com.ses.mapper.ContractMapper;
import com.ses.service.RenewalCalendarService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link RenewalCalendarService} の実装（FR-06）。
 * 状態は原則導出: 明示フラグ(renewalDecision) &gt; 更新ドラフトの有無/確定 &gt; 未対応 の順で判定する。
 */
@Service
@RequiredArgsConstructor
public class RenewalCalendarServiceImpl implements RenewalCalendarService {

    /** API応答の上限件数（A7-22: 黙って欠けない。超過時は truncated=true で明示する）。 */
    private static final int MAX_ITEMS = 1000;

    private final ContractMapper contractMapper;
    private final SystemConfigService systemConfigService;
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.servicedesk.CustomerHealthService customerHealthService;

    @Override
    public RenewalCalendarResponseDto getCalendar(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw BusinessException.of(400, "error.renewalCalendar.invalidRange");
        }

        int leadDays = systemConfigService.getInt("notice.contract-end-days", 30);

        RenewalCalendarResponseDto response = new RenewalCalendarResponseDto();
        response.setLeadDays(leadDays);

        List<Long> allowedIds = effectiveContractIds();
        if (allowedIds != null && allowedIds.isEmpty()) {
            response.setItems(Collections.emptyList());
            response.setTruncated(false);
            return response;
        }

        // renewalDueDate = endDate - leadDays が [from, to] に入る契約 = endDate が [from+leadDays, to+leadDays] に入る契約
        LocalDate endDateFrom = from.plusDays(leadDays);
        LocalDate endDateTo = to.plusDays(leadDays);

        List<RenewalCalendarItemDto> candidates = contractMapper.selectRenewalCalendarCandidates(
                StatusConstants.CONTRACT_ACTIVE, endDateFrom, endDateTo, allowedIds, MAX_ITEMS + 1);

        boolean truncated = candidates.size() > MAX_ITEMS;
        if (truncated) {
            candidates = candidates.subList(0, MAX_ITEMS);
        }

        Map<Long, Boolean> hasConfirmedDraftByOriginalId = resolveDraftStates(candidates);

        // 顧客ヘルススコア連携
        Set<Long> customerIds = candidates.stream()
                .map(RenewalCalendarItemDto::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, com.ses.dto.servicedesk.CustomerHealthScoreDto> healthMap = customerHealthService != null
                ? customerHealthService.getHealthMapForCustomers(customerIds)
                : Collections.emptyMap();

        for (RenewalCalendarItemDto item : candidates) {
            item.setRenewalDueDate(item.getEndDate().minusDays(leadDays));
            item.setRenewalState(deriveState(item, hasConfirmedDraftByOriginalId));
            if (item.getCustomerId() != null && healthMap.containsKey(item.getCustomerId())) {
                com.ses.dto.servicedesk.CustomerHealthScoreDto health = healthMap.get(item.getCustomerId());
                item.setHealthStatus(health.getHealthStatus());
                item.setHealthScore(health.getHealthScore());
                item.setOpenCriticalIssuesCount(health.getOpenCriticalIssuesCount());
                item.setAvgCsatScore(health.getAvgCsatScore());
            }
        }

        response.setItems(candidates);
        response.setTruncated(truncated);
        return response;
    }

    /** renewal-calendarも契約一覧と同じ組織scope∩DataScopeを候補SQLへ渡す。 */
    private List<Long> effectiveContractIds() {
        Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedContractIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new ArrayList<>(dataIds);
        }
        Set<Long> organizationIds = organizationScopeService.allowedContractIds(LocalDate.now());
        return new ArrayList<>(organizationScopeService.intersectWithDataScope(organizationIds, dataIds));
    }

    private Map<Long, Boolean> resolveDraftStates(List<RenewalCalendarItemDto> candidates) {
        List<Long> ids = candidates.stream()
                .map(RenewalCalendarItemDto::getContractId)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ContractDraftStatusDto> drafts = contractMapper.selectDraftStatusesByOriginalIds(ids);
        Map<Long, Boolean> hasConfirmedByOriginalId = new HashMap<>();
        for (ContractDraftStatusDto draft : drafts) {
            // 解約(=更新が取り消された)は「確定」ではない。準備中以外なら全て確定扱いにすると、
            // 中止されたドラフトが誤って「対応済み(緑)」表示になりエスカレーションも止まってしまう。
            boolean confirmed = StatusConstants.CONTRACT_ACTIVE.equals(draft.getStatus())
                    || StatusConstants.CONTRACT_ENDED.equals(draft.getStatus());
            hasConfirmedByOriginalId.merge(draft.getRenewedFromContractId(), confirmed, (a, b) -> a || b);
        }
        return hasConfirmedByOriginalId;
    }

    private String deriveState(RenewalCalendarItemDto item, Map<Long, Boolean> hasConfirmedDraftByOriginalId) {
        String decision = item.getRenewalDecision();
        if (RenewalState.DECISION_CONTINUE.equals(decision)) {
            return RenewalState.CONTINUE;
        }
        if (RenewalState.DECISION_END.equals(decision)) {
            return RenewalState.END_SCHEDULED;
        }
        Boolean confirmed = hasConfirmedDraftByOriginalId.get(item.getContractId());
        if (confirmed == null) {
            return RenewalState.UNHANDLED;
        }
        return confirmed ? RenewalState.CONFIRMED : RenewalState.DRAFT;
    }
}
