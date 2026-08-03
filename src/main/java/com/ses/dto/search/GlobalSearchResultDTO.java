package com.ses.dto.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 横断検索結果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchResultDTO {
    private String type;
    private Long id;
    private String title;
    private String subtitle;
    private String status;
    private String url;
    private LocalDateTime updatedAt;
}
