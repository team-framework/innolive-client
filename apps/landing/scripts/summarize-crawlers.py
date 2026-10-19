"""Aggregate privacy-limited crawler signals from timestamped Docker stdout.

ssh HOST 'docker logs --timestamps --since 336h CONTAINER 2>&1' | python3 scripts/summarize-crawlers.py
Counts self-reported User-Agents, not authenticated bot identities or successful indexing.
"""
from collections import Counter
import json
import sys

counts, paths, dates = Counter(), Counter(), []
for line in sys.stdin:
    start = line.find('{')
    if start < 0:
        continue
    try:
        event = json.loads(line[start:])
    except json.JSONDecodeError:
        continue
    if not isinstance(event, dict) or event.get('event') != 'search_crawler_request':
        continue
    counts[event['crawler']] += 1
    paths[(event['crawler'], event['path'])] += 1
    timestamp = line[:start].strip().split(' ')[0]
    if timestamp:
        dates.append(timestamp)
print(json.dumps({'identity': 'self-reported User-Agent; not verified bots',
                  'total': sum(counts.values()), 'firstObserved': min(dates) if dates else None,
                  'lastObserved': max(dates) if dates else None, 'byCrawler': dict(counts),
                  'byPath': [{'crawler': bot, 'path': path, 'count': count} for (bot, path), count in sorted(paths.items())]}, ensure_ascii=False, indent=2))
