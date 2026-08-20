package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.CrmNormalize;
import com.ses.dto.customer.CustomerContactDto;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.entity.CustomerContact;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.CustomerContactService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.CrmScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomerContactServiceImpl implements CustomerContactService {
    private static final Set<String> ALLOWED_ROLES = Set.of("決裁者", "現場", "調達", "請求", "契約");
    private static final Set<String> ALLOWED_STATUSES = Set.of("有効", "退職", "異動");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PII_VIEW_ACTION = "customer.pii.view";
    private final CustomerContactMapper mapper;
    private final CustomerMapper customerMapper;
    private final DataScopeService dataScopeService;
    private final com.ses.service.security.AuthorizationService authorizationService;
    @Autowired(required = false)
    private Clock clock = Clock.systemDefaultZone();
    @Autowired(required = false)
    private CrmScopeService crmScopeService;

    public CustomerContactServiceImpl(CustomerContactMapper mapper, CustomerMapper customerMapper,
                                      DataScopeService dataScopeService,
                                      com.ses.service.security.AuthorizationService authorizationService,
                                      Clock clock) {
        this.mapper = mapper;
        this.customerMapper = customerMapper;
        this.dataScopeService = dataScopeService;
        this.authorizationService = authorizationService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public List<CustomerContactDto> list(Long customerId, LocalDate asOf) {
        assertCustomerScope(customerId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
        return mapper.selectList(new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId)
                        .le(CustomerContact::getValidFrom, target)
                        .and(w -> w.isNull(CustomerContact::getValidTo)
                                .or().ge(CustomerContact::getValidTo, target))
                        .orderByDesc(CustomerContact::getValidFrom)
                        .orderByDesc(CustomerContact::getId))
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public List<CustomerContactDto> duplicateCandidates(Long customerId, String email, String phone, Long excludeId) {
        assertCustomerScope(customerId);
        if (!hasText(email) && !hasText(phone)) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId)
                        .eq(CustomerContact::getStatus, "有効")
                        .last("LIMIT 100"))
                .stream()
                .filter(c -> !Objects.equals(c.getId(), excludeId)
                        && (sameEmail(email, c.getEmail()) || samePhone(phone, c.getPhone())))
                .limit(20)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerContactDto> recipientCandidates(Long customerId, LocalDate asOf, String role) {
        assertCustomerScope(customerId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
        LambdaQueryWrapper<CustomerContact> query = new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId)
                        .eq(CustomerContact::getStatus, "有効")
                        .le(CustomerContact::getValidFrom, target)
                        .and(w -> w.isNull(CustomerContact::getValidTo)
                                .or().ge(CustomerContact::getValidTo, target))
                        .isNotNull(CustomerContact::getEmail)
                        .ne(CustomerContact::getEmail, "")
                        .orderByDesc(CustomerContact::getPrimaryFlag)
                        .orderByAsc(CustomerContact::getId);
        if (role != null && !role.isBlank()) {
            if (!ALLOWED_ROLES.contains(role)) {
                throw BusinessException.of(400, "error.crm.contactRoleInvalid");
            }
            query.like(CustomerContact::getRolesJson, "\"" + role + "\"");
        }
        return mapper.selectList(query)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerContactDto create(Long customerId, CustomerContactSaveRequest request) {
        assertCustomerScope(customerId);
        validateStatus(request.getStatus());
        validatePeriod(request.getValidFrom(), request.getValidTo());
        lockCustomerContacts(customerId);
        validatePrimaryPeriod(customerId, null, request.getPrimaryFlag(), request.getStatus(),
                request.getValidFrom(), request.getValidTo());

        CustomerContact contact = new CustomerContact();
        contact.setCustomerId(customerId);
        apply(contact, request);
        if (contact.getPrimaryFlag() == null) contact.setPrimaryFlag(0);
        mapper.insert(contact);
        return toDto(mapper.selectById(contact.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerContactDto update(Long customerId, Long contactId, CustomerContactSaveRequest request) {
        assertCustomerScope(customerId);
        validateStatus(request.getStatus());
        validatePeriod(request.getValidFrom(), request.getValidTo());
        lockCustomerContacts(customerId);
        CustomerContact current = lockOwned(customerId, contactId);
        if (request.getVersion() == null || !Objects.equals(request.getVersion(), current.getVersion())) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        validatePrimaryPeriod(customerId, contactId, request.getPrimaryFlag(), request.getStatus(),
                request.getValidFrom(), request.getValidTo());

        UpdateWrapper<CustomerContact> update = new UpdateWrapper<CustomerContact>()
                .eq("id", contactId)
                .eq("customer_id", customerId)
                .eq("version", current.getVersion());
        update.set("name", request.getName())
                .set("name_kana", request.getNameKana())
                .set("department", request.getDepartment())
                .set("position", request.getPosition())
                .set("roles_json", normalizeRolesJson(request.getRolesJson()))
                .set("email",preserveMasked(current.getEmail(), request.getEmail(), true))
                .set("phone",preserveMasked(current.getPhone(), request.getPhone(), false))
                .set("primary_flag", request.getPrimaryFlag() == null ? 0 : request.getPrimaryFlag())
                .set("valid_from", request.getValidFrom())
                .set("valid_to", request.getValidTo())
                .set("status", request.getStatus())
                .set("version", current.getVersion() + 1);
        if (mapper.update(null, update) != 1) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        CustomerContact updated = mapper.selectById(contactId);
        return toDto(updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerContactDto retire(Long customerId, Long contactId, LocalDate validTo, Integer version) {
        assertCustomerScope(customerId);
        CustomerContact current = lockOwned(customerId, contactId);
        LocalDate end = validTo != null ? validTo : LocalDate.now(clock);
        if (end.isBefore(current.getValidFrom())) {
            throw BusinessException.of("error.crm.contactPeriodInvalid");
        }
        if (version == null || !Objects.equals(version, current.getVersion())) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        UpdateWrapper<CustomerContact> update = new UpdateWrapper<CustomerContact>()
                .eq("id", contactId).eq("customer_id", customerId).eq("version", current.getVersion())
                .set("status", "退職").set("valid_to", end).set("primary_flag", 0)
                .set("version", current.getVersion() + 1);
        if (mapper.update(null, update) != 1) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        return toDto(mapper.selectById(contactId));
    }

    @Override
    public String resolveRecipientEmail(Long customerId, Long contactId, LocalDate asOf) {
        assertCustomerScope(customerId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
        CustomerContact contact = mapper.selectOne(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getId, contactId)
                .eq(CustomerContact::getCustomerId, customerId)
                .eq(CustomerContact::getStatus, "有効")
                .le(CustomerContact::getValidFrom, target)
                .and(w -> w.isNull(CustomerContact::getValidTo).or().ge(CustomerContact::getValidTo, target))
                .isNotNull(CustomerContact::getEmail)
                .ne(CustomerContact::getEmail, ""));
        if (contact == null) {
            throw BusinessException.of("error.invoice.recipientContactUnavailable");
        }
        return contact.getEmail();
    }

    @Override
    public CustomerContact getOwnedOrThrow(Long customerId, Long contactId) {
        assertCustomerScope(customerId);
        CustomerContact contact = mapper.selectOne(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getId, contactId)
                .eq(CustomerContact::getCustomerId, customerId));
        if (contact == null) throw BusinessException.of(404, "error.crm.contactNotFound");
        return contact;
    }

    private CustomerContact lockOwned(Long customerId, Long contactId) {
        CustomerContact contact = mapper.selectOne(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getId, contactId)
                .eq(CustomerContact::getCustomerId, customerId)
                .last("FOR UPDATE"));
        if (contact == null) throw BusinessException.of(404, "error.crm.contactNotFound");
        return contact;
    }

    private void lockCustomerContacts(Long customerId) {
        if (customerMapper.selectByIdForUpdate(customerId) == null) {
            throw BusinessException.of(404, "error.crm.customerNotFound");
        }
        mapper.selectList(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getCustomerId, customerId).orderByAsc(CustomerContact::getId).last("FOR UPDATE"));
    }

    private void validatePrimaryPeriod(Long customerId, Long excludedId, Integer primaryFlag, String status,
                                       LocalDate from, LocalDate to) {
        if (!Integer.valueOf(1).equals(primaryFlag) || !"有効".equals(status)) return;
        List<CustomerContact> contacts = mapper.selectList(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getCustomerId, customerId)
                .eq(CustomerContact::getPrimaryFlag, 1)
                .eq(CustomerContact::getStatus, "有効")
                .and(w -> w.isNull(CustomerContact::getValidTo).or().ge(CustomerContact::getValidTo, from))
                .le(CustomerContact::getValidFrom, to == null ? LocalDate.of(9999, 12, 31) : to));
        boolean overlap = contacts.stream().anyMatch(c -> !Objects.equals(c.getId(), excludedId)
                && overlaps(c.getValidFrom(), c.getValidTo(), from, to));
        if (overlap) throw BusinessException.of("error.crm.primaryContactOverlap");
    }

    private boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return !aFrom.isAfter(bToOrMax(bTo)) && !bFrom.isAfter(bToOrMax(aTo));
    }

    private LocalDate bToOrMax(LocalDate date) {
        return date != null ? date : LocalDate.of(9999, 12, 31);
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || (to != null && to.isBefore(from))) {
            throw BusinessException.of("error.crm.contactPeriodInvalid");
        }
    }

    private void apply(CustomerContact contact, CustomerContactSaveRequest request) {
        contact.setName(request.getName());
        contact.setNameKana(request.getNameKana());
        contact.setDepartment(request.getDepartment());
        contact.setPosition(request.getPosition());
        contact.setRolesJson(normalizeRolesJson(request.getRolesJson()));
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());
        contact.setPrimaryFlag(request.getPrimaryFlag() == null ? 0 : request.getPrimaryFlag());
        contact.setValidFrom(request.getValidFrom());
        contact.setValidTo(request.getValidTo());
        contact.setStatus(request.getStatus());
    }

    private void assertCustomerScope(Long customerId) {
        if (crmScopeService != null) {
            crmScopeService.assertAllowedCustomer(customerId, LocalDate.now(clock));
        } else {
            dataScopeService.assertAllowedCustomer(customerId);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sameEmail(String left, String right) {
        String a = CrmNormalize.contactKey(left);
        String b = CrmNormalize.contactKey(right);
        return a != null && a.equals(b);
    }

    private boolean samePhone(String left, String right) {
        String a = CrmNormalize.contactKey(left);
        String b = CrmNormalize.contactKey(right);
        return a != null && a.equals(b);
    }

    /** 非管理者の表示マスクを更新値として書き戻さず、明示的な空欄はクリアとして扱う。 */
    private String preserveMasked(String current, String requested, boolean email) {
        if (canViewPii() || current == null) return requested;
        String masked = maskPii(current, email);
        return masked.equals(requested) ? current : requested;
    }

    private CustomerContactDto toDto(CustomerContact contact) {
        CustomerContactDto dto = new CustomerContactDto();
        dto.setId(contact.getId());
        dto.setCustomerId(contact.getCustomerId());
        dto.setName(contact.getName());
        dto.setNameKana(contact.getNameKana());
        dto.setDepartment(contact.getDepartment());
        dto.setPosition(contact.getPosition());
        dto.setRolesJson(contact.getRolesJson());
        dto.setEmail(maskPii(contact.getEmail(), true));
        dto.setPhone(maskPii(contact.getPhone(), false));
        dto.setPrimaryFlag(contact.getPrimaryFlag());
        dto.setValidFrom(contact.getValidFrom());
        dto.setValidTo(contact.getValidTo());
        dto.setStatus(contact.getStatus());
        dto.setVersion(contact.getVersion());
        return dto;
    }

    private String maskPii(String value, boolean email) {
        if (value == null || value.isBlank() || canViewPii()) return value;
        if (email) {
            int at = value.indexOf('@');
            return at > 1 ? value.charAt(0) + "***" + value.substring(at) : "***";
        }
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }

    private boolean canViewPii() {
        return authorizationService != null && authorizationService.isAllowed(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(),
                PII_VIEW_ACTION);
    }

    private void validateStatus(String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw BusinessException.of(400, "error.crm.contactStatusInvalid");
        }
    }

    private String normalizeRolesJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isArray()) throw new IllegalArgumentException("roles_json must be an array");
            LinkedHashSet<String> roles = new LinkedHashSet<>();
            for (JsonNode item : node) {
                if (!item.isTextual() || !ALLOWED_ROLES.contains(item.textValue())) {
                    throw new IllegalArgumentException("unknown contact role");
                }
                roles.add(item.textValue());
            }
            return JSON.writeValueAsString(roles);
        } catch (Exception e) {
            throw BusinessException.of(400, "error.crm.contactRolesInvalid");
        }
    }
}
