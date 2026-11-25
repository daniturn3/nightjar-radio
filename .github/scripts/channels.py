#!/usr/bin/env python3
"""Nightjar Radio release channels.

  test   -> release tag radio-beta     (developer only, every CI build)
  beta   -> release tag radio-pubbeta  (public beta, gets each published build straight away)
  stable -> release tag radio-app      (main download: once a beta build has been out 10 days,
                                       stable updates to whatever beta has at that moment)

The web app is channelled the same way: next/index.html (test) -> beta/index.html -> index.html (stable).

Every build published to beta is also kept in release radio-builds as nightjar-radio-<build>.apk,
so stable can later take exactly that build even if beta has moved on.
History lives in stats/channels.json.

Usage: channels.py publish [--stable-now] [--web-only | --app-only]   |   channels.py auto-stable
"""
import json, os, re, subprocess, sys, datetime, pathlib, hashlib, shutil

REPO = os.environ["GITHUB_REPOSITORY"]
DAYS = int(os.environ.get("STABLE_AFTER_DAYS", "10"))
STATE = pathlib.Path("stats/channels.json")
WEB = {"test": pathlib.Path("next/index.html"), "beta": pathlib.Path("beta/index.html"), "stable": pathlib.Path("index.html")}
# Daniel's own extras in the Test copy sit between /*@TESTONLY*/ ... /*@END*/ (or <!--@TESTONLY--> ... <!--@END-->)
# and are cut out whenever the Test copy is published, so they never reach Beta or Stable.
STRIP = re.compile(r"/\*@TESTONLY\*/.*?/\*@END\*/|<!--@TESTONLY-->.*?<!--@END-->", re.S)
def body(ch):
    b = WEB[ch].read_bytes()
    if ch == "test":
        t = STRIP.sub("", b.decode("utf-8"))
        if "@TESTONLY" in t or "@END*/" in t: raise SystemExit("Unmatched @TESTONLY/@END marker in next/index.html")
        for bad in ("devSheet", "Developer tools", "devInbox"):
            if bad in t: raise SystemExit(f"Developer tools code ({bad}) is outside the @TESTONLY markers — not publishing")
        b = t.encode("utf-8")
    return b
def webid(ch): return hashlib.sha256(body(ch)).hexdigest()[:12] if WEB[ch].exists() else ""
def webcopy(a, b):
    if webid(a) == webid(b): return False
    WEB[b].write_bytes(body(a)); return True
TAGS = {"test": "radio-beta", "beta": "radio-pubbeta", "stable": "radio-app", "archive": "radio-builds"}

def sh(*a, check=True, capture=True):
    r = subprocess.run(a, text=True, capture_output=capture)
    if check and r.returncode:
        print(r.stdout, r.stderr, file=sys.stderr); raise SystemExit(r.returncode)
    return (r.stdout or "").strip()

def build_of(tag):
    r = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{tag}", "--jq", ".name"], text=True, capture_output=True)
    m = re.search(r"build (\d+)", r.stdout or "")
    return int(m.group(1)) if m else 0

def ensure(tag, title, notes, pre):
    if subprocess.run(["gh", "release", "view", tag], capture_output=True).returncode:
        sh("gh", "release", "create", tag, "--title", title, "--notes", notes, "--latest=false", *(["--prerelease"] if pre else []))

def load():
    return json.loads(STATE.read_text()) if STATE.exists() else {"beta": [], "stable": None}

def save(d, msg):
    STATE.parent.mkdir(exist_ok=True); STATE.write_text(json.dumps(d, indent=1) + "\n")
    sh("git", "config", "user.name", "github-actions"); sh("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    sh("git", "add", "stats", *[str(f) for f in WEB.values() if f.exists()]); 
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode:
        sh("git", "commit", "-qm", msg)
        for _ in range(3):
            subprocess.run(["git", "pull", "-q", "--rebase", "origin", "main"])
            if subprocess.run(["git", "push", "-q", "origin", "HEAD:main"]).returncode == 0:
                subprocess.run(["gh", "api", "-X", "POST", f"repos/{REPO}/pages/builds"], capture_output=True); return
        raise SystemExit("could not push stats")

def notes(n, label):
    body = pathlib.Path("release-notes.md").read_text() if pathlib.Path("release-notes.md").exists() else ""
    pathlib.Path("notes.md").write_text(f"{body}\n\n_{label} build {n}_\n")

def now(): return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)

