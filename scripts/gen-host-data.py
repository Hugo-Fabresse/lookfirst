#!/usr/bin/env python3
"""Generates the host-normalization data files of LF-16 from the official sources, and proves where they come from (LF-20).

Usage:
  scripts/gen-host-data.py --download DIR     download every source (and the IANA RDAP bootstrap) into DIR, check SHA-256
  scripts/gen-host-data.py --verify           download the Unicode / PSL sources, check, regenerate in a temp dir and
                                              compare byte for byte with the committed files (exit code 1 on any
                                              difference). rdap-dns.json is checked OFFLINE: committed file against the
                                              pinned hash and SHA256SUMS
  scripts/gen-host-data.py --check-rdap-fresh download the IANA RDAP bootstrap and say (::warning::, exit 1) if it is
                                              no longer the pinned one: a reminder to review it, never a build failure
  scripts/gen-host-data.py SOURCE_DIR OUT_DIR generate OUT_DIR (app/src/main/resources/lookfirst/host) from SOURCE_DIR,
                                              after checking the SHA-256 of every source file in it

SOURCES below is the single list of inputs: exact versioned URL + expected SHA-256 of each file. To update Unicode or
the PSL, change the URL, the version and the SHA-256 there, run --download, generate, review the diff (docs/adr/0006).
Output is plain text (except rdap-dns.json, copied as is), one record per line: "LOW HIGH VALUE..." in hexadecimal (code points), '#' lines are comments.
Each file starts with comments naming its source URL, version and SHA-256; SHA256SUMS lists the SHA-256 of the files.
Deterministic: same input, same output. Python standard library only.
"""
import hashlib
import sys
import tempfile
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

UNICODE = "https://www.unicode.org/Public/18.0.0"
# PSL: commit 2dbfce14 of publicsuffix/list, 2026-10-16T23:32:05Z (the published file is stamped
# VERSION 2026-10-16_23-32-13_UTC; the file in git has no VERSION line, so the label is declared here).
PSL = "https://raw.githubusercontent.com/publicsuffix/list/2dbfce14c57bcea83fef2be3bdcb560667d7ef93"
PSL_VERSION = "2026-10-16_23-32-13_UTC (commit 2dbfce14c57bcea83fef2be3bdcb560667d7ef93)"
# name -> (url, version, expected sha256)
SOURCES = {
    "IdnaMappingTable.txt": (f"{UNICODE}/idna/IdnaMappingTable.txt", "Unicode 18.0.0",
                             "a03b1eb38032268c696406a83f0972d6a815acd2c8d4151d42ec0fda70ffced1"),
    "confusables.txt": (f"{UNICODE}/security/confusables.txt", "Unicode 18.0.0",
                        "6ed3ee967c9dfdf6677d563c9985182fbc50a2efb7d6059cd57b2e2ce18f5b92"),
    "Scripts.txt": (f"{UNICODE}/ucd/Scripts.txt", "Unicode 18.0.0",
                    "0071fd81b6aeae25f6e8bce8efec3066a6476a91b49bdb2f52dc76e817862a6a"),
    "DerivedBidiClass.txt": (f"{UNICODE}/ucd/extracted/DerivedBidiClass.txt", "Unicode 18.0.0",
                             "d9e23222522551348ea1ccfbb4f62efbf98982afb95840f8959c08ed992c5607"),
    "DerivedJoiningType.txt": (f"{UNICODE}/ucd/extracted/DerivedJoiningType.txt", "Unicode 18.0.0",
                               "e2408ff2c92b175b0f7bf62c989bbb54c7b077528fe31f8d69b96fa09e7d61ed"),
    "DerivedCombiningClass.txt": (f"{UNICODE}/ucd/extracted/DerivedCombiningClass.txt", "Unicode 18.0.0",
                                  "ef6b2611cfb660dba3f6b458b9eb4b05f44ed2417302ee7749d7f0f348793121"),
    "DerivedGeneralCategory.txt": (f"{UNICODE}/ucd/extracted/DerivedGeneralCategory.txt", "Unicode 18.0.0",
                                   "d6b151d2d40ee9b1876d26f417980f45ffae47b6055ccf7203cb31f07a030f94"),
    "public_suffix_list.dat": (f"{PSL}/public_suffix_list.dat", "PSL " + PSL_VERSION,
                               "13879fec7cb7f6c67991de0810874f511d67eca2d99af53e40db2a833b207cdc"),
    # Test vectors: not embedded in the APK, compared byte for byte by --verify.
    "IdnaTestV2.txt": (f"{UNICODE}/idna/IdnaTestV2.txt", "Unicode 18.0.0",
                       "0236b75c5b20dfd857b3b5cf75509887959d6ab00d6c7bda9b7dc3df518c1fde"),
    "test_psl.txt": (f"{PSL}/tests/test_psl.txt", "PSL " + PSL_VERSION,
                     "8f50ad958916d6a8f79fba2363501475571acce752757f9126fe9d2f17dd920d"),
}
# IANA RDAP bootstrap (RFC 9224), embedded as is (LF-27). The URL is not versioned: the SHA-256 pins the content. --verify
# only checks the committed copy against this hash; a new IANA publication is noticed by --check-rdap-fresh (a warning)
# and handled by hand (docs/design/reputation-sources.md, "Updating the RDAP bootstrap").
RDAP_NAME = "rdap-dns.json"
RDAP_URL = "https://data.iana.org/rdap/dns.json"
RDAP_SHA256 = "e3419eb0b28dcc3a68dd3bb0e64429cb0d376c3883ca4c42317aea65fc0ab471"
TEST_SOURCES = ("IdnaTestV2.txt", "test_psl.txt")
ROOT = Path(__file__).resolve().parent.parent
MAIN_OUT = ROOT / "app/src/main/resources/lookfirst/host"
TEST_OUT = ROOT / "app/src/test/resources/lookfirst/host"
MAX_BYTES = 8 * 1024 * 1024
LICENSE = "Unicode License v3 (https://www.unicode.org/license.txt)"


