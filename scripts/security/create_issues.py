#!/usr/bin/env python3
"""Create one issue per new finding (label `security`, assigned to the owner).

usage: create_issues.py <dir with *.json findings files>
env: GH_TOKEN, SECURITY_ASSIGNEE (default Hugo-Fabresse), MAX_NEW_ISSUES (default 50)

Dedup: the fingerprint is stored in the issue body as `<!-- fingerprint: X -->`; open AND closed
issues with the label are searched (only those authored by app/github-actions), and a closed one
is never reopened (closed on purpose).

Known limit: the fingerprint is rule + file + text of the line (no line number), so a NEW
finding with the same rule on an identical line of the same file collides with an existing
(possibly closed) issue and is not reported. Issues by other authors are ignored.
"""
import glob
import json
import os
import re
import subprocess
import sys

LABEL = "security"
assignee = os.environ.get("SECURITY_ASSIGNEE", "Hugo-Fabresse")
cap = int(os.environ.get("MAX_NEW_ISSUES", "50"))


def gh(*args):
    try:
        return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as e:
        print(f"gh {' '.join(args[:2])} failed: {e.stderr}", file=sys.stderr)
        raise


findings = []
for p in sorted(glob.glob(os.path.join(sys.argv[1], "**", "*.json"), recursive=True)):
    with open(p) as f:
        findings += json.load(f)
print(f"{len(findings)} finding(s) in reports")

labels = json.loads(gh("label", "list", "--limit", "200", "--json", "name"))
if LABEL not in [x["name"] for x in labels]:
    gh("label", "create", LABEL, "--color", "B60205", "--description", "Alerte du scan de securite automatique")

existing = json.loads(gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", "5000", "--json", "body,author"))
known = set()
for i in existing:
    # Only issues created by the workflow itself count: a user cannot mask a finding by forging a marker.
    if (i.get("author") or {}).get("login") != "app/github-actions":
        continue
    known.update(re.findall(r"<!-- fingerprint: ([\w-]+) -->", i.get("body") or ""))

created = skipped = 0
for f in findings:
    if f["fingerprint"] in known:
        skipped += 1
        continue
    if created >= cap:
        print(f"cap {cap} reached, the rest is picked up by the next run")
        break
    body = (f["body"] + "\n\n---\nTrouve par le workflow `security.yml`. Fermer l'issue = triage fait "
            f"(elle ne sera pas rouverte).\n<!-- fingerprint: {f['fingerprint']} -->\n")
    gh("issue", "create", "--title", f["title"][:250], "--body", body, "--label", LABEL, "--assignee", assignee)
    known.add(f["fingerprint"])
    created += 1
print(f"created {created}, already known {skipped}")
