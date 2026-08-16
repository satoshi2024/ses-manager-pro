package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.mapper.StaffingScenarioAllocationMapper;
import com.ses.mapper.StaffingScenarioMapper;
import com.ses.service.staffing.StaffingClock;
import com.ses.service.staffing.StaffingScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * 仮配置シナリオの実装（R3.3・scenario isolation）。
 *
 * <p>本serviceは {@code t_staffing_scenario} / {@code t_staffing_scenario_allocation} のみを
 * 更新し、実データ（t_allocation_plan・契約・提案）へ書き込む経路を持たない。
 * 可視性: owner本人 ＋ shared_flag=1 は閲覧可（組織scopeのfilterはB2で実装）。
 */
@Service
@RequiredArgsConstructor
public class StaffingScenarioServiceImpl implements StaffingScenarioService {

    private final StaffingScenarioMapper scenarioMapper;
    private final StaffingScenarioAllocationMapper allocationMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectPositionMapper positionMapper;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeProvider;
    private final StaffingClock clock;

    @Override
    @Transactional
    public StaffingScenario create(StaffingScenario scenario) {
        Long owner = SecurityUtils.currentUserId();
        if (owner == null) {
            throw BusinessException.of(401, "error.authFailed");
        }
        if (scenario.getName() == null || scenario.getName().isBlank()) {
            throw BusinessException.of(400, "error.staffing.scenarioNameRequired");
        }
        if (scenario.getBaseDate() == null) {
            // 未指定時はtenantタイムゾーンの"今日"を基準日にする（S12-R1-P2-02）
            scenario.setBaseDate(clock.today());
        }
        scenario.setId(null);
        scenario.setOwnerUserId(owner);
        scenario.setSharedFlag(scenario.getSharedFlag() == null ? 0 : scenario.getSharedFlag());
        scenarioMapper.insert(scenario);
        return scenario;
    }

    @Override
    @Transactional
    public StaffingScenario update(StaffingScenario scenario) {
        if (scenario.getId() == null) {
            throw BusinessException.of(404, "error.staffing.scenarioNotFound");
        }
        StaffingScenario existing = requireEditable(scenario.getId());
        existing.setName(scenario.getName());
        existing.setBaseDate(scenario.getBaseDate());
        existing.setSharedFlag(scenario.getSharedFlag() == null ? 0 : scenario.getSharedFlag());
        existing.setAssumptionsJson(scenario.getAssumptionsJson());
        if (existing.getName() == null || existing.getName().isBlank()) {
            throw BusinessException.of(400, "error.staffing.scenarioNameRequired");
        }
        if (existing.getBaseDate() == null) {
            throw BusinessException.of(400, "error.staffing.baseDateRequired");
        }
        scenarioMapper.updateById(existing);
        return scenarioMapper.selectById(existing.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        StaffingScenario scenario = requireEditable(id);
        scenarioMapper.deleteById(scenario.getId());
        allocationMapper.delete(new LambdaQueryWrapper<StaffingScenarioAllocation>()
                .eq(StaffingScenarioAllocation::getScenarioId, scenario.getId()));
    }

    @Override
    public StaffingScenario get(Long id) {
        StaffingScenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw BusinessException.of(404, "error.staffing.scenarioNotFound");
        }
        requireVisible(scenario);
        return scenario;
    }

