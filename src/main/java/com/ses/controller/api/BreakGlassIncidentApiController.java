package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.security.BreakGlassIncidentRequest;
import com.ses.entity.BreakGlassIncident;
import com.ses.service.security.BreakGlassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security/break-glass/incidents")
@PreAuthorize("hasRole('管理者')")
@RequiredArgsConstructor
public class BreakGlassIncidentApiController {

    private final BreakGlassService breakGlassService;

    @PostMapping
    public ApiResult<BreakGlassIncident> create(@Valid @RequestBody BreakGlassIncidentRequest request) {
        return ApiResult.success(breakGlassService.create(actorId(), request.reason(),
                Boolean.TRUE.equals(request.idpOutageConfirmed()), request.durationMinutes(), request.correlationId()));
    }

    @PostMapping("/{id}/approve")
    public ApiResult<BreakGlassIncident> approve(@PathVariable Long id) {
        return ApiResult.success(breakGlassService.approve(actorId(), id));
    }

    @PostMapping("/{id}/close")
    public ApiResult<Boolean> close(@PathVariable Long id) {
        breakGlassService.close(actorId(), id);
        return ApiResult.success(true);
    }

    private Long actorId() {
        Long id = SecurityUtils.currentUserId();
        if (id == null) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return id;
    }
}
