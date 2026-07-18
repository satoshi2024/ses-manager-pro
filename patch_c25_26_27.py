import sys
import os

# C25
path_js = 'src/main/resources/static/js/modules/invoice.js'
with open(path_js, 'r', encoding='utf-8') as f:
    text_js = f.read()

find_aging = '''            cols.forEach(c => { cells += `<td class="text-right">￥${Number(r[c] || 0).toLocaleString()}</td>`; });'''
rep_aging = '''            cols.forEach(c => {
                const val = Number(r[c] || 0);
                const str = `￥${val.toLocaleString()}`;
                if (val > 0 && r.customerId) {
                    cells += `<td class="text-right"><a href="/invoice/list?customerId=${r.customerId}">${str}</a></td>`;
                } else {
                    cells += `<td class="text-right">${str}</td>`;
                }
            });'''
text_js = text_js.replace(find_aging, rep_aging)

with open(path_js, 'w', encoding='utf-8') as f:
    f.write(text_js)

# C26 & C27
path_ms = 'src/main/java/com/ses/service/MailService.java'
with open(path_ms, 'r', encoding='utf-8') as f:
    text = f.read()

if 'String to, Long invoiceId' not in text:
    text = text.replace('MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to);', 'MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId);')
    text = text.replace('MailDispatchResult send(String to, String subject, String body);', 'MailDispatchResult send(String to, String subject, String body, Long invoiceId);')
    with open(path_ms, 'w', encoding='utf-8') as f:
        f.write(text)

path_msi = 'src/main/java/com/ses/service/impl/MailServiceImpl.java'
with open(path_msi, 'r', encoding='utf-8') as f:
    text = f.read()

if 'String to, Long invoiceId' not in text:
    text = text.replace('public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to) {', 'public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId) {')
    text = text.replace('return send(to, subject, body);', 'return send(to, subject, body, invoiceId);')
    text = text.replace('public MailDispatchResult send(String to, String subject, String body) {', 'public MailDispatchResult send(String to, String subject, String body, Long invoiceId) {')
    text = text.replace('delivery.setQueuedAt(java.time.LocalDateTime.now());', 'delivery.setQueuedAt(java.time.LocalDateTime.now());\n        delivery.setInvoiceId(invoiceId);')
    with open(path_msi, 'w', encoding='utf-8') as f:
        f.write(text)

path_isi = 'src/main/java/com/ses/service/impl/InvoiceServiceImpl.java'
with open(path_isi, 'r', encoding='utf-8') as f:
    text = f.read()

if 'return mailService.sendWithTemplate(templateId, params, to, invoiceId);' not in text:
    text = text.replace('return mailService.sendWithTemplate(templateId, params, to);', 'return mailService.sendWithTemplate(templateId, params, to, invoiceId);')

if 'public List<MailDelivery> listReminders(Long invoiceId)' not in text:
    text = text.replace('// ===== 債権管理（ar-management / P2） =====', '// ===== 債権管理（ar-management / P2） =====\n    @Autowired\n    private com.ses.mapper.MailDeliveryMapper mailDeliveryMapper;')
    text = text.replace('public MailDispatchResult sendReminder(Long invoiceId, Long templateId) {', 'public java.util.List<com.ses.entity.MailDelivery> listReminders(Long invoiceId) {\n        return mailDeliveryMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.MailDelivery>().eq("invoice_id", invoiceId).orderByDesc("id"));\n    }\n\n    @Override\n    public MailDispatchResult sendReminder(Long invoiceId, Long templateId) {')

bulk_method = '''
    @Override
    public java.util.List<MailDispatchResult> sendReminders(java.util.List<Long> invoiceIds, Long templateId, java.time.LocalDate asOf) {
        java.time.LocalDate targetDate = asOf != null ? asOf : java.time.LocalDate.now();
        java.util.List<MailDispatchResult> results = new java.util.ArrayList<>();
        for (Long id : invoiceIds) {
            Invoice invoice = this.baseMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Invoice>().eq("id", id).last("FOR UPDATE"));
            if (invoice == null || ("入金済".equals(invoice.getStatus()) && listPayments(id).isEmpty())) {
                results.add(new MailDispatchResult(null, "FAILED"));
                continue;
            }
            if (!("送付済".equals(invoice.getStatus()) || "一部入金".equals(invoice.getStatus())) || invoice.getDueDate() == null || !invoice.getDueDate().isBefore(targetDate)) {
                results.add(new MailDispatchResult(null, "SKIPPED"));
                continue;
            }
            com.ses.entity.Customer customer = customerMapper.selectById(invoice.getCustomerId());
            String to = customer.getBillingEmail();
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("invoiceNo", invoice.getInvoiceNo());
            params.put("customerName", customer.getCustomerName());
            params.put("billingMonth", invoice.getBillingMonth());
            params.put("total", invoice.getTotal() != null ? invoice.getTotal().toString() : "0");
            params.put("dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");
            results.add(mailService.sendWithTemplate(templateId, params, to, id));
        }
        return results;
    }
'''
if 'sendReminders(java.util.List<Long>' not in text:
    text = text + bulk_method

with open(path_isi, 'w', encoding='utf-8') as f:
    f.write(text)

path_service = 'src/main/java/com/ses/service/InvoiceService.java'
with open(path_service, 'r', encoding='utf-8') as f:
    text = f.read()

if 'List<MailDelivery> listReminders(Long invoiceId);' not in text:
    text = text.replace('import com.ses.entity.Invoice;', 'import com.ses.entity.Invoice;\nimport com.ses.entity.MailDelivery;')
    text = text.replace('MailDispatchResult sendReminder(Long invoiceId, Long templateId);', 'MailDispatchResult sendReminder(Long invoiceId, Long templateId);\n    List<MailDelivery> listReminders(Long invoiceId);\n    List<MailDispatchResult> sendReminders(List<Long> invoiceIds, Long templateId, LocalDate asOf);')
    with open(path_service, 'w', encoding='utf-8') as f:
        f.write(text)

path_api = 'src/main/java/com/ses/controller/api/InvoiceApiController.java'
with open(path_api, 'r', encoding='utf-8') as f:
    text = f.read()

if '@GetMapping("/{id}/reminders")' not in text:
    find_rem = '''    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {'''

    rep_rem = '''    @GetMapping("/{id}/reminders")
    public ApiResult<?> listReminders(@PathVariable Long id) {
        return ApiResult.success(invoiceService.listReminders(id));
    }

    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {'''

    text = text.replace(find_rem.replace('\r\n', '\n'), rep_rem)
    text = text.replace(find_rem, rep_rem)

bulk_api = '''
    @PostMapping("/reminders")
    public ApiResult<?> sendRemindersBulk(@RequestBody BulkReminderRequest request) {
        return ApiResult.success(invoiceService.sendReminders(request.getInvoiceIds(), request.getTemplateId(), request.getAsOf()));
    }

    public static class BulkReminderRequest {
        private List<Long> invoiceIds;
        private Long templateId;
        private LocalDate asOf;
        public List<Long> getInvoiceIds() { return invoiceIds; }
        public void setInvoiceIds(List<Long> invoiceIds) { this.invoiceIds = invoiceIds; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public LocalDate getAsOf() { return asOf; }
        public void setAsOf(LocalDate asOf) { this.asOf = asOf; }
    }
'''
if '@PostMapping("/reminders")' not in text:
    text = text.replace('public static class ReminderRequest {', bulk_api + '\n    public static class ReminderRequest {')

with open(path_api, 'w', encoding='utf-8') as f:
    f.write(text)
