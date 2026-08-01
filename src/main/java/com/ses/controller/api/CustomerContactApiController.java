package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.customer.CustomerContactDto;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.service.CustomerContactService;
import com.ses.common.util.CsvUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 顧客担当者API。画面・CSVとも本APIの公開DTOを使用する。 */
@RestController
@RequestMapping("/api/customers/{customerId}/contacts")
@RequiredArgsConstructor
public class CustomerContactApiController {
    private final CustomerContactService customerContactService;

    @GetMapping
    public ApiResult<List<CustomerContactDto>> list(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.success(customerContactService.list(customerId, asOf));
    }

    @GetMapping("/recipients")
    public ApiResult<List<CustomerContactDto>> recipients(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.success(customerContactService.recipientCandidates(customerId, asOf));
    }

    @PostMapping
    public ApiResult<CustomerContactDto> create(@PathVariable Long customerId,
                                                 @Valid @RequestBody CustomerContactSaveRequest request) {
        return ApiResult.success(customerContactService.create(customerId, request));
    }

    @PutMapping("/{contactId}")
    public ApiResult<CustomerContactDto> update(@PathVariable Long customerId,
                                                 @PathVariable Long contactId,
                                                 @Valid @RequestBody CustomerContactSaveRequest request) {
        return ApiResult.success(customerContactService.update(customerId, contactId, request));
    }

    @PutMapping("/{contactId}/retire")
    public ApiResult<CustomerContactDto> retire(@PathVariable Long customerId,
                                                 @PathVariable Long contactId,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo,
                                                 @RequestParam Integer version) {
        return ApiResult.success(customerContactService.retire(customerId, contactId, validTo, version));
    }

    /** 画面と同じDTO・マスク規則で担当者CSVを出力する（R1.4）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<CustomerContactDto> contacts = customerContactService.list(customerId, asOf);
        StringBuilder csv = new StringBuilder(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(csv, "id", "name", "department", "position", "email", "phone", "primary", "valid_from", "valid_to", "status");
        for (CustomerContactDto c : contacts) {
            CsvUtils.appendLine(csv, value(c.getId()), c.getName(), c.getDepartment(), c.getPosition(),
                    c.getEmail(), c.getPhone(), value(c.getPrimaryFlag()), value(c.getValidFrom()),
                    value(c.getValidTo()), c.getStatus());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("customer-contacts.csv", StandardCharsets.UTF_8).build().toString())
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
