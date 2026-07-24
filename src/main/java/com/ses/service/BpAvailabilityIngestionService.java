package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailabilityIngestion;
import org.springframework.web.multipart.MultipartFile;

public interface BpAvailabilityIngestionService extends IService<BpAvailabilityIngestion> {

    BpAvailabilityIngestion createJob(MultipartFile file);

    BpAvailabilityIngestion createJobFromPaste(String text);

    void parseAsync(Long id);

    void reparse(Long id);

    void saveReview(Long id, ReviewedBpAvailabilityDto dto);

    Long confirm(Long id, ReviewedBpAvailabilityDto dto);

    void reject(Long id, String reason);
}
