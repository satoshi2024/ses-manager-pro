import sys

path = 'src/main/java/com/ses/service/impl/MailServiceImpl.java'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

find = '''        // SMTP未設定（host空 or JavaMailSender未生成）はドライラン
        if (!StringUtils.hasText(host) || sender == null) {
            delivery.setStatus("DRY_RUN");
            delivery.setAttemptCount(1);
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            log.info("【メールドライラン】from={} to={} subject={}\\n{}", from, delivery.getRecipient(), delivery.getSubject(), delivery.getBody());
        }'''

rep = '''        // SMTP未設定（host空 or JavaMailSender未生成）はドライラン
        if (!StringUtils.hasText(host) || sender == null) {
            delivery.setStatus("DRY_RUN");
            delivery.setAttemptCount(1);
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            log.info("【メールドライラン】from={} to={} subject={}\\n{}", from, delivery.getRecipient(), delivery.getSubject(), delivery.getBody());
            return;
        }'''

text = text.replace(find.replace('\r\n', '\n'), rep)
text = text.replace(find, rep)

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)

path_js = 'src/main/resources/static/js/modules/invoice.js'
with open(path_js, 'r', encoding='utf-8') as f:
    text_js = f.read()

find_js = '''        if (data.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('reminderModal')).hide();
            (SES.toast || alert)(SES.i18n.t('invoice.reminder.sent', '督促メールを送信しました'), 'success');
        } else {'''

rep_js = '''        if (data.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('reminderModal')).hide();
            if (data.data && data.data.status === 'FAILED') {
                if (window.SES && SES.toast) SES.toast('メール送信に失敗しました', 'error');
                else alert('メール送信に失敗しました');
            } else {
                if (window.SES && SES.toast) SES.toast(SES.i18n.t('invoice.reminder.sent', '督促メールを送信しました'), 'success');
                else alert(SES.i18n.t('invoice.reminder.sent', '督促メールを送信しました'));
            }
        } else {'''

text_js = text_js.replace(find_js.replace('\r\n', '\n'), rep_js)
text_js = text_js.replace(find_js, rep_js)

with open(path_js, 'w', encoding='utf-8') as f:
    f.write(text_js)