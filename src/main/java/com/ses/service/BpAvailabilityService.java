package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;

public interface BpAvailabilityService extends IService<BpAvailability> {
    Page<BpAvailability> pageForCurrentTenant(Page<BpAvailability> page, String status);

    BpAvailability getForCurrentTenant(Long id);

    boolean updateForCurrentTenant(Long id, BpAvailability availability);

    boolean removeForCurrentTenant(Long id);

    Engineer promoteToEngineer(Long id);

    /**
     * portal提出（未確認）の内部review（S13-R1-P2-10: 状態CASで二重review競合を防ぐ）。
     * approved=true → 提案可能、false → 却下。対象が未確認でなければ409。
     */
    void review(Long id, boolean approved, String comment);
}
