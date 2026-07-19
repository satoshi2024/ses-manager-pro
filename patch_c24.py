import sys

path_js = 'src/main/resources/static/js/modules/invoice.js'
with open(path_js, 'r', encoding='utf-8') as f:
    text_js = f.read()

find_iso = "new Date().toISOString().split('T')[0]"
rep_iso = "getLocalDateString()"

text_js = text_js.replace(find_iso, rep_iso)

helper = """function getLocalDateString() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
"""

if "function getLocalDateString()" not in text_js:
    text_js += "\n" + helper

with open(path_js, 'w', encoding='utf-8') as f:
    f.write(text_js)