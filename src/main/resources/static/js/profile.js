$(document).ready(function() {
    $('#open-password-modal').on('click', function(e) {
        e.preventDefault();
        $('#pw-current').val('');
        $('#pw-new').val('');
        $('#pw-confirm').val('');
        var modal = new bootstrap.Modal(document.getElementById('passwordChangeModal'));
        modal.show();
    });

    $('#btn-save-password').on('click', function() {
        var currentPassword = $('#pw-current').val();
        var newPassword = $('#pw-new').val();
        var confirmPassword = $('#pw-confirm').val();

        if (newPassword !== confirmPassword) {
            SES.toast.error('新しいパスワードが一致しません');
            return;
        }

        SES.api.put('/api/profile/password', {
            currentPassword: currentPassword,
            newPassword: newPassword
        }).then(function(result) {
            if (result) {
                bootstrap.Modal.getInstance(document.getElementById('passwordChangeModal')).hide();
                SES.toast.success('パスワードを変更しました');
            }
        }).catch(function(e) {
            // エラーはSES.api._fetchが処理するため握り潰す
        });
    });
});
