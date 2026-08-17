package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.oneonone.OneOnOneRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 要員ポータル（1on1）API。本人scopeはengineer-account linkから解決（design §3）。
 */
@RestController
@RequestMapping("/api/my/one-on-ones")
@RequiredArgsConstructor
public class MyOneOnOneApiController {

    private final EngineerAccountLinkService linkService;
    private final OneOnOneRequestService oneOnOneService;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping
    public ApiResult<Page<OneOnOneRequestService.OneOnOneDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(oneOnOneService.pageOwn(currentEngineerId(), status, current, size));
    }

    @GetMapping("/{id}")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> detail(@PathVariable Long id) {
        return ApiResult.success(oneOnOneService.detailOwn(currentEngineerId(), id));
    }

    @PostMapping
    public ApiResult<OneOnOneRequestService.OneOnOneDto> create(@RequestBody CreateRequest request) {
        return ApiResult.success(oneOnOneService.create(currentEngineerId(),
                request == null ? null : request.getCounterpartUserId(),
                request == null ? null : request.getCandidateDates()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> cancel(@PathVariable Long id) {
        return ApiResult.success(oneOnOneService.cancelOwn(currentEngineerId(), id));
    }

    public static class CreateRequest {
        private Long counterpartUserId;
        private List<LocalDate> candidateDates;

        public Long getCounterpartUserId() {
            return counterpartUserId;
        }

        public void setCounterpartUserId(Long counterpartUserId) {
            this.counterpartUserId = counterpartUserId;
        }

        public List<LocalDate> getCandidateDates() {
            return candidateDates;
        }

        public void setCandidateDates(List<LocalDate> candidateDates) {
            this.candidateDates = candidateDates;
        }
    }
}
