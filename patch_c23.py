import sys

path_api = 'src/main/java/com/ses/controller/api/InvoiceApiController.java'
with open(path_api, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('import com.ses.service.InvoiceService;', 'import com.ses.service.InvoiceService;\nimport com.ses.service.EmailTemplateService;')

text = text.replace('    @Autowired\n    private InvoicePdfService invoicePdfService;', '    @Autowired\n    private InvoicePdfService invoicePdfService;\n\n    @Autowired\n    private EmailTemplateService emailTemplateService;')

find_rem = '''    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {'''

rep_rem = '''    @GetMapping("/reminder-templates")
    public ApiResult<?> reminderTemplates() {
        return ApiResult.success(emailTemplateService.list());
    }

    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {'''

text = text.replace(find_rem.replace('\r\n', '\n'), rep_rem)
text = text.replace(find_rem, rep_rem)

with open(path_api, 'w', encoding='utf-8') as f:
    f.write(text)

path_js = 'src/main/resources/static/js/modules/invoice.js'
with open(path_js, 'r', encoding='utf-8') as f:
    text_js = f.read()

text_js = text_js.replace("fetch('/api/email-templates')", "fetch('/api/invoices/reminder-templates')")

with open(path_js, 'w', encoding='utf-8') as f:
    f.write(text_js)