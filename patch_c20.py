import sys

path_service = 'src/main/java/com/ses/service/InvoiceService.java'
with open(path_service, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('import com.ses.entity.InvoicePayment;', 'import com.ses.entity.InvoicePayment;\nimport com.ses.dto.invoice.InvoicePaymentCreateRequest;\nimport com.ses.dto.invoice.InvoicePaymentResponse;')
text = text.replace('InvoicePayment addPayment(Long invoiceId, InvoicePayment payment);', 'InvoicePaymentResponse addPayment(Long invoiceId, InvoicePaymentCreateRequest request);')
text = text.replace('List<InvoicePayment> listPayments(Long invoiceId);', 'List<InvoicePaymentResponse> listPayments(Long invoiceId);')

with open(path_service, 'w', encoding='utf-8') as f:
    f.write(text)

path_impl = 'src/main/java/com/ses/service/impl/InvoiceServiceImpl.java'
with open(path_impl, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('import com.ses.entity.InvoicePayment;', 'import com.ses.entity.InvoicePayment;\nimport com.ses.dto.invoice.InvoicePaymentCreateRequest;\nimport com.ses.dto.invoice.InvoicePaymentResponse;\nimport java.util.stream.Collectors;')

find_add = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoicePayment addPayment(Long invoiceId, InvoicePayment payment) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        // 取消(void=論理削除)済み・存在しない請求書には入金できない。
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }
        if (payment.getAmount() == null || payment.getAmount().signum() <= 0) {
            throw BusinessException.of("error.invoice.paymentAmountInvalid");
        }
        
        payment.setInvoiceId(invoiceId);
        invoicePaymentMapper.insert(payment);'''

rep_add = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoicePaymentResponse addPayment(Long invoiceId, InvoicePaymentCreateRequest request) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        // 取消(void=論理削除)済み・存在しない請求書には入金できない。
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw BusinessException.of("error.invoice.paymentAmountInvalid");
        }
        
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(invoiceId);
        payment.setAmount(request.getAmount());
        payment.setFee(request.getFee() != null ? request.getFee() : java.math.BigDecimal.ZERO);
        payment.setPaidDate(request.getPaidDate());
        payment.setRemarks(request.getRemarks());
        payment.setCreatedBy(0L); // 仮
        payment.setUpdatedBy(0L); // 仮
        invoicePaymentMapper.insert(payment);'''

text = text.replace(find_add.replace('\r\n', '\n'), rep_add)

find_ret_add = '''        // ステータス再計算
        recalculateStatus(invoice);
        return payment;'''
rep_ret_add = '''        // ステータス再計算
        recalculateStatus(invoice);
        return mapToResponse(payment);'''
text = text.replace(find_ret_add.replace('\r\n', '\n'), rep_ret_add)

find_list = '''    @Override
    public List<InvoicePayment> listPayments(Long invoiceId) {
        return invoicePaymentMapper.selectList(new QueryWrapper<InvoicePayment>()
                .eq("invoice_id", invoiceId)
                .orderByAsc("paid_date", "id"));
    }'''

rep_list = '''    @Override
    public List<InvoicePaymentResponse> listPayments(Long invoiceId) {
        return invoicePaymentMapper.selectList(new QueryWrapper<InvoicePayment>()
                .eq("invoice_id", invoiceId)
                .orderByAsc("paid_date", "id"))
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private InvoicePaymentResponse mapToResponse(InvoicePayment payment) {
        InvoicePaymentResponse res = new InvoicePaymentResponse();
        res.setId(payment.getId());
        res.setInvoiceId(payment.getInvoiceId());
        res.setAmount(payment.getAmount());
        res.setFee(payment.getFee());
        res.setPaidDate(payment.getPaidDate());
        res.setRemarks(payment.getRemarks());
        return res;
    }
'''
text = text.replace(find_list.replace('\r\n', '\n'), rep_list)

# voidInvoice fix: listPayments now returns Response, not Entity!
# List<InvoicePayment> payments = listPayments(id);
text = text.replace('List<InvoicePayment> payments = listPayments(id);', 'List<InvoicePaymentResponse> payments = listPayments(id);')

with open(path_impl, 'w', encoding='utf-8') as f:
    f.write(text)

path_api = 'src/main/java/com/ses/controller/api/InvoiceApiController.java'
with open(path_api, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('import com.ses.entity.InvoicePayment;', 'import com.ses.entity.InvoicePayment;\nimport com.ses.dto.invoice.InvoicePaymentCreateRequest;\nimport jakarta.validation.Valid;')
text = text.replace('public ApiResult<?> addPayment(@PathVariable Long id, @RequestBody InvoicePayment payment) {', 'public ApiResult<?> addPayment(@PathVariable Long id, @RequestBody @Valid InvoicePaymentCreateRequest request) {')
text = text.replace('return ApiResult.success(invoiceService.addPayment(id, payment));', 'return ApiResult.success(invoiceService.addPayment(id, request));')

with open(path_api, 'w', encoding='utf-8') as f:
    f.write(text)