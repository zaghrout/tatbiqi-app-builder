#!/usr/bin/env python3
"""
Reports a build's status back to the Tatbiqi dashboard.

The body is signed with HMAC-SHA256 using BUILD_CALLBACK_SECRET, sent as
`X-Tatbiqi-Signature: sha256=<hex>`. The dashboard rejects unsigned or stale calls.

Usage:
  python3 builder/callback.py --status building|ready|failed \
      [--result-json result.json] [--apk-key apks/ORD/1.apk] [--aab-key aabs/ORD/1.aab] [--error "..."]
  python3 builder/callback.py --platform ios --status ready --screenshot-key ios/ORD/1/screenshot.png

Environment:
  CALLBACK_URL, BUILD_CALLBACK_SECRET, BUILD_ID, ORDER_NUMBER, RUN_URL
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--status", required=True, choices=["building", "ready", "failed"])
    p.add_argument("--result-json", default="")
    p.add_argument("--apk-key", default="")
    p.add_argument("--aab-key", default="")
    p.add_argument("--error", default="")
    p.add_argument("--platform", default="android", choices=["android", "ios"])
    p.add_argument("--screenshot-key", default="")
    p.add_argument("--version-code", type=int, default=None)
    args = p.parse_args()

    url = os.environ.get("CALLBACK_URL", "")
    secret = os.environ.get("BUILD_CALLBACK_SECRET", "")
    if not url or not secret:
        print("⚠️  CALLBACK_URL or BUILD_CALLBACK_SECRET not set; skipping callback")
        return
    if not os.environ.get("BUILD_ID"):
        print("ℹ️  no BUILD_ID (manual test run); skipping callback")
        return

    body = {
        "buildId": os.environ.get("BUILD_ID", ""),
        "orderNumber": os.environ.get("ORDER_NUMBER", ""),
        "status": args.status,
        "runUrl": os.environ.get("RUN_URL", ""),
        "timestamp": int(time.time()),
    }
    if args.result_json and os.path.exists(args.result_json):
        with open(args.result_json, encoding="utf-8") as f:
            r = json.load(f)
        body.update({
            "size": r.get("size"),
            "packageName": r.get("package"),
            "versionCode": r.get("versionCode"),
            "versionName": r.get("versionName"),
            "aabSize": r.get("aabSize"),
        })
    if args.apk_key:
        body["apkKey"] = args.apk_key
    if args.aab_key:
        body["aabKey"] = args.aab_key
    if args.error:
        body["error"] = args.error[:500]
    if args.platform == "ios":
        body["platform"] = "ios"
        if args.screenshot_key:
            body["screenshotKey"] = args.screenshot_key
    if args.version_code is not None:
        body["versionCode"] = args.version_code

    raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
    sig = hmac.new(secret.encode("utf-8"), raw, hashlib.sha256).hexdigest()
    req = urllib.request.Request(url, data=raw, method="POST", headers={
        "Content-Type": "application/json",
        "X-Tatbiqi-Signature": f"sha256={sig}",
        "User-Agent": "TatbiqiBuilder/3",
    })

    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                print(f"✅ callback {args.status}: HTTP {resp.status}")
                return
        except urllib.error.HTTPError as e:
            print(f"⚠️  callback HTTP {e.code}: {e.read()[:300]!r}")
            if 400 <= e.code < 500:
                sys.exit(1)
        except Exception as e:
            print(f"⚠️  callback error: {e}")
        time.sleep(3 * (attempt + 1))
    sys.exit(1)


if __name__ == "__main__":
    main()
