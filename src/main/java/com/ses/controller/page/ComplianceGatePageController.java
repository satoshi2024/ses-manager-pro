package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 派遣コンプライアンスG2 gateページ（R23-P1-01 §5）。
 * tabs: Mapping / Reviewer Type / Review Policy / Assignment / Internal Approval /
 * External Review / 本人・資格・作成者確認 / ACTIVE / Event History。
 * capabilityはserver計算し（/api/compliance-gate/capabilities）、JS role判定はauthorizationに使わない。
 */
@Controller
@RequestMapping("/compliance-gate")
public class ComplianceGatePageController {

    @GetMapping
    public String index() {
        return "compliance/gate";
    }
}
