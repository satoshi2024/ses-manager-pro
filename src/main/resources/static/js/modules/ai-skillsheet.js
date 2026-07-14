function generateSkillSheet(engineerId) {
    Swal.fire({
        title: SES.i18n.t('ai.skillsheet.generateTitle'),
        text: SES.i18n.t('ai.skillsheet.generateConfirm'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonColor: '#6f42c1',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '<i class="bi bi-magic me-1"></i> ' + SES.i18n.t('ai.skillsheet.generateButton'),
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            Swal.fire({
                title: SES.i18n.t('ai.skillsheet.generatingTitle'),
                html: SES.i18n.t('ai.skillsheet.generatingMessage') + '<br><br><div class="spinner-border text-accent-purple" role="status"></div>',
                allowOutsideClick: false,
                showConfirmButton: false
            });

            // Simulate API call
            $.ajax({
                url: '/api/ai/skill-sheet/generate',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ engineerId: engineerId }),
                success: function(res) {
                    setTimeout(() => {
                        Swal.fire({
                            title: SES.i18n.t('ai.skillsheet.successTitle'),
                            text: SES.i18n.t('ai.skillsheet.successMessage'),
                            icon: 'success',
                            confirmButtonColor: '#20c997',
                            confirmButtonText: SES.i18n.t('common.download')
                        }).then(() => {
                            // In real app, trigger PDF download or open in new tab
                            Toast.success(SES.i18n.t('ai.skillsheet.downloadStarted'));
                        });
                    }, 2000);
                },
                error: function(err) {
                    setTimeout(() => {
                        Swal.fire({
                            title: SES.i18n.t('ai.skillsheet.successTitle') + ' (Mock)',
                            text: SES.i18n.t('ai.skillsheet.successMessage'),
                            icon: 'success',
                            confirmButtonColor: '#20c997',
                            confirmButtonText: SES.i18n.t('common.download')
                        }).then(() => {
                            Toast.success(SES.i18n.t('ai.skillsheet.downloadStarted'));
                        });
                    }, 2000);
                }
            });
        }
    });
}