    @Override
    public List<StaffingScenario> listVisible() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<StaffingScenario> query = new LambdaQueryWrapper<>();
        query.eq(StaffingScenario::getOwnerUserId, userId)
                .or(w -> {
                    w.eq(StaffingScenario::getSharedFlag, 1);
                    // 組織scope有効（マネージャー）では、共有scenarioはownerが組織scope配下の
                    // ユーザーのものだけ表示する（design §5.3・S12-R1-P1-02）
                    com.ses.service.security.OrganizationScopeService scope =
                            organizationScopeProvider.getIfAvailable();
                    if (scope != null && !scope.hasFullAccess()) {
                        Set<Long> allowedUsers = scope.allowedUserIds(LocalDate.now());
                        if (allowedUsers.isEmpty()) {
                            w.apply("1 = 0");
                        } else {
                            w.in(StaffingScenario::getOwnerUserId, allowedUsers);
                        }
                    }
                })
                .orderByDesc(StaffingScenario::getUpdatedAt);
        return scenarioMapper.selectList(query);
    }

    @Override
    @Transactional
    public StaffingScenarioAllocation upsertAllocation(StaffingScenarioAllocation allocation) {
        StaffingScenario scenario = requireVisibleOf(allocation.getScenarioId());
        if (allocation.getEngineerId() == null || engineerMapper.selectById(allocation.getEngineerId()) == null) {
            throw BusinessException.of(400, "error.staffing.engineerRequired");
        }
        if (allocation.getPercent() == null
                || allocation.getPercent().compareTo(java.math.BigDecimal.ZERO) <= 0
                || allocation.getPercent().compareTo(new java.math.BigDecimal("100")) > 0) {
            throw BusinessException.of(400, "error.staffing.invalidPercent");
        }
        if (allocation.getPositionId() != null
                && positionMapper.selectById(allocation.getPositionId()) == null) {
            throw BusinessException.of(404, "error.staffing.positionNotFound");
        }
        String datesJson = normalizeDates(scenario, allocation.getDates());

        if (allocation.getId() == null) {
            allocation.setScenarioId(scenario.getId());
            allocation.setDates(datesJson);
            allocationMapper.insert(allocation);
        } else {
            StaffingScenarioAllocation existing = allocationMapper.selectById(allocation.getId());
            if (existing == null || !scenario.getId().equals(existing.getScenarioId())) {
                throw BusinessException.of(404, "error.staffing.scenarioAllocationNotFound");
            }
            existing.setEngineerId(allocation.getEngineerId());
            existing.setPositionId(allocation.getPositionId());
            existing.setDates(datesJson);
            existing.setPercent(allocation.getPercent());
            allocationMapper.updateById(existing);
        }
        return allocationMapper.selectById(allocation.getId());
    }

    @Override
    @Transactional
    public void deleteAllocation(Long allocationId) {
        StaffingScenarioAllocation allocation = allocationMapper.selectById(allocationId);
        if (allocation == null) {
            throw BusinessException.of(404, "error.staffing.scenarioAllocationNotFound");
        }
        requireVisibleOf(allocation.getScenarioId());
        allocationMapper.deleteById(allocationId);
    }

    @Override
    public List<StaffingScenarioAllocation> listAllocations(Long scenarioId) {
        requireVisibleOf(scenarioId);
        return allocationMapper.selectList(new LambdaQueryWrapper<StaffingScenarioAllocation>()
                .eq(StaffingScenarioAllocation::getScenarioId, scenarioId)
                .orderByAsc(StaffingScenarioAllocation::getId));
    }

    // ---------------------------------------------------------------
    // 可視性（design §5.3の最小gate。組織scopeのfilterはB2で実装）
    // ---------------------------------------------------------------

    private void requireVisible(StaffingScenario scenario) {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null && userId.equals(scenario.getOwnerUserId())) {
            return;
        }
        if (Integer.valueOf(1).equals(scenario.getSharedFlag())) {
            return;
        }
        throw BusinessException.of(403, "error.staffing.scenarioForbidden");
    }

    private StaffingScenario requireVisibleOf(Long scenarioId) {
        StaffingScenario scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw BusinessException.of(404, "error.staffing.scenarioNotFound");
        }
        requireVisible(scenario);
        return scenario;
    }

    /** 編集（ownerのみ。共有scenarioの編集はownerに限定）。 */
    private StaffingScenario requireEditable(Long scenarioId) {
        StaffingScenario scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw BusinessException.of(404, "error.staffing.scenarioNotFound");
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId == null || !userId.equals(scenario.getOwnerUserId())) {
            throw BusinessException.of(403, "error.staffing.scenarioForbidden");
        }
        return scenario;
    }

    // ---------------------------------------------------------------
    // dates検証（ISO日付のJSON配列・昇順・重複なし・[base_date, +24か月]）
    // ---------------------------------------------------------------

    private String normalizeDates(StaffingScenario scenario, String datesJson) {
        if (datesJson == null || datesJson.isBlank()) {
            throw BusinessException.of(400, "error.staffing.invalidDates");
        }
        List<String> raw;
        try {
            raw = objectMapper.readValue(datesJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw BusinessException.of(400, "error.staffing.invalidDates");
        }
        if (raw == null || raw.isEmpty()) {
            throw BusinessException.of(400, "error.staffing.invalidDates");
        }
        LocalDate base = scenario.getBaseDate();
        LocalDate max = base.plusMonths(StaffingClock.HORIZON_MONTHS);
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        for (String value : raw) {
            LocalDate day;
            try {
                day = LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw BusinessException.of(400, "error.staffing.invalidDates");
            }
            if (day.isBefore(base) || day.isAfter(max)) {
                throw BusinessException.of(400, "error.staffing.horizonExceeded");
            }
            dates.add(day);
        }
        return dates.stream().map(d -> "\"" + d + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
