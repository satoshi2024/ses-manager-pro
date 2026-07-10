$(document).ready(function() {
    // Get engineer ID from URL
    const urlParams = new URLSearchParams(window.location.search);
    const id = urlParams.get('id');
    
    if (id) {
        loadEngineerDetail(id);
    } else {
        Toast.error('要員IDが指定されていません');
    }
});

function loadEngineerDetail(id) {
    $.ajax({
        url: '/api/engineers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineerDetail(res.data);
            } else {
                Toast.error('データの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function renderEngineerDetail(eng) {
    // Update global variables for AI matching
    if (typeof currentEngineerId !== 'undefined') {
        currentEngineerId = eng.id;
        currentEngineerName = eng.fullName;
    }

    // Update Header
    $('#eng-name').text(eng.fullName);
    $('#eng-initial').text(`(${eng.initialName || '-'})`);
    
    // Update Profile Card
    $('#det-name').text(eng.fullName);
    
    let statusColor = 'text-muted';
    let statusIcon = 'bi-circle';
    if (eng.status === '稼動中') { statusColor = 'text-accent-green'; statusIcon = 'bi-check-circle-fill'; }
    if (eng.status === '提案中') { statusColor = 'text-info'; statusIcon = 'bi-arrow-right-circle-fill'; }
    if (eng.status === 'Bench') { statusColor = 'text-warning'; statusIcon = 'bi-pause-circle-fill'; }
    if (eng.status === '退場予定') { statusColor = 'text-danger'; statusIcon = 'bi-exclamation-circle-fill'; }
    
    $('#det-status').html(`<span class="${statusColor}"><i class="bi ${statusIcon} me-1"></i>${eng.status || '未設定'}</span>`);
    
    // Experience
    $('#det-experience').text(eng.experienceYears ? eng.experienceYears + '年' : '-');
    
    // Price
    const priceStr = eng.expectedUnitPrice ? '¥' + eng.expectedUnitPrice.toLocaleString() + ' / 月' : '-';
    $('#det-price').text(priceStr);
    
    // Station
    $('#det-station').text(eng.nearestStation || '-');
    
    // Skills (mock logic: parse from resumeSummary if available, else show default)
    let skillsHtml = '';
    if (eng.resumeSummary && eng.resumeSummary.includes('Java')) {
        skillsHtml += '<span class="badge bg-secondary border border-dark text-light">Java</span>';
    }
    if (eng.resumeSummary && eng.resumeSummary.includes('Spring')) {
        skillsHtml += '<span class="badge bg-secondary border border-dark text-light">Spring Boot</span>';
    }
    if (skillsHtml === '') {
        skillsHtml = '<span class="badge bg-secondary border border-dark text-light">登録なし</span>';
    }
    $('#det-skills').html(skillsHtml);

    // Resume Summary Timeline
    if (eng.resumeSummary) {
        // Just show it as one block for now
        $('#det-resume').html(`
            <div class="position-relative mb-4">
                <div class="position-absolute bg-accent-blue rounded-circle" style="width: 12px; height: 12px; left: -1.85rem; top: 0.3rem;"></div>
                <div class="text-muted small mb-1">サマリ</div>
                <p class="text-light small mb-2" style="white-space: pre-wrap;">${eng.resumeSummary}</p>
            </div>
        `);
    } else {
        $('#det-resume').html('<div class="text-muted small">経歴情報が登録されていません。</div>');
    }
}
