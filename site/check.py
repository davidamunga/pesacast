#!/usr/bin/env python3
"""Checks that keep the site honest. Standard library only. Run: pnpm --filter pesacast-site build && python3 site/check.py

Checks the built site in site/dist, which is what gets published.

- every page has lang, a title, a description and exactly one h1;
- internal links, images, fonts and stylesheets resolve to files that exist;
- the page loads nothing from other websites and uses no scripts, inline styles or event handlers
  (the Content-Security-Policy in _headers forbids all three, and the footer promises it);
- the headers file still has the strict policy.
"""
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "dist"
if not ROOT.is_dir():
    sys.exit("site/dist not found: run `pnpm --filter pesacast-site build` first")
problems = []


def fail(page, message):
    problems.append(f"{page}: {message}")


class Page(HTMLParser):
    def __init__(self, name):
        super().__init__()
        self.name = name
        self.h1 = 0
        self.title = ""
        self.in_title = False
        self.description = None
        self.lang = None
        self.meta = {}    # property/name -> content
        self.links = []   # (kind, url) for things that load or navigate
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "html":
            self.lang = a.get("lang")
        if tag == "h1":
            self.h1 += 1
        if tag == "title":
            self.in_title = True
        if tag == "meta" and a.get("name") == "description":
            self.description = a.get("content")
        if tag == "meta" and (a.get("property") or a.get("name")):
            self.meta[a.get("property") or a.get("name")] = a.get("content", "")
        if tag == "script":
            fail(self.name, "uses <script>")
        if "style" in a:
            fail(self.name, "has an inline style attribute")
        for k in a:
            if k.startswith("on"):
                fail(self.name, f"has an event handler attribute ({k})")
        if tag == "link" and a.get("href"):
            # a canonical link names this page's own address; it loads nothing
            self.links.append(("nav" if a.get("rel") == "canonical" else "load", a["href"]))
        if tag in ("img", "source") and a.get("src"):
            self.links.append(("load", a["src"]))
        if tag == "a" and a.get("href"):
            self.links.append(("nav", a["href"]))

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title:
            self.title += data


pages = sorted(ROOT.glob("*.html"))
if not pages:
    sys.exit("no pages found")
parsed = {}
for path in pages:
    p = Page(path.name)
    p.feed(path.read_text(encoding="utf-8"))
    parsed[path.name] = p
    if p.lang != "en":
        fail(path.name, "missing lang=en")
    if not p.title.strip():
        fail(path.name, "missing <title>")
    if not (p.description or "").strip():
        fail(path.name, "missing meta description")
    if p.h1 != 1:
        fail(path.name, f"has {p.h1} h1 elements, expected 1")
    for key in ("og:title", "og:description", "og:image", "twitter:card", "twitter:image"):
        if not p.meta.get(key):
            fail(path.name, f"missing {key}")
    image = p.meta.get("og:image", "")
    if image and not image.startswith("https://") and not (ROOT / image).is_file():
        fail(path.name, f"og:image file not found: {image}")
    if len(p.description or "") > 200:
        fail(path.name, "meta description is longer than 200 characters")
    if re.search(r"\bTODO\b|lorem ipsum", path.read_text(encoding="utf-8"), re.I):
        fail(path.name, "contains a TODO or placeholder text")

for name, p in parsed.items():
    for kind, url in p.links:
        if url.startswith("#"):
            if url[1:] not in p.ids:
                fail(name, f"anchor {url} does not exist on the page")
            continue
        if re.match(r"^[a-z][a-z0-9+.-]*:", url, re.I):
            if kind == "load":
                fail(name, f"loads a resource from another site: {url}")
            elif not url.startswith("https://"):
                fail(name, f"external link is not https: {url}")
            continue
        target, _, frag = url.partition("#")
        target = target.split("?")[0]
        if not target:
            continue
        f = ROOT / target
        if not f.is_file():
            fail(name, f"broken link: {url}")
        elif frag and f.suffix == ".html" and frag not in parsed.get(f.name, Page(f.name)).ids:
            fail(name, f"anchor #{frag} not found in {target}")

for icon in ("favicon.ico", "favicon-32.png", "apple-touch-icon.png", "og.png", "robots.txt", "llms.txt"):
    if not (ROOT / icon).is_file():
        fail(icon, "missing from the build")

css_files = sorted((ROOT / "assets").glob("*.css"))
if not css_files:
    sys.exit("no stylesheet in dist/assets")
css = "\n".join(f.read_text(encoding="utf-8") for f in css_files)
for ref in re.findall(r"url\(([^)]+)\)", css):
    ref = ref.strip("'\" ")
    if re.match(r"^[a-z]+:", ref):
        fail("style.css", f"loads {ref}")
    elif not (ROOT / "assets" / ref).is_file():
        fail("style.css", f"missing file {ref}")
if "@import" in css:
    fail("style.css", "uses @import")

headers = (ROOT / "_headers").read_text(encoding="utf-8")
for needle in ("default-src 'none'", "style-src 'self'", "frame-ancestors 'none'"):
    if needle not in headers:
        fail("_headers", f"missing {needle}")
if "script-src" in headers or "unsafe-inline" in headers:
    fail("_headers", "allows scripts or inline content")

# Vercel ignores _headers and reads vercel.json, so the two must carry the same policy.
vercel = Path(__file__).resolve().parent / "vercel.json"
if not vercel.is_file():
    fail("vercel.json", "missing")
else:
    csp = re.search(r"Content-Security-Policy: (.+)", headers).group(1).strip()
    if csp not in vercel.read_text(encoding="utf-8"):
        fail("vercel.json", "Content-Security-Policy differs from public/_headers")

if problems:
    print("\n".join(problems))
    sys.exit(1)
print(f"site ok: {len(pages)} pages")
