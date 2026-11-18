// 初回ログイン時のパスワード変更画面（templates/password/change.html）。
// 変更前は他のAPIが遮断されるため、common.js（通知polling等）には依存しない。
// 変更APIは既存の PUT /api/profile/password（ApiResult形式、success code 200）を使う。
document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('password-change-form');
    if (!form) {
        return;
    }
    var message = document.getElementById('password-change-message');
    var submit = document.getElementById('password-change-submit');

    function showError(text) {
        message.textContent = text || form.dataset.msgNetwork || '';
    }

    function csrfToken() {
        // CookieCsrfTokenRepository が発行する XSRF-TOKEN Cookie を X-XSRF-TOKEN ヘッダーへ複製する。
        var match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    form.addEventListener('submit', async function (event) {
        event.preventDefault();
        var currentPassword = document.getElementById('password-change-current').value;
        var newPassword = document.getElementById('password-change-new').value;
        var confirmPassword = document.getElementById('password-change-confirm').value;
        if (newPassword !== confirmPassword) {
            showError(form.dataset.msgMismatch);
            return;
        }
        message.textContent = '';
        submit.disabled = true;
        try {
            var response = await fetch('/api/profile/password', {
                method: 'PUT',
                cache: 'no-store',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
                body: JSON.stringify({ currentPassword: currentPassword, newPassword: newPassword })
            });
            if (response.status === 401) {
                window.location.href = '/login?error=timeout';
                return;
            }
            var result = null;
            try {
                result = await response.json();
            } catch (parseError) {
                result = null;
            }
            if (result && result.code === 200) {
                // 遮断が解除されたので通常の振り分け（LoginSuccessHandlerと同じ /）へ進む。
                window.location.href = '/';
                return;
            }
            showError(result && result.message);
        } catch (error) {
            showError(null);
        } finally {
            submit.disabled = false;
        }
    });
});
