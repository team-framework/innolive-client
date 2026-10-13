"""Check built HTML and FAQ/schema consistency without executing JavaScript."""
import argparse
import json
import re
import urllib.request
import urllib.robotparser
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

ORIGIN = "https://innolive.studio"
AGENTS = ["Googlebot", "Google-Extended", "bingbot", "OAI-SearchBot", "Claude-SearchBot", "Claude-User", "PerplexityBot"]
# The same path registry drives language navigation and this verification.
GUIDES = re.findall(r'"(/[^" ]+)"', (Path(__file__).resolve().parents[1] / "lib/seo-guide-paths.ts").read_text())
TARGETS = {"/ko", "/en", "/ja", *(f"/ko{path}" for path in GUIDES)}


class Node:
    def __init__(self, tag="", attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []

    def find(self, predicate):
        result = [self] if predicate(self) else []
        for child in self.children:
            if isinstance(child, Node):
                result.extend(child.find(predicate))
        return result

    def text(self):
        return "".join(child.text() if isinstance(child, Node) else child for child in self.children)


class Document(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append(node)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                break

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def normalized(text):
    return re.sub(r"\s+", " ", text).strip()


def fetch(base, path):
    request = urllib.request.Request(base + path, headers={"User-Agent": "Mozilla/5.0 (compatible; OAI-SearchBot/1.4; +https://openai.com/searchbot)"})
    with urllib.request.urlopen(request, timeout=20) as response:
        assert response.status == 200, f"{path}: HTTP {response.status}"
        return response.read().decode("utf-8"), response.headers


def check_page(base, path, robots):
    source, headers = fetch(base, path)
    root = Document(source).root
    find = root.find
    expected_url = ORIGIN + path
    assert all(robots.can_fetch(agent, expected_url) for agent in AGENTS), "crawler blocked"
    assert "noindex" not in headers.get("X-Robots-Tag", "").lower(), "X-Robots-Tag noindex"
    assert not find(lambda node: node.tag == "meta" and node.attrs.get("name", "").lower() in ["robots", "googlebot"] and "noindex" in node.attrs.get("content", "").lower()), "meta noindex"
    canonical = find(lambda node: node.tag == "link" and node.attrs.get("rel") == "canonical")
    assert len(canonical) == 1 and canonical[0].attrs.get("href") == expected_url, "canonical mismatch"
    assert len(find(lambda node: node.tag == "h1")) == 1, "h1 count"
    assert find(lambda node: node.tag == "html")[0].attrs.get("lang") == path.split("/")[1], "HTML language"
    graphs = [json.loads(node.text())["@graph"] for node in find(lambda node: node.tag == "script" and node.attrs.get("type") == "application/ld+json")]
    graph = [node for nodes in graphs for node in nodes]
    count = 0
    if path in TARGETS:
        types = [node["@type"] for node in graph]
        for expected in ["Organization", "WebSite", "SoftwareApplication", "WebPage"]:
            assert types.count(expected) == 1, f"{expected} count"
        webpage = next(node for node in graph if node["@type"] == "WebPage")
        assert webpage["url"] == expected_url, "schema URL"
        paragraphs = [normalized(node.text()) for node in find(lambda node: node.tag == "p")]
        assert normalized(webpage["description"]) in paragraphs, "schema description missing from body"
        software = next(node for node in graph if node["@type"] == "SoftwareApplication")
        assert software["@id"] == ORIGIN + "/#software", "product identity"
        for faq in (node for node in graph if node["@type"] == "FAQPage"):
            assert find(lambda node: node.attrs.get("id") == "faq"), "FAQ section anchor"
            visible = find(lambda node: re.fullmatch(r"faq-\d+", node.attrs.get("id", "")))
            assert len(visible) == len(faq["mainEntity"]), "FAQ count"
            for question in faq["mainEntity"]:
                anchor = question["@id"].split("#")[1]
                matches = find(lambda node: node.attrs.get("id") == anchor)
                assert len(matches) == 1, f"{anchor}: anchor count"
                item = matches[0]
                heading = item.find(lambda node: node.tag in ["summary", "h3"])
                answer = item.find(lambda node: node.tag == "p")
                assert normalized(heading[0].text()) == normalized(question["name"]), f"{anchor}: question mismatch"
                assert normalized(answer[0].text()) == normalized(question["acceptedAnswer"]["text"]), f"{anchor}: answer mismatch"
                count += 1
        if "/blog/" in path:
            article = next(node for node in graph if node["@type"] == "Article")
            assert article["headline"] == find(lambda node: node.tag == "h1")[0].text(), "article headline"
    return {"path": path, "types": [node["@type"] for node in graph], "faq_answers": count}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:3107")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    robots_text, _ = fetch(base, "/robots.txt")
    robots = urllib.robotparser.RobotFileParser()
    robots.parse(robots_text.splitlines())
    assert f"Sitemap: {ORIGIN}/sitemap.xml" in robots_text
    assert all(not robots.can_fetch(agent, ORIGIN + "/api/example") for agent in AGENTS), "API crawl policy"
    sitemap, _ = fetch(base, "/sitemap.xml")
    urls = [node.text for node in ET.fromstring(sitemap).findall("{*}url/{*}loc")]
    assert len(urls) == len(set(urls)), "duplicate sitemap URL"
    assert all(url.startswith(ORIGIN + "/") for url in urls), "sitemap origin"
    paths = [url.removeprefix(ORIGIN) for url in urls]
    assert TARGETS <= set(paths), "missing AEO page in sitemap"
    queries = json.loads((Path(__file__).resolve().parents[3] / "docs/fixtures/landing-aeo-queries.ko.json").read_text())
    assert len(queries["queries"]) == 43, "keyword inventory count"
    assert len({query["keyword"] for query in queries["queries"]}) == 43, "duplicate keyword"
    assert all("innolive" not in query["prompt"].lower() for query in queries["queries"]), "branded benchmark prompt"
    assert all(query["sourcePath"] in paths for query in queries["queries"]), "benchmark source missing from sitemap"
    pages = [check_page(base, path, robots) for path in paths]
    report = {"base_url": base, "sitemap_urls": len(urls), "structured_pages": len(TARGETS), "faq_answers": sum(page["faq_answers"] for page in pages), "pages": pages}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"PASS: {len(pages)} pages, {len(TARGETS)} structured pages, {report['faq_answers']} matching FAQ answers, {len(AGENTS)} crawler policies")


if __name__ == "__main__":
    main()
