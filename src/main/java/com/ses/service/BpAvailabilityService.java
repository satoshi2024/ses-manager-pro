package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;

public interface BpAvailabilityService extends IService<BpAvailability> {
    Engineer promoteToEngineer(Long id);

    /**
     * portal提出（未確認）の内部review（S13-R1-P2-10: 状態CASで二重review競合を防ぐ）。
     * approved=true → 提案可能、false → 却下。対象が未確認でなければ409。
     */
    void review(Long id, boolean approved, String comment);
}
