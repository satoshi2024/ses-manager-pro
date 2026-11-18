package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.service.servicedesk.ServiceRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * サービスデスクCSV出力の打ち切り表示（RB-NF02 review P3-5）。上限（1000件）で打ち切った時だけ、
 * 利用者のlocaleの文言で末尾に注記行を出す。文言は同梱のbundle（{@code messages*.properties}）から引く。
 */
@ExtendWith(MockitoExtension.class)
class ServiceRequestExportServiceImplTest {

    @Mock
    private ServiceRequestService serviceRequestService;

    private ServiceRequestExportServiceImpl exportService;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        exportService = new ServiceRequestExportServiceImpl(serviceRequestService, messageSource);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void 上限で打ち切った時は末尾に利用者のlocaleで注記行を出す() {
        when(serviceRequestService.searchInternalRequests(eq(1), eq(1000), any(), any(), any(), any(), any()))
                .thenReturn(page(1000, 1234));

        LocaleContextHolder.setLocale(Locale.JAPANESE);
        List<String> ja = exportLines();
        assertEquals(1 + 1000 + 1, ja.size(), "ヘッダー + 1000件 + 注記1行");
        assertEquals("\"※出力上限（1000件）に達したため、該当する1234件のうち新しい順に1000件だけを出力しました。"
                + "検索条件を絞って再度出力してください。\"", ja.get(ja.size() - 1));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<String> en = exportLines();
        assertEquals(1 + 1000 + 1, en.size());
        assertTrue(en.get(en.size() - 1).contains("only the newest 1000 of 1234 matching inquiries"),
                en.get(en.size() - 1));
    }

    @Test
    void 上限以内なら注記行を出さない() {
        when(serviceRequestService.searchInternalRequests(eq(1), eq(1000), any(), any(), any(), any(), any()))
                .thenReturn(page(3, 3));
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        List<String> lines = exportLines();

        assertEquals(1 + 3, lines.size(), "ヘッダー + 3件");
        assertTrue(lines.stream().noneMatch(line -> line.contains("出力上限")), String.join("\n", lines));
    }

    private List<String> exportLines() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.exportRequestsToCsv(out, null, null, null, null, null);
        byte[] bytes = out.toByteArray();
        // 先頭3byteはUTF-8 BOM
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).lines().toList();
    }

    private Page<ServiceRequestDto> page(int records, long total) {
        List<ServiceRequestDto> rows = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            rows.add(ServiceRequestDto.builder().id((long) i + 1).requestNo("REQ-" + (i + 1)).build());
        }
        Page<ServiceRequestDto> page = new Page<>(1, 1000, total);
        page.setRecords(rows);
        return page;
    }
}
