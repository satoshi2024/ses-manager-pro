package com.ses.controller.api;

import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import com.ses.service.WorkRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/work-records")
@RequiredArgsConstructor
public class WorkRecordApiController {

    private final WorkRecordService workRecordService;

    @GetMapping("/grid")
    public List<WorkRecordGridDto> getGrid(@RequestParam String month) {
        return workRecordService.monthlyGrid(month);
    }

    @PutMapping
    public WorkRecord saveHours(@RequestBody Map<String, Object> body) {
        Long contractId = Long.valueOf(body.get("contractId").toString());
        String workMonth = body.get("workMonth").toString();
        BigDecimal actualHours = new BigDecimal(body.get("actualHours").toString());
        String remarks = body.get("remarks") != null ? body.get("remarks").toString() : null;

        return workRecordService.saveHours(contractId, workMonth, actualHours, remarks);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmMonth(@RequestParam String month) {
        workRecordService.confirmMonth(month);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasRole('管理者')")
    public ResponseEntity<Void> reopenMonth(@RequestParam String month) {
        workRecordService.reopenMonth(month);
        return ResponseEntity.ok().build();
    }
}
