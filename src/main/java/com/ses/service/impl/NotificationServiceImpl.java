package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.AiLog;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.AiLogMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;
    private final AiLogMapper aiLogMapper;

    @Override
    public List<NotificationDto> getRecentNotifications() {
        List<NotificationDto> notifications = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // 1. 退場予定エンジニア (稼動中で終了日が30日以内)
        QueryWrapper<Contract> contractQw = new QueryWrapper<>();
        contractQw.eq("status", "稼動中")
                .le("end_date", today.plusDays(30))
                .ge("end_date", today);
        List<Contract> contracts = contractMapper.selectList(contractQw);

        if (contracts != null) {
            for (Contract c : contracts) {
                Engineer eng = engineerMapper.selectById(c.getEngineerId());
                if (eng != null) {
                    String name = (eng.getInitialName() != null && !eng.getInitialName().isEmpty()) ? eng.getInitialName() : eng.getFullName();
                    notifications.add(NotificationDto.builder()
                            .type("RETIRING_ENGINEER")
                            .icon("bi-exclamation-triangle text-accent-yellow")
                            .message(name + "氏の退場日が近づいています")
                            .date(c.getEndDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")))
                            .sortDate(c.getEndDate().atStartOfDay())
                            .build());
                }
            }
        }

        // 2. AIマッチング完了通知 (request_type='マッチング' かつ 24時間以内)
        QueryWrapper<AiLog> aiLogQw = new QueryWrapper<>();
        aiLogQw.eq("request_type", "マッチング")
                .ge("created_at", now.minusHours(24));
        List<AiLog> aiLogs = aiLogMapper.selectList(aiLogQw);

        if (aiLogs != null) {
            for (AiLog log : aiLogs) {
                String timeAgo = calculateTimeAgo(log.getCreatedAt(), now);
                notifications.add(NotificationDto.builder()
                        .type("AI_MATCHING")
                        .icon("bi-info-circle text-accent-blue")
                        .message("【AI】マッチング処理が完了しました")
                        .date(timeAgo)
                        .sortDate(log.getCreatedAt())
                        .build());
            }
        }

        return notifications.stream()
                .sorted(Comparator.comparing(NotificationDto::getSortDate).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    private String calculateTimeAgo(LocalDateTime from, LocalDateTime to) {
        if (from == null) return "";
        long hours = ChronoUnit.HOURS.between(from, to);
        if (hours <= 0) {
            long mins = ChronoUnit.MINUTES.between(from, to);
            return mins <= 0 ? "たった今" : mins + "分前";
        }
        return hours + "時間前";
    }
}
