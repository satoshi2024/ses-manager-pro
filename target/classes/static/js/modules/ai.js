$(document).ready(function() {
    // Load API Key from localStorage
    const savedApiKey = localStorage.getItem('geminiApiKey');
    if (savedApiKey) {
        $('#geminiApiKey').val(savedApiKey);
    } else {
        // If no API key, open settings panel automatically
        $('#settingsPanel').collapse('show');
    }

    // Load contexts (engineers and projects)
    loadContextData();

    // Save settings
    $('#saveSettingsBtn').click(function() {
        const key = $('#geminiApiKey').val().trim();
        if (key) {
            localStorage.setItem('geminiApiKey', key);
            Toast.success('APIキーを保存しました');
            $('#settingsPanel').collapse('hide');
        } else {
            localStorage.removeItem('geminiApiKey');
            Toast.warning('APIキーがクリアされました');
        }
    });

    // Enter key to send
    $('#chatInput').on('keypress', function(e) {
        if (e.which == 13 && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Send button click
    $('#sendBtn').click(function() {
        sendMessage();
    });
});

function loadContextData() {
    // Load Engineers
    $.get('/api/engineers?size=100', function(res) {
        if (res.code === 200 && res.data) {
            const records = res.data.records || res.data;
            const select = $('#contextEngineer');
            records.forEach(eng => {
                select.append(`<option value="${eng.id}">${eng.fullName} (${eng.expectedUnitPrice ? eng.expectedUnitPrice+'万円' : '-'})</option>`);
            });
        }
    });

    // Load Projects
    $.get('/api/projects?size=100', function(res) {
        if (res.code === 200 && res.data) {
            const records = res.data.records || res.data;
            const select = $('#contextProject');
            records.forEach(proj => {
                select.append(`<option value="${proj.id}">${proj.projectName}</option>`);
            });
        }
    });
}

function sendMessage() {
    const prompt = $('#chatInput').val().trim();
    const apiKey = $('#geminiApiKey').val().trim();
    
    if (!prompt) return;
    
    if (!apiKey) {
        Toast.error('Gemini API Key を設定画面から入力してください。');
        $('#settingsPanel').collapse('show');
        return;
    }

    // Add user message to UI
    appendMessage('user', prompt);
    $('#chatInput').val('');
    
    // Show loading state
    $('#sendIcon').addClass('d-none');
    $('#sendSpinner').removeClass('d-none');
    $('#sendBtn').prop('disabled', true);
    $('#chatInput').prop('disabled', true);

    const data = {
        apiKey: apiKey,
        prompt: prompt,
        engineerId: $('#contextEngineer').val() || null,
        projectId: $('#contextProject').val() || null
    };

    $.ajax({
        url: '/api/ai/chat',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                appendMessage('ai', res.data);
            } else {
                appendMessage('ai', `<span class="text-danger"><i class="bi bi-exclamation-triangle"></i> エラー: ${res.message}</span>`, false);
            }
        },
        error: function(err) {
            console.error(err);
            appendMessage('ai', '<span class="text-danger"><i class="bi bi-exclamation-triangle"></i> サーバー通信エラーが発生しました。</span>', false);
        },
        complete: function() {
            // Restore UI state
            $('#sendIcon').removeClass('d-none');
            $('#sendSpinner').addClass('d-none');
            $('#sendBtn').prop('disabled', false);
            $('#chatInput').prop('disabled', false).focus();
        }
    });
}

function appendMessage(sender, text, parseMarkdown = true) {
    const chatBox = $('#chatBox');
    let html = '';
    
    if (sender === 'user') {
        html = `
            <div class="message user d-flex align-items-end justify-content-end">
                <div class="message-content shadow-sm text-break">
                    ${escapeHtml(text).replace(/\n/g, '<br>')}
                </div>
            </div>
        `;
    } else {
        const content = parseMarkdown ? marked.parse(text) : text;
        html = `
            <div class="message ai d-flex align-items-start">
                <div class="avatar bg-gradient-purple text-white rounded-circle me-2 d-flex align-items-center justify-content-center flex-shrink-0" style="width: 40px; height: 40px;">
                    <i class="bi bi-robot"></i>
                </div>
                <div class="message-content shadow-sm text-break w-100">
                    ${content}
                </div>
            </div>
        `;
    }
    
    chatBox.append(html);
    // Scroll to bottom
    chatBox.scrollTop(chatBox[0].scrollHeight);
}

function escapeHtml(unsafe) {
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}
