#!/usr/bin/env python3
"""Nightjar Radio release channels.

  test   -> release tag radio-beta     (developer only, every CI build)
  beta   -> release tag radio-pubbeta  (public beta, gets each published build straight away)
  stable -> release tag radio-app      (main download, gets a beta build once it has been out 10 days)

Every build published to beta is also kept in release radio-builds as nightjar-radio-<build>.apk,
so stable can later take exactly that build even if beta has moved on.
History lives in stats/channels.json.

Usage: channels.py publish [--stable-now]   |   channels.py auto-stable
"""
import json, os, re, subprocess, sys, datetime, pathlib

REPO = os.environ["GITHUB_REPOSITORY"]
DAYS = int(os.environ.get("STABLE_AFTER_DAYS", "10"))
STATE = pathlib.Path("stats/channels.json")
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
    sh("git", "add", "stats"); 
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode:
        sh("git", "commit", "-qm", msg + " [skip ci]")
        for _ in range(3):
            subprocess.run(["git", "pull", "-q", "--rebase", "origin", "main"])
            if subprocess.run(["git", "push", "-q", "origin", "HEAD:main"]).returncode == 0: return
        raise SystemExit("could not push stats")

def notes(n, label):
    body = pathlib.Path("android/RELEASE_NOTES.md").read_text() if pathlib.Path("android/RELEASE_NOTES.md").exists() else ""
    pathlib.Path("notes.md").write_text(f"{body}\n\n_{label} build {n}_\n")

def now(): return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)

def to_beta(d):
    n = build_of(TAGS["test"])
    if not n: raise SystemExit("::error::No test build found")
    if any(x["build"] == n for x in d["beta"]):
        print(f"Build {n} is already in beta"); return n
    sh("gh", "release", "download", TAGS["test"], "-p", "nightjar-radio-beta.apk", "-O", "nightjar-radio.apk", "--clobber")
    # keep the outgoing beta build's download count before its file is replaced
    ob = build_of(TAGS["beta"])
    if ob:
        bc = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{TAGS['beta']}", "--jq",
            '[.assets[]|select(.name=="nightjar-radio.apk")|.download_count][0] // 0'], text=True, capture_output=True).stdout.strip() or "0"
        p = pathlib.Path("stats/downloads.json"); dl = json.loads(p.read_text()) if p.exists() else []
        dl = [x for x in dl if not (x["build"] == ob and x.get("channel") == "beta")] + [{"build": ob, "downloads": int(bc), "until": datetime.date.today().isoformat(), "channel": "beta"}]
        p.write_text(json.dumps(dl, indent=1))
    ensure(TAGS["archive"], "Nightjar Radio build archive", "Every build that went to the public beta. Used to promote builds to stable.", True)
    sh("cp", "nightjar-radio.apk", f"nightjar-radio-{n}.apk")
    sh("gh", "release", "upload", TAGS["archive"], f"nightjar-radio-{n}.apk", "--clobber")
    ensure(TAGS["beta"], "Nightjar Radio beta", "Public beta of the Nightjar Radio Android app: new features first, may have bugs.", True)
    sh("gh", "release", "upload", TAGS["beta"], "nightjar-radio.apk", "--clobber")
    notes(n, "Beta"); sh("gh", "release", "edit", TAGS["beta"], "--title", f"Nightjar Radio build {n} (beta)", "--notes-file", "notes.md", "--prerelease")
    d["beta"].append({"build": n, "at": now().isoformat()}); d["beta"] = d["beta"][-30:]
    print(f"Build {n} is now in beta"); return n

def to_stable(d, n, why):
    cur = build_of(TAGS["stable"])
    if cur >= n: print(f"Stable already has build {cur}"); return False
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
    d["stable"] = {"build": n, "at": now().isoformat(), "how": why}
    print(f"Build {n} is now stable ({why})"); return True

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    d = load()
    if cmd == "publish":
        n = to_beta(d)
        msg = f"Build {n} published to beta"
        if "--stable-now" in sys.argv and to_stable(d, n, "straight to stable"):
            msg = f"Build {n} published to beta and stable"
        save(d, msg)
    elif cmd == "auto-stable":
        cutoff = now() - datetime.timedelta(days=DAYS)
        cur = build_of(TAGS["stable"])
        ready = [x for x in d["beta"] if x["build"] > cur and datetime.datetime.fromisoformat(x["at"]) <= cutoff]
        if not ready:
            waiting = [x for x in d["beta"] if x["build"] > cur]
            print("Nothing ready for stable." + (f" Next: build {waiting[0]['build']} on {(datetime.datetime.fromisoformat(waiting[0]['at'])+datetime.timedelta(days=DAYS)).date()}" if waiting else ""))
            return
        n = max(x["build"] for x in ready)
        if to_stable(d, n, f"{DAYS} days in beta"): save(d, f"Build {n} promoted to stable")
    else:
        raise SystemExit(__doc__)

if __name__ == "__main__":
    main()
