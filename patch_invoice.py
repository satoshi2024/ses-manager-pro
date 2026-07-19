import sys
import os

path = 'src/main/java/com/ses/service/impl/InvoiceServiceImpl.java'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

find_addPayment = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoicePayment addPayment(Long invoiceId, InvoicePayment payment) {
        Invoice invoice = this.getById(invoiceId);
        // 取消(void=論理削除)済み・存在しない請求書には入金できない。
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }'''

rep_addPayment = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoicePayment addPayment(Long invoiceId, InvoicePayment payment) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        // 取消(void=論理削除)済み・存在しない請求書には入金できない。
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }'''
text = text.replace(find_addPayment.replace('\r\n', '\n'), rep_addPayment)
text = text.replace(find_addPayment, rep_addPayment) # fallback for rn

find_delPayment = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePayment(Long invoiceId, Long paymentId) {
        Invoice invoice = this.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }'''

rep_delPayment = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePayment(Long invoiceId, Long paymentId) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }'''
text = text.replace(find_delPayment.replace('\r\n', '\n'), rep_delPayment)
text = text.replace(find_delPayment, rep_delPayment)

find_void = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidInvoice(Long id) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus())) {
            throw BusinessException.of("error.invoice.cancelPaidInvoice");
        }'''

rep_void = '''    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidInvoice(Long id) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        List<InvoicePayment> payments = listPayments(id);
        if (!payments.isEmpty()) {
            throw BusinessException.of("error.invoice.cancelPaidInvoice");
        }'''
text = text.replace(find_void.replace('\r\n', '\n'), rep_void)
text = text.replace(find_void, rep_void)

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)