def to_beta(d):
    """Copy the test web app and (if newer) the test APK to beta. Returns (build, changed)."""
    n, last = build_of(TAGS["test"]), build_of(TAGS["beta"])
    web = False if "--app-only" in sys.argv else webcopy("test", "beta")  # --app-only: leave the websites alone
    native = bool(n) and n > last and "--web-only" not in sys.argv
    if native:
        sh("gh", "release", "download", TAGS["test"], "-p", "nightjar-radio-beta.apk", "-O", "nightjar-radio.apk", "--clobber")
        if last:  # keep the outgoing beta build's download count before its file is replaced
            bc = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{TAGS['beta']}", "--jq",
                '[.assets[]|select(.name=="nightjar-radio.apk")|.download_count][0] // 0'], text=True, capture_output=True).stdout.strip() or "0"
            p = pathlib.Path("stats/downloads.json"); dl = json.loads(p.read_text()) if p.exists() else []
            dl = [x for x in dl if not (x["build"] == last and x.get("channel") == "beta")] + [{"build": last, "downloads": int(bc), "until": datetime.date.today().isoformat(), "channel": "beta"}]
            p.write_text(json.dumps(dl, indent=1))
        ensure(TAGS["archive"], "Nightjar Radio build archive", "Every build that went to the public beta. Used to promote builds to stable.", True)
        sh("cp", "nightjar-radio.apk", f"nightjar-radio-{n}.apk")
        sh("gh", "release", "upload", TAGS["archive"], f"nightjar-radio-{n}.apk", "--clobber")
        ensure(TAGS["beta"], "Nightjar Radio beta", "Public beta of the Nightjar Radio Android app: new features first, may have bugs.", True)
        sh("gh", "release", "upload", TAGS["beta"], "nightjar-radio.apk", "--clobber")
        notes(n, "Beta"); sh("gh", "release", "edit", TAGS["beta"], "--title", f"Nightjar Radio build {n} (beta)", "--notes-file", "notes.md", "--prerelease")
    b = n if native else last
    if not (web or native):
        print("Nothing new to publish — Beta already has the latest app build and website."); return b, False
    d["beta"].append({"build": b, "web": webid("beta"), "at": now().isoformat()}); d["beta"] = d["beta"][-40:]
    print(f"Beta now has build {b}" + (" (new app build)" if native else "") + (" and the latest website" if web else "")); return b, True

def to_stable(d, n, why):
    cur = build_of(TAGS["stable"])
    web = False if "--app-only" in sys.argv else webcopy("beta", "stable")
    if cur >= n:
        if web: d["stable"] = {"build": cur, "web": webid("stable"), "at": now().isoformat(), "how": why}; print(f"Stable website updated ({why})")
        else: print(f"Stable already has build {cur} and the latest website")
        return web
    sh("gh", "release", "download", TAGS["archive"], "-p", f"nightjar-radio-{n}.apk", "-O", "nightjar-radio.apk", "--clobber")
    # keep an all-time download total for the outgoing stable build
    cnt = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{TAGS['stable']}", "--jq",
        '[.assets[]|select(.name=="nightjar-radio.apk")|.download_count][0] // 0'], text=True, capture_output=True).stdout.strip() or "0"
    if cur:
        p = pathlib.Path("stats/downloads.json"); dl = json.loads(p.read_text()) if p.exists() else []
        dl = [x for x in dl if not (x["build"] == cur and x.get("channel", "stable") == "stable")] + [{"build": cur, "downloads": int(cnt), "until": datetime.date.today().isoformat(), "channel": "stable"}]
        p.write_text(json.dumps(dl, indent=1))
    ensure(TAGS["stable"], "Nightjar Radio", "Android app for Nightjar Radio", False)
    sh("gh", "release", "upload", TAGS["stable"], "nightjar-radio.apk", "--clobber")
    notes(n, "Stable"); sh("gh", "release", "edit", TAGS["stable"], "--title", f"Nightjar Radio build {n}", "--notes-file", "notes.md")
    d["stable"] = {"build": n, "web": webid("stable"), "at": now().isoformat(), "how": why}
    print(f"Build {n} is now stable ({why})"); return True

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    d = load()
    if cmd == "publish":
        n, changed = to_beta(d)
        msg = f"Publish: build {n} + website to Beta"
        if "--stable-now" in sys.argv:
            if to_stable(d, n, "straight to stable"): changed = True; msg = f"Publish: build {n} + website to Beta and Stable"
        if changed: save(d, msg)
    elif cmd == "promote":
        # Daniel asked for Stable to get the current Beta now (use --app-only to leave the Stable website alone).
        n = max([x["build"] for x in d["beta"]] + [build_of(TAGS["beta"])])
        if to_stable(d, n, "Daniel asked"): save(d, f"Stable now has build {n}" + (" (app only)" if "--app-only" in sys.argv else ""))
    elif cmd == "auto-stable":
        cutoff = now() - datetime.timedelta(days=DAYS)
        since = datetime.datetime.fromisoformat(d["stable"]["at"]) if d.get("stable") else datetime.datetime.min.replace(tzinfo=datetime.timezone.utc)
        pending = [x for x in d["beta"] if datetime.datetime.fromisoformat(x["at"]) > since]
        if not pending: print("Stable is up to date."); return
        first = min(datetime.datetime.fromisoformat(x["at"]) for x in pending)
        if first > cutoff: print(f"Stable update due on {(first + datetime.timedelta(days=DAYS)).date()}"); return
        # Due: Stable gets whatever Beta has NOW (app build + website), so anything added and
        # removed again in Beta never reaches Stable.
        n = max([x["build"] for x in d["beta"]] + [build_of(TAGS["beta"])])
        if to_stable(d, n, "stable update due"): save(d, f"Stable now matches Beta (build {n})")
        else: d["stable"] = dict(d.get("stable") or {"build": n}, at=now().isoformat()); save(d, "Stable already matched Beta")
    else:
        raise SystemExit(__doc__)

if __name__ == "__main__":
    main()
