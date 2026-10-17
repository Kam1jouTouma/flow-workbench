"""Render the tutorial's Markdown subset with no external packages.

Supported authoring features: headings, paragraphs, lists, quotes, fenced code,
inline code, bold and links. The Markdown file is the editable source.
"""

from pathlib import Path
import html
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/release-v0.16.1/脚本适配教程.md"


def inline(text):
    codes = []

    def code(match):
        codes.append("<code>" + html.escape(match[1]) + "</code>")
        return f"\x00{len(codes)-1}\x00"

    text = re.sub(r"`([^`]+)`", code, text)
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    for i, value in enumerate(codes):
        text = text.replace(f"\x00{i}\x00", value)
    return text


def render():
    lines = DOC.read_text(encoding="utf-8").splitlines()
    body, toc = [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("```"):
            language = line[3:].strip()
            code = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                code.append(lines[i])
                i += 1
            if i == len(lines):
                raise ValueError("Unclosed Markdown code fence")
            body.append(
                '<div class="code-block"><div class="code-label">'
                + html.escape(language or "示例")
                + "</div><pre><code>"
                + html.escape("\n".join(code))
                + "</code></pre></div>"
            )
        elif match := re.match(r"^(#{1,3}) (.+)$", line):
            level, title = len(match[1]), match[2]
            anchor = ""
            if level == 2:
                ident = f"section-{len(toc)+1}"
                anchor = f' id="{ident}"'
                toc.append(f'<a href="#{ident}">{inline(title)}</a>')
            body.append(f"<h{level}{anchor}>{inline(title)}</h{level}>")
        elif line.startswith("> "):
            quote = []
            while i < len(lines) and lines[i].startswith("> "):
                quote.append(lines[i][2:])
                i += 1
            body.append("<blockquote><p>" + inline(" ".join(quote)) + "</p></blockquote>")
            continue
        elif re.match(r"^(?:\d+\. |\- )", line):
            ordered = bool(re.match(r"^\d+\. ", line))
            tag = "ol" if ordered else "ul"
            pattern = r"^\d+\. (.*)$" if ordered else r"^- (.*)$"
            items = []
            while i < len(lines) and (match := re.match(pattern, lines[i])):
                items.append("<li>" + inline(match[1]) + "</li>")
                i += 1
            body.append(f"<{tag}>" + "".join(items) + f"</{tag}>")
            continue
        else:
            paragraph = [line]
            i += 1
            while (
                i < len(lines)
                and lines[i].strip()
                and not re.match(r"^(?:#|```|> |\d+\. |- )", lines[i])
            ):
                paragraph.append(lines[i])
                i += 1
            body.append("<p>" + inline(" ".join(paragraph)) + "</p>")
            continue
        i += 1
    css = """
    :root{color-scheme:light;--ink:#24324a;--muted:#65758c;--accent:#345fc4}
    *{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#f5f7fb;color:var(--ink);font:16px/1.85 "Microsoft YaHei",system-ui,sans-serif}
    .layout{max-width:1200px;margin:40px auto;display:grid;grid-template-columns:240px minmax(0,1fr);gap:28px;padding:0 24px}
    nav{align-self:start;position:sticky;top:28px;padding:20px;border:1px solid #dfe6f1;border-radius:12px;background:#fff}
    nav strong{display:block;margin-bottom:12px}nav a{display:block;padding:6px 0;font-size:14px;line-height:1.65}
    main{min-width:0;background:#fff;border:1px solid #dfe6f1;border-radius:14px;padding:36px 44px}
    h1{font-size:32px;line-height:1.4;margin:0 0 18px}h2{font-size:23px;line-height:1.5;margin:44px 0 18px;padding-top:14px;border-top:1px solid #e6ecf5;scroll-margin-top:24px}
    h3{font-size:18px;line-height:1.6;margin:28px 0 12px}p{margin:12px 0}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
    ol,ul{padding-left:25px;margin:16px 0}li{margin:8px 0;padding-left:4px}p code,li code{background:#edf2fa;color:#234b94;padding:2px 5px;border-radius:4px;overflow-wrap:anywhere}
    code{font:14px/1.7 Consolas,"Cascadia Code",monospace}.code-block{border:1px solid #dce4ef;border-radius:9px;overflow:hidden;margin:18px 0;background:#f6f8fc}
    .code-label{font:12px/1.5 system-ui,sans-serif;letter-spacing:.4px;color:var(--muted);padding:8px 16px;border-bottom:1px solid #e2e8f2;background:#edf2f9}
    pre{margin:0;padding:16px 20px;overflow-x:auto;white-space:pre;tab-size:4}blockquote{margin:20px 0;padding:8px 18px;border-left:4px solid #5278cc;background:#eef4ff;border-radius:0 8px 8px 0;font-size:14px}
    .source{font-size:13px;color:var(--muted);margin-bottom:28px}.footer{margin-top:36px;padding-top:18px;border-top:1px solid #e6ecf5;font-size:13px;color:var(--muted)}
    @media(max-width:850px){.layout{display:block;margin:20px auto;padding:0 12px}nav{position:static;margin-bottom:18px}main{padding:24px 22px}h1{font-size:27px}h2{font-size:21px}}
    @media print{body{background:#fff}.layout{display:block;margin:0;padding:0}nav{display:none}main{border:0;padding:0}pre{white-space:pre-wrap}h2{break-after:avoid}.code-block{break-inside:avoid}}
    """
    page = (
        '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>脚本适配教程 · Flow v0.16.1</title><style>" + css + "</style></head>"
        '<body><div class="layout"><nav aria-label="教程目录"><strong>教程目录</strong>'
        + "".join(toc)
        + '</nav><main><div class="source">Flow v0.16.1 · '
        '<a href="脚本适配教程.md">Markdown 源文档</a></div>'
        + "".join(body)
        + '<p class="footer">本页由 Markdown 源文档生成；修改教程请编辑同目录的 .md 文件。</p>'
        "</main></div></body></html>"
    )
    DOC.with_suffix(".html").write_text(page, encoding="utf-8")
    print("Tutorial HTML rendered from Markdown")


if __name__ == "__main__":
    render()
