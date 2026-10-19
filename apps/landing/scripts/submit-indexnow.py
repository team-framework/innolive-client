"""Notify IndexNow after deployment. Dry run unless --submit is supplied."""
import argparse
import json
from pathlib import Path
import re
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET


def prepare(origin):
    origin = origin.rstrip('/')
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != 'https' or parsed.hostname != 'innolive.studio' or parsed.path or parsed.query or parsed.fragment or parsed.username or parsed.port:
        raise ValueError('Only the canonical HTTPS origin is supported')
    public = Path(__file__).resolve().parents[1] / 'public'
    keys = [p for p in public.glob('*.txt') if re.fullmatch(r'[a-zA-Z0-9-]{8,128}', p.stem) and p.read_text().strip() == p.stem]
    if len(keys) != 1:
        raise ValueError('Expected one public IndexNow verification file')
    key = keys[0].stem
    with urllib.request.urlopen(f'{origin}/{key}.txt', timeout=30) as response:
        if response.read().decode().strip() != key:
            raise ValueError('Deployed verification file mismatch')
    with urllib.request.urlopen(f'{origin}/sitemap.xml', timeout=30) as response:
        root = ET.fromstring(response.read())
    urls = [node.text for node in root.findall('{http://www.sitemaps.org/schemas/sitemap/0.9}url/{http://www.sitemaps.org/schemas/sitemap/0.9}loc')]
    if not 0 < len(urls) <= 10000 or len(set(urls)) != len(urls):
        raise ValueError('Invalid sitemap URL count')
    if any(urllib.parse.urlsplit(url).netloc != parsed.netloc or urllib.parse.urlsplit(url).scheme != 'https' for url in urls):
        raise ValueError('Noncanonical sitemap URL')
    return {'host': parsed.hostname, 'key': key, 'keyLocation': f'{origin}/{key}.txt', 'urlList': urls}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('origin')
    parser.add_argument('--submit', action='store_true')
    args = parser.parse_args()
    payload = prepare(args.origin)
    result = {'urls': len(payload['urlList']), 'submitted': args.submit}
    if args.submit:
        request = urllib.request.Request('https://api.indexnow.org/indexnow', data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json; charset=utf-8'})
        with urllib.request.urlopen(request, timeout=30) as response:
            result['status'] = response.status
        if result['status'] not in (200, 202):
            raise SystemExit('IndexNow did not accept the request')
        result['meaning'] = 'received; key validation pending' if result['status'] == 202 else 'received; indexing not confirmed'
    print(json.dumps(result))
