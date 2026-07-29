document.addEventListener('DOMContentLoaded', function () {
    var message = document.getElementById('mfa-message');

    function showError(error) {
        if (message) {
            message.textContent = error && error.message ? error.message : '処理に失敗しました。';
        }
    }

    var setupButton = document.getElementById('mfa-setup-button');
    if (setupButton) {
        setupButton.addEventListener('click', async function () {
            try {
                var data = await SES.api.post('/api/security/mfa/setup', {});
                document.getElementById('mfa-secret').textContent = data.secret || '';
                document.getElementById('mfa-uri').value = data.otpauthUri || '';
                document.getElementById('mfa-setup-result').classList.remove('d-none');
                setupButton.disabled = true;
            } catch (error) {
                showError(error);
            }
        });
    }

    var enableButton = document.getElementById('mfa-enable-button');
    if (enableButton) {
        enableButton.addEventListener('click', async function () {
            try {
                var code = document.getElementById('mfa-enable-code').value;
                var data = await SES.api.post('/api/security/mfa/enable', { code: code });
                document.getElementById('mfa-recovery-codes').textContent = (data.recoveryCodes || []).join('\n');
                document.getElementById('mfa-recovery-result').classList.remove('d-none');
                document.getElementById('mfa-setup-result').classList.add('d-none');
            } catch (error) {
                showError(error);
            }
        });
    }

    var verifyButton = document.getElementById('mfa-verify-button');
    if (verifyButton) {
        verifyButton.addEventListener('click', async function () {
            try {
                var code = document.getElementById('mfa-verify-code').value;
                await SES.api.post('/api/security/mfa/verify', { code: code });
                window.location.href = '/';
            } catch (error) {
                showError(error);
            }
        });
    }

    var logoutButton = document.getElementById('mfa-logout-button');
    if (logoutButton) {
        logoutButton.addEventListener('click', async function () {
            var headers = { 'X-XSRF-TOKEN': SES.csrf.token() || '' };
            await fetch('/logout', { method: 'POST', headers: headers, credentials: 'same-origin' });
            window.location.href = '/login?logout';
        });
    }
});
