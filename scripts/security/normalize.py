#!/usr/bin/env python3
"""Turn a semgrep / gitleaks / osv-scanner JSON report into a tool-neutral findings list + a job summary.

usage: normalize.py <semgrep|gitleaks|osv> <report.json> <findings.json>

Secrets never reach the output: gitleaks must be run with --redact, and only rule / location
are copied anyway. The fingerprint is stable across runs (no line number for semgrep) so the
issue job can deduplicate. File names and rule messages are untrusted (a PR controls them):
they only appear as inline code, sanitised by clean().
"""
import hashlib
import json
import os
import sys
from urllib.parse import quote

tool, report_path, out_path = sys.argv[1:4]
repo = os.environ.get("GITHUB_REPOSITORY", "")
sha = os.environ.get("GITHUB_SHA", "HEAD")
server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")

with open(report_path) as f:
    raw = json.load(f)


def clean(text):
    """Make untrusted text inert in markdown: no @-mention, no HTML, no backtick, no new line."""
    text = str(text).replace("`", "'").replace("\r", " ").replace("\n", " ")
    return text.replace("<", "＜").replace(">", "＞").replace("@", "＠")


def code(text):
    """Untrusted text as inline code (cannot close the span: backticks are stripped)."""
    return f"`{clean(text)}`"


def blob(path, line):
    return f"{server}/{repo}/blob/{sha}/{quote(path)}#L{line}"


def line_text(path, line):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()[line - 1].strip()
    except (OSError, IndexError):
        return f"line:{line}"


findings = []
seen = {}
if tool == "semgrep":
    for r in raw.get("results", []):
        path, line = r["path"], r["start"]["line"]
        rule = r["check_id"]
        fp = hashlib.sha256(f"{rule}|{path}|{line_text(path, line)}".encode()).hexdigest()[:24]
        n = seen[fp] = seen.get(fp, 0) + 1  # same rule on identical lines of one file
        if n > 1:
            fp = f"{fp}-{n}"
        short = rule.rsplit(".", 1)[-1]
        msg = clean(r["extra"].get("message", "").strip())
        sev = clean(r["extra"].get("severity", ""))
        where = code(f"{path}:{line}")
        findings.append({
            "tool": tool, "fingerprint": f"semgrep-{fp}",
            "title": clean(f"[security][semgrep] {short} in {path}:{line}"),
            "line": f"{sev} {code(rule)} {where}",
            "body": f"**Rule** : {code(rule)} ({sev})\n\n**Fichier** : {where} ([voir]({blob(path, line)}))\n\n{msg}",
        })
elif tool == "gitleaks":
    for r in raw or []:
        path, line = r["File"], r["StartLine"]
        rule = r["RuleID"]
        fp = hashlib.sha256(r["Fingerprint"].encode()).hexdigest()[:24]
        commit = clean(r["Commit"][:10])
        where = code(f"{path}:{line}")
        findings.append({
            "tool": tool, "fingerprint": f"gitleaks-{fp}",
            "title": clean(f"[security][gitleaks] {rule} in {path}:{line}"),
            "line": f"{code(rule)} {where} (commit {commit})",
            "body": (f"**Rule** : {code(rule)} - {clean(r.get('Description', ''))}\n\n**Fichier** : {where}"
                     f"\n\n**Commit** : `{commit}` (valeur masquee, voir `git show {commit}`)\n\n"
                     "Si c'est un vrai secret : le revoquer d'abord, nettoyer l'historique ensuite."),
        })
elif tool == "osv":
    # osv-scanner --format json on app/gradle.lockfile. One finding per (package, version, vulnerability
    # group): aliases of the same advisory (GHSA / CVE) are grouped by the scanner.
    for res in raw.get("results", []):
        lock_name = "app/gradle.lockfile"
        shipped = "l'APK"
        for pkg in res.get("packages", []):
            name, version = pkg["package"]["name"], pkg["package"]["version"]
            by_id = {v["id"]: v for v in pkg.get("vulnerabilities", [])}
            for grp in pkg.get("groups", []):
                ids = sorted(grp.get("ids", []))
                if not ids:
                    continue
                vid = ids[0]
                v = by_id.get(vid, {})
                fixed = sorted({e["fixed"] for a in v.get("affected", []) for r in a.get("ranges", [])
                                for e in r.get("events", []) if "fixed" in e})
                fp = hashlib.sha256(f"{name}|{version}|{vid}".encode()).hexdigest()[:24]
                sev = clean(grp.get("max_severity") or v.get("database_specific", {}).get("severity", "?"))
                aliases = ", ".join(code(a) for a in sorted(grp.get("aliases", [])) if a != vid)
                summ = v.get("summary", "")
                summ = code(summ[:300]) if summ else ""  # inline code: no link, no #n reference rendered
                dep = f"{name} {version}"
                body = (f"**Vulnerabilite** : {code(vid)} (severite {code(sev)}) - [osv.dev](https://osv.dev/{quote(vid)})"
                        f"\n\n**Dependance livree dans {shipped}** : {code(dep)} ({code(lock_name)})\n\n{summ}")
                if aliases:
                    body += f"\n\n**Alias** : {aliases}"
                if fixed:
                    body += f"\n\n**Corrigee dans** : {', '.join(code(x) for x in fixed)}"
                body += ("\n\nFaux positif ou risque accepte : entree `IgnoredVulns` dans `osv-scanner.toml` "
                         "(raison + `ignoreUntil`). Sinon mettre a jour la dependance puis regenerer "
                         f"`{lock_name}`.")
                findings.append({
                    "tool": tool, "fingerprint": f"osv-{fp}",
                    "title": clean(f"[security][osv] {vid} in {dep}"),
                    "line": f"{code(vid)} {code(dep)} (severite {code(sev)})",
                    "body": body,
                })
else:
    sys.exit(f"unknown tool {tool}")

with open(out_path, "w") as f:
    json.dump(findings, f, indent=1)

summary = [f"### {tool} : {len(findings)} finding(s)", ""]
summary += [f"- {x['line']}" for x in findings[:100]]
if len(findings) > 100:
    summary.append(f"- ... et {len(findings) - 100} de plus (voir l'artefact du job)")
text = "\n".join(summary) + "\n"
print(text)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
        f.write(text)
