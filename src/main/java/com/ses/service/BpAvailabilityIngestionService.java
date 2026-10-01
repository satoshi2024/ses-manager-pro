package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailabilityIngestion;
import org.springframework.web.multipart.MultipartFile;

public interface BpAvailabilityIngestionService extends IService<BpAvailabilityIngestion> {

    Page<BpAvailabilityIngestion> pageForCurrentTenant(Page<BpAvailabilityIngestion> page, String status);

    BpAvailabilityIngestion getForCurrentTenant(Long id);

    BpAvailabilityIngestion createJob(MultipartFile file);

    BpAvailabilityIngestion createJobFromPaste(String text);

    void parseAsync(Long id);

    /** 非同期実行へtenantを明示的に引き渡す。親threadのThreadLocalには依存しない。 */
    void parseAsync(Long id, String tenantId);

    void reparse(Long id);

    void saveReview(Long id, ReviewedBpAvailabilityDto dto);

    Long confirm(Long id, ReviewedBpAvailabilityDto dto);

    void reject(Long id, String reason);
}
