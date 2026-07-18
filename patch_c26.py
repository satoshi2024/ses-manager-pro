import sys

# 1. engineer-schema-h2.sql
path_h2 = 'src/test/resources/sql/engineer-schema-h2.sql'
with open(path_h2, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('error_message VARCHAR(1000)', 'error_message VARCHAR(1000),\n  invoice_id BIGINT')
with open(path_h2, 'w', encoding='utf-8') as f:
    f.write(text)

# 2. MailDelivery.java
path_md = 'src/main/java/com/ses/entity/MailDelivery.java'
with open(path_md, 'r', encoding='utf-8') as f:
    text = f.read()

if 'private Long invoiceId;' not in text:
    text = text.replace('private String errorMessage;', 'private String errorMessage;\n    private Long invoiceId;')
    with open(path_md, 'w', encoding='utf-8') as f:
        f.write(text)

# 3. MailService.java
path_ms = 'src/main/java/com/ses/service/MailService.java'
with open(path_ms, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to);', 'MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId);')
text = text.replace('MailDispatchResult send(String to, String subject, String body);', 'MailDispatchResult send(String to, String subject, String body, Long invoiceId);')

with open(path_ms, 'w', encoding='utf-8') as f:
    f.write(text)

# 4. MailServiceImpl.java
path_msi = 'src/main/java/com/ses/service/impl/MailServiceImpl.java'
with open(path_msi, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to) {', 'public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId) {')
text = text.replace('return send(to, subject, body);', 'return send(to, subject, body, invoiceId);')
text = text.replace('public MailDispatchResult send(String to, String subject, String body) {', 'public MailDispatchResult send(String to, String subject, String body, Long invoiceId) {')
text = text.replace('delivery.setQueuedAt(java.time.LocalDateTime.now());', 'delivery.setQueuedAt(java.time.LocalDateTime.now());\n        delivery.setInvoiceId(invoiceId);')

with open(path_msi, 'w', encoding='utf-8') as f:
    f.write(text)

# 5. InvoiceServiceImpl.java
path_isi = 'src/main/java/com/ses/service/impl/InvoiceServiceImpl.java'
with open(path_isi, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('return mailService.sendWithTemplate(templateId, params, to);', 'return mailService.sendWithTemplate(templateId, params, to, invoiceId);')

if 'public List<MailDelivery> listReminders(Long invoiceId)' not in text:
    text = text.replace('// ===== 債権管理（ar-management / P2） =====', '// ===== 債権管理（ar-management / P2） =====\n    @Autowired\n    private com.ses.mapper.MailDeliveryMapper mailDeliveryMapper;')
    text = text.replace('public MailDispatchResult sendReminder(Long invoiceId, Long templateId) {', 'public java.util.List<com.ses.entity.MailDelivery> listReminders(Long invoiceId) {\n        return mailDeliveryMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.MailDelivery>().eq("invoice_id", invoiceId).orderByDesc("id"));\n    }\n\n    @Override\n    public MailDispatchResult sendReminder(Long invoiceId, Long templateId) {')

with open(path_isi, 'w', encoding='utf-8') as f:
    f.write(text)

# 6. InvoiceService.java (add listReminders)
with open(path_service, 'r', encoding='utf-8') as f:
    text = f.read()

if 'List<MailDelivery> listReminders(Long invoiceId);' not in text:
    text = text.replace('import com.ses.entity.Invoice;', 'import com.ses.entity.Invoice;\nimport com.ses.entity.MailDelivery;')
    text = text.replace('MailDispatchResult sendReminder(Long invoiceId, Long templateId);', 'MailDispatchResult sendReminder(Long invoiceId, Long templateId);\n    List<MailDelivery> listReminders(Long invoiceId);')

with open(path_service, 'w', encoding='utf-8') as f:
    f.write(text)

# 7. InvoiceApiController.java
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

with open(path_api, 'w', encoding='utf-8') as f:
    f.write(text)
