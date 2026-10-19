"""Audit raw HTTP responses without JavaScript. Exit nonzero on regressions.

python3 scripts/audit-search.py http://127.0.0.1:3109 --output /tmp/search.json
Run the same command with the deployed origin after release.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


class Page(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.meta, self.links, self.text, self.ld = {}, [], [], []
        self.anchors = []
        self.h1 = 0
        self.title = ""
        self.in_title = False
        self.hidden = 0
        self.json_script = False
        self.script = ""
        self.feed(source)

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag in ("p", "div", "section", "h1", "h2", "h3", "li", "td", "th", "button"):
            self.text.append(" ")
        if tag == "a" and attrs.get("href"):
            self.anchors.append(attrs["href"])
        if tag == "meta":
            self.meta[attrs.get("name", attrs.get("property", ""))] = attrs.get("content", "")
        if tag == "link":
            self.links.append(attrs)
        if tag == "h1":
            self.h1 += 1
        if tag == "title":
            self.in_title = True
        if tag in ("script", "style"):
            self.hidden += 1
        if tag == "script":
            self.json_script = attrs.get("type") == "application/ld+json"
            self.script = ""

    def handle_endtag(self, tag):
        if tag in ("p", "div", "section", "h1", "h2", "h3", "li", "td", "th", "button"):
            self.text.append(" ")
        if tag == "title":
            self.in_title = False
        if tag == "script" and self.json_script:
            self.ld.append(json.loads(self.script))
            self.json_script = False
        if tag in ("script", "style"):
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, value):
        if self.in_title:
            self.title += value
        if self.json_script:
            self.script += value
        if not self.hidden:
            self.text.append(value)


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "InnoLive-Search-Audit/1.0"})
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers), response.read().decode("utf-8")


def normalized(value):
    return re.sub(r"\s+", " ", value).strip()


def run(origin):
    errors, pages = [], []
    internal_links = set()
    def check(condition, message):
        if not condition:
            errors.append(message)

    status, _, sitemap = get(origin + "/sitemap.xml")
    check(status == 200, "sitemap status")
    urls = [element.text for element in ET.fromstring(sitemap).iter() if element.tag.endswith("}loc")]
    check(bool(urls) and len(set(urls)) == len(urls), "empty or duplicate sitemap")

    def inspect(canonical):
        path = urllib.parse.urlsplit(canonical).path
        status, headers, html = get(origin + path)
        page = Page(html)
        nodes = [node for item in page.ld for node in item.get("@graph", [item])]
        visible = normalized("".join(page.text))
        faqs = [question for node in nodes if node.get("@type") == "FAQPage" for question in node["mainEntity"]]
        return page, {"path": path, "status": status, "title": page.title,
                      "h1": page.h1, "jsonLd": len(page.ld), "faqAnswers": len(faqs),
                      "faqMatches": sum(normalized(q["name"]) in visible and normalized(q["acceptedAnswer"]["text"]) in visible for q in faqs)}, headers

    for canonical, (page, record, headers) in zip(urls, ThreadPoolExecutor(max_workers=6).map(inspect, urls)):
        path = record["path"]
        check(record["status"] == 200, f"{path}: status")
        check(page.h1 == 1, f"{path}: h1 count {page.h1}")
        check(bool(page.title and page.meta.get("description")), f"{path}: title/description")
        check(any(link.get("rel") == "canonical" and link.get("href") == canonical for link in page.links), f"{path}: canonical")
        suffix = "/".join(path.split("/")[2:])
        for lang in ("ko", "en", "ja", "x-default"):
            target_locale = "ko" if lang == "x-default" else lang
            expected = f"https://innolive.studio/{target_locale}" + (f"/{suffix}" if suffix else "")
            check(any(link.get("hreflang") == lang and link.get("href") == expected for link in page.links), f"{path}: hreflang {lang}")
        for href in page.anchors:
            linked = urllib.parse.urlsplit(urllib.parse.urljoin(canonical, href))
            if linked.scheme in ("http", "https") and linked.netloc == "innolive.studio":
                internal_links.add(linked.path or "/")
        directives = page.meta.get("robots", "") + " " + " ".join(v for k, v in headers.items() if k.lower() == "x-robots-tag")
        check(not re.search(r"\b(noindex|none)\b", directives, re.I), f"{path}: noindex")
        check(bool(page.meta.get("og:title") and page.meta.get("twitter:card")), f"{path}: social metadata")
        check(record["faqAnswers"] == record["faqMatches"], f"{path}: FAQ not in visible HTML")
        if path.count("/") == 1 or any(segment in path for segment in ("/about", "/blog/", "/compare/", "mosaic", "face-blur", "live-privacy", "/youtube", "/chzzk")):
            check(bool(page.ld), f"{path}: missing JSON-LD")
        record["description"] = page.meta.get("description")
        pages.append(record)

    sitemap_paths = {record["path"] for record in pages}
    for path in sorted(internal_links - sitemap_paths):
        check(get(origin + path)[0] == 200, f"{path}: broken internal link")
    for key in ("title", "description"):
        values = [page[key] for page in pages]
        check(len(set(values)) == len(values), f"duplicate {key}")
    for locale in ("ko", "en", "ja"):
        for form in ("login", "signup"):
            path = f"/{locale}/{form}"
            status, _, html = get(origin + path)
            check(status == 200 and "noindex" in Page(html).meta.get("robots", ""), f"{path}: account form must be noindex")
    endpoints = {}
    for path, content_type in (("/llms.txt", "text/plain"), ("/llms-full.txt", "text/plain"), ("/feed.xml", "application/rss+xml"), ("/robots.txt", "text/plain")):
        status, headers, body = get(origin + path)
        endpoints[path] = {"status": status, "bytes": len(body.encode())}
        check(status == 200, f"{path}: status {status}")
        check(content_type in next((v for k, v in headers.items() if k.lower() == "content-type"), ""), f"{path}: content type")
        if status != 200:
            continue
        if path.startswith("/llms"):
            guide_urls = [url for url in urls if any(part in url for part in ("/blog/", "/compare/", "/live-mosaic", "/about"))]
            check(all(url in body for url in guide_urls), f"{path}: missing guide links")
        if path == "/feed.xml":
            links = [item.findtext("link") for item in ET.fromstring(body).findall("./channel/item")]
            check(set(links) == {url for url in urls if "/blog/" in url}, "RSS blog coverage")
            endpoints[path]["items"] = len(links)
        if path == "/robots.txt":
            check("User-Agent: Yeti" in body and "User-Agent: OAI-SearchBot" in body, "explicit crawler policies")
            groups = body.strip().split("\n\n")
            check(all("Disallow: /api/" in group for group in groups if "User-Agent:" in group), "crawler API exclusion")
    check(get(origin + "/ko/seo-audit-missing-20261019")[0] == 404, "missing page is not 404")
    return {"measuredAt": datetime.now(timezone.utc).isoformat(), "origin": origin,
            "pageCount": len(pages), "internalLinkTargets": len(internal_links), "faqAnswers": sum(p["faqAnswers"] for p in pages),
            "endpoints": endpoints, "pages": pages, "errors": errors}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = run(args.origin.rstrip("/"))
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "pages"}, ensure_ascii=False, indent=2))
    raise SystemExit(bool(result["errors"]))
