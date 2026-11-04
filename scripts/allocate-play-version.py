#!/usr/bin/env python3
"""Allocate a fresh Play code before signing; source declarations stay unchanged."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys

SPEC = importlib.util.spec_from_file_location('play_publisher', Path(__file__).with_name('publish-play-test.py'))
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-version-code', required=True, type=int)
    parser.add_argument('--version', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--source-tree', required=True)
    parser.add_argument('--target', choices=('internal', 'closed'), required=True)
    parser.add_argument('--closed-track', default='alpha')
    parser.add_argument('--result', required=True, type=Path)
    args = parser.parse_args()
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
    publisher.authorize_target(args.target, os.environ['GITHUB_EVENT_NAME'], event)
    allocation = publisher.allocate_version_code(
        publisher.PublisherAPI(os.environ.get('PLAY_ACCESS_TOKEN', '')),
        args.source_version_code, args.target, args.closed_track)
    plan = publisher.version_plan(allocation, args.version, args.source_sha, args.source_tree)
    args.result.parent.mkdir(parents=True, exist_ok=True)
    args.result.write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
        output.write('RELEASE_VERSION_CODE=' + str(plan['allocated_version_code']) + '\n')
    print(json.dumps(plan))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, publisher.APIError, publisher.TransportError, OSError) as error:
        print(f'::error::Play version allocation failed: {error}', file=sys.stderr)
        sys.exit(1)
