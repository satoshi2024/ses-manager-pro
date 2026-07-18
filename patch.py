import sys

path = 'src/main/java/com/ses/service/impl/InvoiceServiceImpl.java'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('Invoice invoice = this.getById(invoiceId);', 'Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));')

find2 = '''        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }'''
rep2 = '''        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }'''
text = text.replace(find2.replace('\r\n', '\n'), rep2)

find3 = '''        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus())) {
            throw BusinessException.of("error.invoice.cancelPaidInvoice");
        }'''
rep3 = '''        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if (!listPayments(id).isEmpty()) {
            throw BusinessException.of("error.invoice.cancelPaidInvoice");
        }'''
text = text.replace(find3.replace('\r\n', '\n'), rep3)

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)