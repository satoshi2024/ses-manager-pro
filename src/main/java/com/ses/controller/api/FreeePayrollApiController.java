package com.ses.controller.api;
import com.ses.common.result.ApiResult;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.service.FreeeIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/payroll") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','HR')")
public class FreeePayrollApiController {
 private final FreeeIntegrationService service;
 @GetMapping("/status") public ApiResult<Boolean> status(){return ApiResult.success(service.connected());}
 @GetMapping("/employees") public ApiResult<List<FreeeEmployeeDto>> employees(){return ApiResult.success(service.employees());}
 @PutMapping("/links/{engineerId}") public ApiResult<Boolean> link(@PathVariable Long engineerId,@RequestParam String employeeId){service.link(engineerId,employeeId,null);return ApiResult.success(true);}
 @DeleteMapping("/links/{engineerId}") public ApiResult<Boolean> unlink(@PathVariable Long engineerId){service.unlink(engineerId);return ApiResult.success(true);}
 @GetMapping("/statements") public ResponseEntity<ApiResult<List<PayrollStatementDto>>> statements(@RequestParam int year,@RequestParam int month,@RequestParam(defaultValue="salary") String type){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(service.statements(year,month,type)));}
}
