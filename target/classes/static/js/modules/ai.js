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

    // Welcome message typing animation
    startWelcomeAnimation();

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

// --- Typing Animation Functions ---

function startWelcomeAnimation() {
    const welcomeText = 'こんにちは！SES Manager ProのAIアシスタントです。\n要員と案件のマッチング分析や、提案メールの作成、スキルシートの要約などをサポートします。\n右上の「設定」から Gemini API Key を入力してからご質問ください。';

    // Show typing dots for 2 seconds, then start typewriter
    setTimeout(function() {
        const contentEl = $('#welcomeContent');
        contentEl.empty();
        typeWriter(contentEl[0], welcomeText, 35, function() {
            // Animation complete - finalize with proper HTML structure
            contentEl.html('<p class="mb-0">' + welcomeText.replace(/\n/g, '<br>') + '</p>');
        });
    }, 2000);
}

/**
 * Typewriter effect for plain text content.
 * Types out text character by character with a blinking cursor.
 */
function typeWriter(element, text, speed, callback) {
    const $el = $(element);
    let index = 0;
    $el.html('<span class="typewriter-text"></span><span class="typewriter-cursor"></span>');
    const $textSpan = $el.find('.typewriter-text');
    const chatBox = $('#chatBox');

    function type() {
        if (index < text.length) {
            const char = text.charAt(index);
            if (char === '\n') {
                $textSpan.append('<br>');
            } else {
                $textSpan.append(document.createTextNode(char));
            }
            index++;
            // Scroll to bottom during typing
            chatBox.scrollTop(chatBox[0].scrollHeight);
            setTimeout(type, speed);
        } else {
            // Remove cursor when done
            $el.find('.typewriter-cursor').remove();
            if (callback) callback();
        }
    }

    type();
}

/**
 * Typewriter effect for markdown/HTML AI responses.
 * Renders the full HTML but reveals it progressively by walking text nodes.
 */
function typeWriterHtml(element, htmlContent, speed, callback) {
    const $el = $(element);
    const chatBox = $('#chatBox');

    // Create a hidden container with the full rendered HTML
    $el.html('<div class="typewriter-html-container"></div><span class="typewriter-cursor"></span>');
    const $container = $el.find('.typewriter-html-container');
    $container.html(htmlContent);

    // Collect all text nodes in document order
    const textNodes = [];
    function collectTextNodes(node) {
        if (node.nodeType === 3) { // Text node
            textNodes.push(node);
        } else {
            for (let i = 0; i < node.childNodes.length; i++) {
                collectTextNodes(node.childNodes[i]);
            }
        }
    }
    collectTextNodes($container[0]);

    // Store original text and clear all text nodes
    const originalTexts = textNodes.map(n => n.textContent);
    textNodes.forEach(n => { n.textContent = ''; });

    let nodeIndex = 0;
    let charIndex = 0;

    function revealNext() {
        if (nodeIndex >= textNodes.length) {
            // All done - remove cursor
            $el.find('.typewriter-cursor').remove();
            if (callback) callback();
            return;
        }

        const currentText = originalTexts[nodeIndex];
        if (charIndex < currentText.length) {
            textNodes[nodeIndex].textContent = currentText.substring(0, charIndex + 1);
            charIndex++;
            chatBox.scrollTop(chatBox[0].scrollHeight);
            setTimeout(revealNext, speed);
        } else {
            // Move to next text node
            nodeIndex++;
            charIndex = 0;
            revealNext();
        }
    }

    revealNext();
}

// --- End Typing Animation Functions ---

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

    // Show thinking dots in chat area
    const thinkingHtml = `
        <div class="message ai d-flex align-items-start" id="thinkingMessage">
            <div class="avatar bg-gradient-purple text-white rounded-circle me-2 d-flex align-items-center justify-content-center flex-shrink-0" style="width: 40px; height: 40px;">
                <i class="bi bi-robot"></i>
            </div>
            <div class="message-content shadow-sm">
                <div class="typing-dots">
                    <span></span><span></span><span></span>
                </div>
            </div>
        </div>
    `;
    $('#chatBox').append(thinkingHtml);
    $('#chatBox').scrollTop($('#chatBox')[0].scrollHeight);

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
            // Remove thinking dots
            $('#thinkingMessage').remove();

            if (res.code === 200) {
                appendMessage('ai', res.data);
            } else {
                appendMessage('ai', `<span class="text-danger"><i class="bi bi-exclamation-triangle"></i> エラー: ${res.message}</span>`, false);
            }
        },
        error: function(err) {
            console.error(err);
            // Remove thinking dots
            $('#thinkingMessage').remove();
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
    
    if (sender === 'user') {
        const html = `
            <div class="message user d-flex align-items-end justify-content-end">
                <div class="message-content shadow-sm text-break">
                    ${escapeHtml(text).replace(/\n/g, '<br>')}
                </div>
            </div>
        `;
        chatBox.append(html);
        chatBox.scrollTop(chatBox[0].scrollHeight);
    } else {
        // AI message with typewriter effect
        const msgId = 'ai-msg-' + Date.now();
        const html = `
            <div class="message ai d-flex align-items-start">
                <div class="avatar bg-gradient-purple text-white rounded-circle me-2 d-flex align-items-center justify-content-center flex-shrink-0" style="width: 40px; height: 40px;">
                    <i class="bi bi-robot"></i>
                </div>
                <div class="message-content shadow-sm text-break w-100" id="${msgId}">
                </div>
            </div>
        `;
        chatBox.append(html);

        const contentEl = document.getElementById(msgId);

        if (parseMarkdown) {
            const renderedHtml = marked.parse(text);
            // Use HTML typewriter for markdown content
            typeWriterHtml(contentEl, renderedHtml, 20, function() {
                // Finalize: ensure the container has clean HTML (remove wrapper)
                const $container = $(contentEl).find('.typewriter-html-container');
                const finalHtml = $container.html();
                $(contentEl).html(finalHtml);
            });
        } else {
            // Error messages - show immediately without animation
            $(contentEl).html(text);
            chatBox.scrollTop(chatBox[0].scrollHeight);
        }
    }
}

function escapeHtml(unsafe) {
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}
