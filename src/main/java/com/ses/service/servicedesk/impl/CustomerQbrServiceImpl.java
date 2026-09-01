package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.servicedesk.CustomerQbrCreateRequest;
import com.ses.dto.servicedesk.CustomerQbrDto;
import com.ses.dto.servicedesk.CustomerQbrUpdateRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerQbr;
import com.ses.entity.SysUser;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerQbrMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.servicedesk.CustomerQbrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerQbrServiceImpl implements CustomerQbrService {

    private final CustomerQbrMapper qbrMapper;
    private final CustomerMapper customerMapper;
    private final SysUserMapper sysUserMapper;
    private final DataScopeService dataScopeService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerQbrDto> searchQbrs(int page, int size, Long customerId, String keyword) {
        Page<CustomerQbr> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<CustomerQbr> wrapper = new LambdaQueryWrapper<>();

        if (customerId != null) {
            if (dataScopeService.isScoped()) {
                dataScopeService.assertAllowedCustomer(customerId);
            }
            wrapper.eq(CustomerQbr::getCustomerId, customerId);
        } else if (dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed == null || allowed.isEmpty()) {
                return new Page<>(page, size, 0);
            }
            wrapper.in(CustomerQbr::getCustomerId, allowed);
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(CustomerQbr::getTitle, keyword)
                    .or().like(CustomerQbr::getAgenda, keyword)
                    .or().like(CustomerQbr::getDiscussion, keyword));
        }

        wrapper.orderByDesc(CustomerQbr::getMeetingDate);

        Page<CustomerQbr> result = qbrMapper.selectPage(mpPage, wrapper);
        List<CustomerQbrDto> dtos = result.getRecords().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        Page<CustomerQbrDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(dtos);
        return dtoPage;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerQbrDto getQbr(Long id) {
        CustomerQbr qbr = qbrMapper.selectById(id);
        if (qbr == null) {
            throw BusinessException.of(404, "指定された定例会記録が見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(qbr.getCustomerId());
        }
        return convertToDto(qbr);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerQbrDto createQbr(CustomerQbrCreateRequest req, Long actorUserId) {
        Customer customer = customerMapper.selectById(req.getCustomerId());
        if (customer == null || Integer.valueOf(1).equals(customer.getDeletedFlag())) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(req.getCustomerId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Long effectiveActor = actorUserId != null ? actorUserId : SecurityUtils.currentUserId();

        CustomerQbr qbr = CustomerQbr.builder()
                .customerId(req.getCustomerId())
                .meetingDate(req.getMeetingDate())
                .title(req.getTitle())
                .agenda(req.getAgenda())
                .discussion(req.getMinutes())
                .decisions(req.getActionItems())
                .attendees(req.getAttendees())
                .createdBy(effectiveActor)
                .createdAt(now)
                .updatedAt(now)
                .build();

        qbrMapper.insert(qbr);
        return convertToDto(qbr);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQbr(Long id, CustomerQbrUpdateRequest req) {
        CustomerQbr existing = qbrMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定された定例会記録が見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        existing.setMeetingDate(req.getMeetingDate());
        existing.setTitle(req.getTitle());
        existing.setAgenda(req.getAgenda());
        existing.setDiscussion(req.getMinutes());
        existing.setDecisions(req.getActionItems());
        existing.setAttendees(req.getAttendees());
        existing.setUpdatedBy(SecurityUtils.currentUserId());
        existing.setUpdatedAt(LocalDateTime.now(clock));

        qbrMapper.updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteQbr(Long id) {
        CustomerQbr existing = qbrMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定された定例会記録が見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }
        qbrMapper.deleteById(id);
    }

    private CustomerQbrDto convertToDto(CustomerQbr qbr) {
        Customer c = customerMapper.selectById(qbr.getCustomerId());
        String customerName = c != null ? c.getCompanyName() : "顧客#" + qbr.getCustomerId();

        String createdByName = "システム";
        if (qbr.getCreatedBy() != null) {
            SysUser u = sysUserMapper.selectById(qbr.getCreatedBy());
            if (u != null && u.getRealName() != null) {
                createdByName = u.getRealName();
            }
        }

        return CustomerQbrDto.builder()
                .id(qbr.getId())
                .customerId(qbr.getCustomerId())
                .customerName(customerName)
                .meetingDate(qbr.getMeetingDate())
                .title(qbr.getTitle())
                .agenda(qbr.getAgenda())
                .minutes(qbr.getDiscussion())
                .actionItems(qbr.getDecisions())
                .attendees(qbr.getAttendees())
                .createdBy(qbr.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(qbr.getCreatedAt())
                .updatedAt(qbr.getUpdatedAt())
                .build();
    }
}