def sha256_of(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url):
    """HTTPS only, size-bounded, no redirect to a non-https URL. Returns (data, error)."""
    try:
        with urllib.request.urlopen(url, timeout=120) as response:  # nosemgrep: fixed https URLs from SOURCES
            data = response.read(MAX_BYTES + 1)
            final_url = response.geturl()
    except (urllib.error.URLError, TimeoutError, OSError) as error:  # HTTPError is a URLError
        return None, f"download failed ({type(error).__name__}) {url}"
    if not final_url.startswith("https://"):
        return None, "redirected to a non-https URL"
    return data, None


def check_rdap_fresh():
    """Warns (not a failure of the build) when the IANA RDAP bootstrap is no longer the pinned one."""
    data, error = fetch(RDAP_URL)
    if error:
        print(f"::warning::RDAP bootstrap freshness not checked: {error}")
        return [f"{RDAP_NAME}: {error}"]
    actual = sha256_of(data)
    if len(data) > MAX_BYTES or actual != RDAP_SHA256:
        print(f"::warning::The IANA RDAP bootstrap changed (sha256 {actual}, pinned {RDAP_SHA256}). "
              "Review it and update the pin: docs/design/reputation-sources.md, Updating the RDAP bootstrap.")
        return [f"{RDAP_NAME}: no longer the pinned file"]
    print("ok", RDAP_NAME, "still the pinned file")
    return []


