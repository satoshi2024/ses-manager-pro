let templateModal = null;
let currentTemplates = [];

$(document).ready(function() {
    templateModal = new bootstrap.Modal(document.getElementById('templateModal'));
    loadTemplates();
});

function loadTemplates() {
    $('#template-table-body').html('<tr><td colspan="4" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</td></tr>');
    
    $.ajax({
        url: '/api/email-templates',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderTemplates(res.data);
            } else {
                Toast.error(res.message);
                renderTemplates(getMockData()); // Fallback
            }
        },
        error: function(err) {
            console.error(err);
            renderTemplates(getMockData()); // Fallback
        }
    });
}

function renderTemplates(list) {
    currentTemplates = list || [];
    const tbody = $('#template-table-body');
    tbody.empty();

    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="4" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }

    list.forEach(t => {
        let typeBadge = 'status-secondary';
        if (t.templateType === '提案') typeBadge = 'status-primary';
        if (t.templateType === '面接依頼') typeBadge = 'status-warning';
        if (t.templateType === 'お礼') typeBadge = 'status-success';

        const tr = `
            <tr>
                <td class="px-4 py-3 text-white fw-bold">${SES.escapeHtml(t.templateName)}</td>
                <td class="py-3"><span class="status-badge ${typeBadge}">${SES.escapeHtml(t.templateType)}</span></td>
                <td class="py-3 text-muted text-truncate" style="max-width:300px;">${SES.escapeHtml(t.subjectTemplate)}</td>
                <td class="px-4 py-3 text-end">
                    <button class="btn btn-sm btn-outline-secondary text-muted hover-text-white border-dark me-1" onclick="editTemplate(${t.id})">
                        <i class="bi bi-pencil"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-danger border-dark" onclick="deleteTemplate(${t.id})">
                        <i class="bi bi-trash"></i>
                    </button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function showTemplateModal() {
    $('#template-form')[0].reset();
    $('#template-id').val('');
    $('#modal-title').text('新規テンプレート作成');
    templateModal.show();
}

function editTemplate(id) {
    // JSONをonclick属性へ直接埋め込むと値に含まれるクォートで属性を脱出できてしまうため、
    // idのみを渡してキャッシュ済み一覧から検索する
    const data = currentTemplates.find(t => t.id === id);
    if (!data) return;
    $('#template-id').val(data.id);
    $('#template-name').val(data.templateName);
    $('#template-type').val(data.templateType);
    $('#subject-template').val(data.subjectTemplate);
    $('#body-template').val(data.bodyTemplate);
    $('#modal-title').text('テンプレート編集');
    templateModal.show();
}

function saveTemplate() {
    if (!$('#template-form')[0].checkValidity()) {
        $('#template-form')[0].reportValidity();
        return;
    }
    
    const id = $('#template-id').val();
    const isNew = !id;
    
    const data = {
        templateName: $('#template-name').val(),
        templateType: $('#template-type').val(),
        subjectTemplate: $('#subject-template').val(),
        bodyTemplate: $('#body-template').val()
    };
    
    if (!isNew) {
        data.id = id;
    }

    $.ajax({
        url: isNew ? '/api/email-templates' : `/api/email-templates/${id}`,
        method: isNew ? 'POST' : 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('テンプレートを保存しました');
                templateModal.hide();
                loadTemplates();
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function deleteTemplate(id) {
    Swal.fire({
        title: '削除確認',
        text: 'このテンプレートを削除してもよろしいですか？',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: `/api/email-templates/${id}`,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success('削除しました');
                        loadTemplates();
                    } else {
                        Toast.error(res.message || '削除に失敗しました');
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}

function getMockData() {
    return [
        {
            id: 1,
            templateName: '標準提案メール',
            templateType: '提案',
            subjectTemplate: '【ご提案】エンジニアのご紹介（{engineer_name}）',
            bodyTemplate: '{customer_name} ご担当者様\n\nお世話になっております。SES Manager Proの{sender_name}です。\n\n貴社の{project_name}案件につきまして、弊社の{engineer_name}をご提案させていただきます。\n\n【アピールポイント】\n・\n・\n\nスキルシートを添付いたしますので、ご査収のほどよろしくお願いいたします。'
        },
        {
            id: 2,
            templateName: '事前面談依頼',
            templateType: '面接依頼',
            subjectTemplate: '【面談のお願い】{engineer_name}について',
            bodyTemplate: '{customer_name} ご担当者様\n\nお世話になっております。{sender_name}です。\n\n先日ご提案した{engineer_name}につきまして、書類選考を通過とのこと、誠にありがとうございます。\n事前面談の日程調整をお願いしたく存じます。'
        }
    ];
}
