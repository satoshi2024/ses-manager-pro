package com.ses.service.impl;

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
import com.ses.service.accounting.AccountingTenantContextHolder;
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
    private final com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    @Autowired(required = false)
    private Clock clock = Clock.systemDefaultZone();
    @Autowired(required = false)
    private CrmScopeService crmScopeService;

    public CustomerContactServiceImpl(CustomerContactMapper mapper, CustomerMapper customerMapper,
                                      DataScopeService dataScopeService,
                                      com.ses.service.security.AuthorizationService authorizationService,
                                      Clock clock,
                                      com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver) {
        this.mapper = mapper;
        this.customerMapper = customerMapper;
        this.dataScopeService = dataScopeService;
        this.authorizationService = authorizationService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.tenantOwnershipResolver = tenantOwnershipResolver;
    }

    @Override
    public List<CustomerContactDto> list(Long customerId, LocalDate asOf) {
        assertCustomerScope(customerId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
        return mapper.selectListForTenant(customerId, currentTenant(), null, target, null)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public List<CustomerContactDto> duplicateCandidates(Long customerId, String email, String phone, Long excludeId) {
        assertCustomerScope(customerId);
        if (!hasText(email) && !hasText(phone)) return List.of();
        return mapper.selectListForTenant(customerId, currentTenant(), "有効", null, null)
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
        if (role != null && !role.isBlank()) {
            if (!ALLOWED_ROLES.contains(role)) {
                throw BusinessException.of(400, "error.crm.contactRoleInvalid");
            }
        }
        return mapper.selectListForTenant(customerId, currentTenant(), "有効", target, role)
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
        return toDto(mapper.selectByIdForTenant(contact.getId(), customerId, currentTenant()));
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

        String tenantId = currentTenant();
        String rolesJson = normalizeRolesJson(request.getRolesJson());
        String email = preserveMasked(current.getEmail(), request.getEmail(), true);
        String phone = preserveMasked(current.getPhone(), request.getPhone(), false);
        Integer primaryFlag = request.getPrimaryFlag() == null ? 0 : request.getPrimaryFlag();
        if (mapper.updateByIdForTenant(contactId, customerId, tenantId, current.getVersion(), request,
                rolesJson, email, phone, primaryFlag) != 1) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        CustomerContact updated = mapper.selectByIdForTenant(contactId, customerId, currentTenant());
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
        if (mapper.retireForTenant(contactId, customerId, currentTenant(), current.getVersion(), end) != 1) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        return toDto(mapper.selectByIdForTenant(contactId, customerId, currentTenant()));
    }

    @Override
    public String resolveRecipientEmail(Long customerId, Long contactId, LocalDate asOf) {
        assertCustomerScope(customerId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
        CustomerContact contact = mapper.selectListForTenant(customerId, currentTenant(), "有効", target, null)
                .stream().filter(c -> Objects.equals(c.getId(), contactId)
                        && c.getEmail() != null && !c.getEmail().isBlank()).findFirst().orElse(null);
        if (contact == null) {
            throw BusinessException.of("error.invoice.recipientContactUnavailable");
        }
        return contact.getEmail();
    }

    @Override
    public CustomerContact getOwnedOrThrow(Long customerId, Long contactId) {
        assertCustomerScope(customerId);
        CustomerContact contact = mapper.selectByIdForTenant(contactId, customerId, currentTenant());
        if (contact == null) throw BusinessException.of(404, "error.crm.contactNotFound");
        return contact;
    }

    private CustomerContact lockOwned(Long customerId, Long contactId) {
        CustomerContact contact = mapper.selectForUpdateForTenant(contactId, customerId, currentTenant());
        if (contact == null) throw BusinessException.of(404, "error.crm.contactNotFound");
        return contact;
    }

    private void lockCustomerContacts(Long customerId) {
        if (customerMapper.selectByIdForUpdateForTenant(customerId, currentTenant()) == null) {
            throw BusinessException.of(404, "error.crm.customerNotFound");
        }
        mapper.selectListForTenant(customerId, currentTenant(), null, null, null);
    }

    private void validatePrimaryPeriod(Long customerId, Long excludedId, Integer primaryFlag, String status,
                                       LocalDate from, LocalDate to) {
        if (!Integer.valueOf(1).equals(primaryFlag) || !"有効".equals(status)) return;
        List<CustomerContact> contacts = mapper.selectListForTenant(customerId, currentTenant(), "有効", null, null)
                .stream().filter(c -> Integer.valueOf(1).equals(c.getPrimaryFlag())
                        && !c.getValidFrom().isAfter(to == null ? LocalDate.of(9999, 12, 31) : to)
                        && (c.getValidTo() == null || !c.getValidTo().isBefore(from)))
                .collect(Collectors.toList());
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
        String tenantId = currentTenant();
        if (tenantOwnershipResolver.selectCustomer(tenantId, customerId) == null) {
            throw BusinessException.of(404, "error.crm.customerNotFound");
        }
        if (crmScopeService != null) {
            crmScopeService.assertAllowedCustomer(customerId, LocalDate.now(clock));
        } else {
            dataScopeService.assertAllowedCustomer(customerId);
        }
    }

    private String currentTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
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