def check_committed_rdap(path):
    """Offline: the committed copy is the pinned file and SHA256SUMS says so."""
    failures = []
    if not path.exists() or sha256_of(path.read_bytes()) != RDAP_SHA256:
        failures.append(f"{RDAP_NAME}: committed file is not the pinned one ({RDAP_SHA256})")
    sums = (MAIN_OUT / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    if f"{RDAP_SHA256}  {RDAP_NAME}" not in sums:
        failures.append(f"{RDAP_NAME}: SHA256SUMS does not list the pinned hash")
    return failures


def download(dest, with_rdap=False):
    """Downloads every source into dest (HTTPS only, size-bounded) and checks its SHA-256. Returns the failures."""
    dest.mkdir(parents=True, exist_ok=True)
    failures = []
    if with_rdap:
        data, error = fetch(RDAP_URL)
        if error:
            failures.append(f"{RDAP_NAME}: {error}")
        elif len(data) > MAX_BYTES or sha256_of(data) != RDAP_SHA256:
            failures.append(f"{RDAP_NAME}: sha256 {sha256_of(data)}, expected {RDAP_SHA256} ({RDAP_URL})")
        else:
            (dest / RDAP_NAME).write_bytes(data)
            print("ok", RDAP_NAME, RDAP_SHA256)
    for name, (url, _version, expected) in SOURCES.items():
        if not url.startswith("https://"):
            failures.append(f"{name}: not an https URL")
            continue
        try:
            with urllib.request.urlopen(url, timeout=120) as response:  # nosemgrep: fixed https URLs from SOURCES
                data = response.read(MAX_BYTES + 1)
                final_url = response.geturl()
        except (urllib.error.URLError, TimeoutError, OSError) as error:  # HTTPError is a URLError
            failures.append(f"{name}: download failed ({type(error).__name__}) {url}")
            continue
        if not final_url.startswith("https://"):
            failures.append(f"{name}: redirected to a non-https URL")
            continue
        actual = sha256_of(data)
        if len(data) > MAX_BYTES or actual != expected:
            failures.append(f"{name}: sha256 {actual}, expected {expected} ({url})")
        else:
            (dest / name).write_bytes(data)
            print("ok", name, actual)
    return failures


def check_sources(directory):
    failures = []
    for name, (_url, _version, expected) in SOURCES.items():
        path = directory / name
        if name in TEST_SOURCES and not path.exists():
            continue
        if not path.exists():
            failures.append(f"{name}: missing in {directory}")
        elif sha256_of(path.read_bytes()) != expected:
            failures.append(f"{name}: sha256 differs from the one in SOURCES")
    return failures


def source_lines(*names):
    """Header comments naming where a generated file comes from."""
    return "".join(f"# Source: {SOURCES[n][0]} ; {SOURCES[n][1]} ; SHA-256 {SOURCES[n][2]}\n" for n in names)


def compare(expected_dir, actual_dir, names):
    diffs = []
    for name in names:
        a, b = expected_dir / name, actual_dir / name
        if not b.exists():
            diffs.append(f"{name}: missing")
        elif a.read_bytes() != b.read_bytes():
            diffs.append(f"{name}: differs")
    return diffs


def write_sums(out):
    names = sorted(p.name for p in [*out.glob("*.txt"), *out.glob("*.json")])
    with open(out / "SHA256SUMS", "w", encoding="utf-8", newline="\n") as f:
        for name in names:
            f.write(f"{sha256_of((out / name).read_bytes())}  {name}\n")


def main():
    args = sys.argv[1:]
    if len(args) == 2 and args[0] == "--download":
        failures = download(Path(args[1]), with_rdap=True)
    elif args == ["--check-rdap-fresh"]:
        failures = check_rdap_fresh()
    elif args == ["--verify"]:
        with tempfile.TemporaryDirectory() as tmp:
            src_dir, out_dir = Path(tmp) / "src", Path(tmp) / "out"
            failures = download(src_dir) + check_committed_rdap(MAIN_OUT / RDAP_NAME)
            if not failures:
                (src_dir / RDAP_NAME).write_bytes((MAIN_OUT / RDAP_NAME).read_bytes())
                generate(src_dir, out_dir)
                failures = compare(out_dir, MAIN_OUT, sorted(p.name for p in out_dir.iterdir()))
                failures += compare(src_dir, TEST_OUT, TEST_SOURCES)
                extra = {p.name for p in MAIN_OUT.iterdir()} - {p.name for p in out_dir.iterdir()}
                failures += [f"{n}: committed but not generated" for n in sorted(extra)]
                extra_tests = {p.name for p in TEST_OUT.iterdir()} - set(TEST_SOURCES)
                failures += [f"{n}: in test data but not a source" for n in sorted(extra_tests)]
    elif len(args) == 2 and not args[0].startswith("-"):
        failures = check_sources(Path(args[0]))
        if not (Path(args[0]) / RDAP_NAME).exists():
            failures.append(f"{RDAP_NAME}: missing in {args[0]}")
        if not failures:
            generate(Path(args[0]), Path(args[1]))
    else:
        sys.exit(__doc__)
    for failure in failures:
        print("FAIL", failure, file=sys.stderr)
    if failures:
        sys.exit(1)
    print("all good")


def generate(src_dir, out_dir):
    global src, out
    src, out = src_dir, out_dir
    out.mkdir(parents=True, exist_ok=True)
    generate_files()
    write_sums(out)


src = out = None  # set by generate()


def lines_of(name):
    return (src / name).read_text(encoding="utf-8").splitlines()


def meta(name):
    return " ".join(l.lstrip("# ").strip() for l in lines_of(name)[:12] if l.startswith("#") and ("Version:" in l or "Date:" in l))


def records(name):
    for line in lines_of(name):
        line = line.split("#", 1)[0].strip()
        if line:
            yield [f.strip() for f in line.split(";")]


def span(field):
    lo, _, hi = field.partition("..")
    return int(lo, 16), int(hi or lo, 16)


def write_ranges(name, rows, comment, source):
    merged = []
    for lo, hi, val in sorted(rows):
        if merged and merged[-1][2] == val and merged[-1][1] + 1 == lo:
            merged[-1] = (merged[-1][0], hi, val)
        else:
            merged.append((lo, hi, val))
    with open(out / name, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# {comment}\n# Generated by scripts/gen-host-data.py, do not edit. {LICENSE}\n")
        f.write(source_lines(source))
        for lo, hi, val in merged:
            f.write(f"{lo:X} {hi:X} {val}".rstrip() + "\n")
    print(name, len(merged))


def generate_files():
    STATUS = {"valid": "V", "ignored": "I", "mapped": "M", "deviation": "D", "disallowed": "X",
              "disallowed_STD3_valid": "3", "disallowed_STD3_mapped": "4"}
    idna = []
    valid_idna = set()
    for r in records("IdnaMappingTable.txt"):
        lo, hi = span(r[0])
        st = STATUS[r[1]]
        if st == "X":
            continue  # disallowed is the default
        mapping = ",".join(f"{int(x, 16):X}" for x in r[2].split()) if st in "M4" else ""
        idna.append((lo, hi, f"{st} {mapping}".strip()))
        if st in "VD":
            valid_idna.update(range(lo, hi + 1))
    write_ranges("idna-mapping.txt", idna, "UTS #46 IdnaMappingTable. " + meta("IdnaMappingTable.txt")
                 + ". V valid, I ignored, M mapped, D deviation, 3/4 STD3 valid/mapped, absent = disallowed", "IdnaMappingTable.txt")
    write_ranges("bidi-class.txt", [(*span(r[0]), r[1]) for r in records("DerivedBidiClass.txt") if len(r) >= 2],
                 "Bidi_Class. " + meta("DerivedBidiClass.txt"), "DerivedBidiClass.txt")
    write_ranges("joining-type.txt",
                 [(*span(r[0]), r[1]) for r in records("DerivedJoiningType.txt") if len(r) >= 2 and r[1] in "DLRT"],
                 "Joining_Type D L R T. " + meta("DerivedJoiningType.txt"), "DerivedJoiningType.txt")
    write_ranges("virama.txt", [(*span(r[0]), "9") for r in records("DerivedCombiningClass.txt") if len(r) >= 2 and r[1] == "9"],
                 "Canonical_Combining_Class=9. " + meta("DerivedCombiningClass.txt"), "DerivedCombiningClass.txt")
    write_ranges("marks.txt",
                 [(*span(r[0]), "M") for r in records("DerivedGeneralCategory.txt") if len(r) >= 2 and r[1] in ("Mn", "Mc", "Me")],
                 "General_Category M. " + meta("DerivedGeneralCategory.txt"), "DerivedGeneralCategory.txt")
    write_ranges("scripts.txt", [(*span(r[0]), r[1]) for r in records("Scripts.txt") if len(r) >= 2],
                 "Script. " + meta("Scripts.txt"), "Scripts.txt")

    # confusables: single-code-point sources that IDNA allows; "SRC TGT TGT ..." in hex.
    conf = {}
    for r in records("confusables.txt"):
        if len(r) < 3 or r[2] != "MA":
            continue
        s = r[0].split()
        if len(s) == 1 and int(s[0], 16) in valid_idna:
            conf[int(s[0], 16)] = " ".join(f"{int(x, 16):X}" for x in r[1].split())
    with open(out / "confusables.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("# UTS #39 confusables.txt (MA table), sources limited to IDNA-valid code points. " + meta("confusables.txt") + "\n")
        f.write(f"# Generated by scripts/gen-host-data.py, do not edit. {LICENSE}\n")
        f.write(source_lines("IdnaMappingTable.txt", "confusables.txt"))
        for cp in sorted(conf):
            f.write(f"{cp:X} {conf[cp]}\n")
    print("confusables.txt", len(conf))

    # Public Suffix List: rules only, non-ASCII labels as punycode (MPL-2.0, https://publicsuffix.org/).
    psl = lines_of("public_suffix_list.dat")
    version = "VERSION: " + PSL_VERSION.split()[0]
    icann, private = set(), set()
    target = icann
    for line in psl:
        line = line.strip()
        if line.startswith("// ===BEGIN PRIVATE DOMAINS==="):
            target = private
        if not line or line.startswith("//"):
            continue
        line = line.split()[0]
        prefix = "!" if line.startswith("!") else ""
        labels = []
        for lab in line.lstrip("!").split("."):
            if lab.isascii():
                labels.append(lab)
            else:
                labels.append("xn--" + unicodedata.normalize("NFC", lab).lower().encode("punycode").decode("ascii"))
        target.add(prefix + ".".join(labels))
    with open(out / "public-suffix-list.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Public Suffix List, rules only (Mozilla Public License 2.0, https://publicsuffix.org/list/). {version}\n")
        f.write("# Generated by scripts/gen-host-data.py, do not edit. '*.x' wildcard, '!x' exception, others plain.\n")
        f.write(source_lines("public_suffix_list.dat"))
        f.write("# ICANN section first, then the line #PRIVATE and the private section (hosting platforms anyone can use).\n")
        for rule in sorted(icann):
            f.write(rule + "\n")
        f.write("#PRIVATE\n")
        for rule in sorted(private - icann):
            f.write(rule + "\n")
    print("public-suffix-list.txt", len(icann), len(private))

    # RDAP bootstrap: embedded byte for byte (JSON has no comments; where it comes from is SOURCES and SHA256SUMS).
    # The app reads it with a strict, bounded parser and keeps only https servers (data/reputation/RdapBootstrap).
    (out / RDAP_NAME).write_bytes((src / RDAP_NAME).read_bytes())
    print(RDAP_NAME)


if __name__ == "__main__":
    main()
