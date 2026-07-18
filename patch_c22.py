import sys

path = 'src/main/java/com/ses/common/util/TemplateRenderer.java'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('Pattern.compile("\\{\\{(\\w+)\\}\\}")', 'Pattern.compile("\\\\{1,2}(\\\\w+)\\\\}_{1,2}")')

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)