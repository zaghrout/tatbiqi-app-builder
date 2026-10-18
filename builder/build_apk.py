#!/usr/bin/env python3
"""
Tatbiqi app builder (v4).

Builds a signed WebView APK for one store with aapt2 + d8 + apksigner, no Gradle,
and with --aab-output also a signed Android App Bundle (AAB) for Google Play,
packaged with bundletool and signed with jarsigner using the same key.
Runs on GitHub Actions (see .github/workflows/build-apk.yml) or locally.

Per-store values never go into Java source. They are written to
assets/tatbiqi.json and read by MainActivity at runtime.

Usage:
  python3 builder/build_apk.py \
      --store-url https://mystore.salla.sa --app-name "متجري" \
      --package-name sa.tatbiqi.mystore --primary-color 0F766E \
      --whatsapp 966555555555 --logo-url https://.../logo.png \
      --version-code 1 --version-name 1.0.0 --output out/app.apk \
      --aab-output out/app.aab

Environment:
  ANDROID_HOME          Android SDK root (required)
  BUILD_TOOLS_VERSION   default 36.0.0
  ANDROID_PLATFORM      default android-36
  BUNDLETOOL_JAR        path to bundletool-all.jar (required with --aab-output)
  KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
                        signing key. If KEYSTORE_PATH is unset and
                        --throwaway-key is passed, a temporary key is generated
                        (for local testing only: such APKs can never be updated).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

HERE = Path(__file__).resolve().parent
TEMPLATE_JAVA = HERE / "template" / "MainActivity.java"
JAVA_PACKAGE = "com.tatbiqi.app"  # code package; the app id is --package-name
MIN_SDK = "23"
TARGET_SDK = "36"  # Android 16, required by Google Play for new apps and updates

MAX_LOGO_BYTES = 5 * 1024 * 1024
ICON_SIZES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]


class BuildError(Exception):
    pass


# ---------------------------------------------------------------- validation

def validate(args):
    url = args.store_url.strip()
    if not re.fullmatch(r"https://[A-Za-z0-9.-]+(:\d+)?(/[^\s\"'<>\\]*)?", url):
        raise BuildError("store-url must be an https:// URL")

    name = args.app_name.strip()
    if not name or len(name) > 50:
        raise BuildError("app-name must be 1-50 characters")
    if any(ord(c) < 32 for c in name):
        raise BuildError("app-name contains control characters")

    color = args.primary_color.strip().lstrip("#")
    if not re.fullmatch(r"[0-9A-Fa-f]{6}", color):
        raise BuildError("primary-color must be 6 hex digits")

    pkg = args.package_name.strip()
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}", pkg) or len(pkg) > 100:
        raise BuildError("package-name must look like sa.tatbiqi.storename")

    wa = re.sub(r"\D", "", args.whatsapp or "")
    if wa and not (8 <= len(wa) <= 15):
        raise BuildError("whatsapp must be 8-15 digits with country code")

    msg = (args.whatsapp_message or "").strip()[:300]

    logo = (args.logo_url or "").strip()
    if logo and not re.fullmatch(r"https?://\S+", logo):
        raise BuildError("logo-url must be an http(s) URL")

    if not (1 <= args.version_code <= 2_100_000_000):
        raise BuildError("version-code out of range")
    if not re.fullmatch(r"\d+(\.\d+){0,3}", args.version_name):
        raise BuildError("version-name must look like 1.0.0")

    return {
        "store_url": url,
        "app_name": name,
        "color": color.upper(),
        "package": pkg,
        "whatsapp": wa,
        "whatsapp_message": msg,
        "logo_url": logo,
    }


# ---------------------------------------------------------------- tools

def sdk_paths():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise BuildError("ANDROID_HOME is not set")
    bt = Path(home) / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "36.0.0")
    platform = Path(home) / "platforms" / os.environ.get("ANDROID_PLATFORM", "android-36") / "android.jar"
    for p in (bt / "aapt2", bt / "d8", bt / "zipalign", bt / "apksigner", platform):
        if not p.exists():
            raise BuildError(f"missing SDK file: {p}")
    return bt, platform


def run(cmd, secret=None):
    shown = " ".join(str(c) for c in cmd)
    if secret:
        shown = shown.replace(secret, "***")
    print(f"$ {shown}", flush=True)
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.stdout.strip():
        print(r.stdout.rstrip())
    if r.returncode != 0:
        err = r.stderr if not secret else r.stderr.replace(secret, "***")
        print(err, file=sys.stderr)
        tail = " | ".join((err or r.stdout).strip().splitlines()[-6:])[:900]
        raise BuildError(f"step failed: {Path(str(cmd[-1] if cmd[0] == 'java' else cmd[0])).name} {tail}")
    return r


# ---------------------------------------------------------------- resources

def write_manifest(work, cfg):
    label = xml_escape(cfg["app_name"], {'"': "&quot;"})
    manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{JAVA_PACKAGE}">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application
        android:label="{label}"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:theme="@android:style/Theme.DeviceDefault.Light.NoActionBar"
        android:hardwareAccelerated="true"
        android:enableOnBackInvokedCallback="true">
        <activity
            android:name="{JAVA_PACKAGE}.MainActivity"
            android:exported="true"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
'''
    (work / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")


def write_default_icon(res, color):
    d = res / "mipmap"
    d.mkdir(parents=True, exist_ok=True)
    (d / "ic_launcher.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp"
    android:viewportWidth="48" android:viewportHeight="48">
    <path android:fillColor="#{color}" android:pathData="M8,0h32a8,8 0,0 1,8 8v32a8,8 0,0 1,-8 8h-32a8,8 0,0 1,-8 -8v-32a8,8 0,0 1,8 -8z" />
    <path android:fillColor="#FFFFFF"
        android:pathData="M16,19 L32,19 L33,37 L15,37 Z M20,19 v-2 a4,4 0,0 1,8 0 v2 h-2 v-2 a2,2 0,0 0,-4 0 v2 z" />
</vector>
''', encoding="utf-8")


def download_logo(url):
    req = urllib.request.Request(url, headers={"User-Agent": "TatbiqiBuilder/3"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = resp.read(MAX_LOGO_BYTES + 1)
    if len(data) > MAX_LOGO_BYTES:
        raise BuildError("logo is larger than 5MB")
    return data


def write_logo_icons(res, logo_bytes, color):
    """Square icon: logo centered on a rounded tile of the store color."""
    from PIL import Image, ImageDraw

    logo = Image.open(io.BytesIO(logo_bytes))
    logo.load()
    logo = logo.convert("RGBA")
    bbox = logo.getbbox()
    if bbox:
        logo = logo.crop(bbox)

    bg = tuple(int(color[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
    for folder, size in ICON_SIZES:
        scale = 4  # draw big, then downsample for smooth edges
        big = size * scale
        tile = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=big // 6, fill=255)
        tile.paste(Image.new("RGBA", (big, big), bg), (0, 0), mask)

        inner = int(big * 0.72)
        w, h = logo.size
        ratio = min(inner / w, inner / h)
        fitted = logo.resize((max(1, int(w * ratio)), max(1, int(h * ratio))), Image.LANCZOS)
        tile.alpha_composite(fitted, ((big - fitted.width) // 2, (big - fitted.height) // 2))

        out = res / folder / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        tile.resize((size, size), Image.LANCZOS).save(out, "PNG", optimize=True)


def write_assets(work, cfg):
    assets = work / "assets"
    assets.mkdir()
    config = {
        "storeUrl": cfg["store_url"],
        "primaryColor": "#" + cfg["color"],
        "whatsappNumber": cfg["whatsapp"],
        "whatsappMessage": cfg["whatsapp_message"],
    }
    (assets / "tatbiqi.json").write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
    return assets


# ---------------------------------------------------------------- signing

def signing_key(work, throwaway):
    path = os.environ.get("KEYSTORE_PATH")
    if path:
        for var in ("KEYSTORE_PASSWORD", "KEY_ALIAS"):
            if not os.environ.get(var):
                raise BuildError(f"{var} is not set")
        return (path, os.environ["KEYSTORE_PASSWORD"], os.environ["KEY_ALIAS"],
                os.environ.get("KEY_PASSWORD") or os.environ["KEYSTORE_PASSWORD"])
    if not throwaway:
        raise BuildError("KEYSTORE_PATH is not set (pass --throwaway-key for a local test build)")
    ks = work / "throwaway.keystore"
    pw = "throwaway-" + os.urandom(6).hex()
    run(["keytool", "-genkeypair", "-keystore", ks, "-storepass", pw, "-keypass", pw,
         "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
         "-dname", "CN=Tatbiqi Test"], secret=pw)
    return str(ks), pw, "test", pw


# ---------------------------------------------------------------- bundle

def build_aab(work, bt, platform, cfg, args, aab_out, key):
    """Proto-format link -> base module zip -> bundletool -> jarsigner."""
    jar = os.environ.get("BUNDLETOOL_JAR")
    if not jar or not Path(jar).exists():
        raise BuildError("BUNDLETOOL_JAR is not set or missing")

    print("\n🧩 aapt2 (proto)", flush=True)
    run([bt / "aapt2", "link", "--proto-format"] + link_args(work, platform, cfg, args)
        + ["-o", work / "proto.apk", work / "res.zip"])

    # bundletool wants the manifest under manifest/ and the dex under dex/.
    base = work / "base.zip"
    with zipfile.ZipFile(work / "proto.apk") as src, \
            zipfile.ZipFile(base, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            name = "manifest/AndroidManifest.xml" if item.filename == "AndroidManifest.xml" else item.filename
            dst.writestr(name, src.read(item))
        dst.write(work / "dex" / "classes.dex", "dex/classes.dex")

    print("\n📚 bundletool", flush=True)
    aab_out.unlink(missing_ok=True)
    run(["java", "-jar", jar, "build-bundle", f"--modules={base}", f"--output={aab_out}"])
    run(["java", "-jar", jar, "validate", f"--bundle={aab_out}"])

    print("\n🔑 jarsigner", flush=True)
    ks, _, alias, _ = key
    run(["jarsigner", "-keystore", ks,
         "-storepass:env", "TATBIQI_KS_PASS", "-keypass:env", "TATBIQI_KEY_PASS",
         "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", aab_out, alias])
    run(["jarsigner", "-verify", aab_out])

    # Same path Google Play takes: the bundle must turn into installable APKs.
    manifest = run(["java", "-jar", jar, "dump", "manifest", f"--bundle={aab_out}"]).stdout
    if f'android:targetSdkVersion="{TARGET_SDK}"' not in manifest or cfg["package"] not in manifest:
        raise BuildError("bundle manifest is missing the package name or target SDK")
    ks, ks_pw, alias, key_pw = key
    run(["java", "-jar", jar, "build-apks", "--mode=universal",
         f"--bundle={aab_out}", f"--output={work / 'check.apks'}",
         f"--ks={ks}", f"--ks-key-alias={alias}",
         "--ks-pass=env:TATBIQI_KS_PASS", "--key-pass=env:TATBIQI_KEY_PASS"])


# ---------------------------------------------------------------- build

def link_args(work, platform, cfg, args):
    return ["--manifest", work / "AndroidManifest.xml",
            "-I", platform,
            "--rename-manifest-package", cfg["package"],
            "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
            "--version-code", str(args.version_code),
            "--version-name", args.version_name,
            "-A", work / "assets"]


def build(args):
    cfg = validate(args)
    bt, platform = sdk_paths()
    out = Path(args.output).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)

    work = Path(tempfile.mkdtemp(prefix="tatbiqi-"))
    try:
        res = work / "res"
        res.mkdir()
        (work / "classes").mkdir()
        (work / "dex").mkdir()

        write_manifest(work, cfg)
        write_assets(work, cfg)

        icon_source = "default"
        if cfg["logo_url"]:
            try:
                write_logo_icons(res, download_logo(cfg["logo_url"]), cfg["color"])
                icon_source = "logo"
            except Exception as e:  # a bad logo should not fail the build
                print(f"⚠️  logo skipped: {e}")
        if icon_source == "default":
            write_default_icon(res, cfg["color"])

        print("\n🔨 javac", flush=True)
        run(["javac", "--release", "8", "-nowarn", "-encoding", "UTF-8",
             "-classpath", platform,
             "-d", work / "classes", TEMPLATE_JAVA])
        classes = [str(p) for p in (work / "classes").rglob("*.class")]

        print("\n📦 d8", flush=True)
        run([bt / "d8", "--release", "--min-api", MIN_SDK, "--lib", platform,
             "--output", work / "dex"] + classes)

        print("\n🎨 aapt2", flush=True)
        run([bt / "aapt2", "compile", "--dir", res, "-o", work / "res.zip"])
        run([bt / "aapt2", "link"] + link_args(work, platform, cfg, args)
            + ["-o", work / "unsigned.apk", work / "res.zip"])

        run(["zip", "-j", "-q", work / "unsigned.apk", work / "dex" / "classes.dex"])
        run([bt / "zipalign", "-f", "-p", "4", work / "unsigned.apk", work / "aligned.apk"])

        print("\n🔑 apksigner", flush=True)
        key = signing_key(work, args.throwaway_key)
        ks, ks_pw, alias, key_pw = key
        env_ks = {"TATBIQI_KS_PASS": ks_pw, "TATBIQI_KEY_PASS": key_pw}
        os.environ.update(env_ks)
        run([bt / "apksigner", "sign", "--ks", ks, "--ks-key-alias", alias,
             "--ks-pass", "env:TATBIQI_KS_PASS", "--key-pass", "env:TATBIQI_KEY_PASS",
             "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true",
             "--out", out, work / "aligned.apk"])
        run([bt / "apksigner", "verify", out])

        aab = Path(args.aab_output).resolve() if args.aab_output else None
        if aab:
            aab.parent.mkdir(parents=True, exist_ok=True)
            build_aab(work, bt, platform, cfg, args, aab, key)

        result = {
            "apk": str(out),
            "size": out.stat().st_size,
            "package": cfg["package"],
            "versionCode": args.version_code,
            "versionName": args.version_name,
            "icon": icon_source,
        }
        if aab:
            result.update({"aab": str(aab), "aabSize": aab.stat().st_size})
        print(f"\n🎉 {json.dumps(result, ensure_ascii=False)}")
        if args.result_json:
            Path(args.result_json).write_text(json.dumps(result), encoding="utf-8")
        return result
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    p = argparse.ArgumentParser(description="Build a Tatbiqi store APK")
    p.add_argument("--store-url", required=True)
    p.add_argument("--app-name", required=True)
    p.add_argument("--package-name", required=True)
    p.add_argument("--primary-color", default="0F766E")
    p.add_argument("--whatsapp", default="")
    p.add_argument("--whatsapp-message", default="")
    p.add_argument("--logo-url", default="")
    p.add_argument("--version-code", type=int, default=1)
    p.add_argument("--version-name", default="1.0.0")
    p.add_argument("--output", required=True)
    p.add_argument("--aab-output", default="", help="also build a signed .aab for Google Play")
    p.add_argument("--result-json", default="")
    p.add_argument("--throwaway-key", action="store_true",
                   help="sign with a temporary key when KEYSTORE_PATH is unset (local tests only)")
    args = p.parse_args()
    try:
        build(args)
    except BuildError as e:
        print(f"❌ {e}", file=sys.stderr)
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error::{e}")  # shows on the run page
        sys.exit(1)


if __name__ == "__main__":
    main()
