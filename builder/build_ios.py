#!/usr/bin/env python3
"""
Tatbiqi iOS project generator.

Writes a ready-to-build Xcode project (via XcodeGen) for one store: the shared
Swift sources in builder/ios/Sources, plus per-store tatbiqi.json, app icon,
launch color and Info.plist values. The workflow then runs xcodegen and
xcodebuild on a macOS runner (see .github/workflows/build-ios.yml).

Uses the same inputs and validation as build_apk.py. Store values only go into
tatbiqi.json and plist strings, never into Swift code.

Usage:
  python3 builder/build_ios.py --store-url https://mystore.salla.sa \\
      --app-name "متجري" --package-name sa.tatbiqi.mystore --primary-color 0F766E \\
      --version-code 1 --version-name 1.0.0 --out out/ios
"""
import argparse
import io
import json
import shutil
import sys
from pathlib import Path

from build_apk import BuildError, download_logo, validate

HERE = Path(__file__).resolve().parent
SOURCES = HERE / "ios" / "Sources"
DEPLOYMENT_TARGET = "15.0"


def bundle_id(package_name):
    # iOS bundle ids allow letters, digits, "-" and "." but not "_".
    return package_name.replace("_", "-")


def hex_to_rgb(color):
    return tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))


def write_icon(assets, logo_bytes, color):
    """1024x1024 opaque square (App Store rejects transparency); iOS rounds the corners."""
    from PIL import Image

    size = 1024
    icon = Image.new("RGB", (size, size), hex_to_rgb(color))
    if logo_bytes:
        logo = Image.open(io.BytesIO(logo_bytes))
        logo.load()
        logo = logo.convert("RGBA")
        bbox = logo.getbbox()
        if bbox:
            logo = logo.crop(bbox)
        inner = int(size * 0.68)
        ratio = min(inner / logo.width, inner / logo.height)
        logo = logo.resize((max(1, int(logo.width * ratio)), max(1, int(logo.height * ratio))), Image.LANCZOS)
        icon.paste(logo, ((size - logo.width) // 2, (size - logo.height) // 2), logo)
    else:
        # Same shopping bag as the Android default icon, drawn in white.
        from PIL import ImageDraw
        d = ImageDraw.Draw(icon)
        s = size / 48
        d.polygon([(16 * s, 19 * s), (32 * s, 19 * s), (33 * s, 37 * s), (15 * s, 37 * s)], fill="white")
        d.arc((20 * s, 13 * s, 28 * s, 23 * s), 180, 360, fill="white", width=int(2 * s))

    folder = assets / "AppIcon.appiconset"
    folder.mkdir(parents=True)
    icon.save(folder / "icon-1024.png", "PNG")
    (folder / "Contents.json").write_text(json.dumps({
        "images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": {"author": "xcode", "version": 1},
    }), encoding="utf-8")


def write_launch_color(assets, color):
    r, g, b = hex_to_rgb(color)
    folder = assets / "LaunchBackground.colorset"
    folder.mkdir(parents=True)
    (folder / "Contents.json").write_text(json.dumps({
        "colors": [{"idiom": "universal", "color": {"color-space": "srgb", "components": {
            "red": f"{r / 255:.3f}", "green": f"{g / 255:.3f}", "blue": f"{b / 255:.3f}", "alpha": "1.000"}}}],
        "info": {"author": "xcode", "version": 1},
    }), encoding="utf-8")


def write_project_yml(out, cfg, args, team_id):
    # JSON is valid YAML, so we never hand-escape store names.
    settings = {
        "PRODUCT_BUNDLE_IDENTIFIER": bundle_id(cfg["package"]),
        "PRODUCT_NAME": "App",
        "MARKETING_VERSION": args.version_name,
        "CURRENT_PROJECT_VERSION": str(args.version_code),
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
        "TARGETED_DEVICE_FAMILY": "1",
        "SWIFT_VERSION": "5.0",
        "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
        "CODE_SIGN_STYLE": "Automatic",
        "ENABLE_BITCODE": "NO",
    }
    if team_id:
        settings["DEVELOPMENT_TEAM"] = team_id
    project = {
        "name": "Tatbiqi",
        "options": {"deploymentTarget": {"iOS": DEPLOYMENT_TARGET}, "developmentLanguage": "ar"},
        "targets": {
            "App": {
                "type": "application",
                "platform": "iOS",
                "sources": ["Sources", "Resources"],
                "settings": {"base": settings},
                "info": {
                    "path": "Info.plist",
                    "properties": {
                        "CFBundleDisplayName": cfg["app_name"],
                        "CFBundleName": cfg["app_name"][:15],
                        "CFBundleDevelopmentRegion": "ar",
                        "CFBundleShortVersionString": "$(MARKETING_VERSION)",
                        "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)",
                        "LSRequiresIPhoneOS": True,
                        "ITSAppUsesNonExemptEncryption": False,
                        "UILaunchScreen": {"UIColorName": "LaunchBackground"},
                        "UIViewControllerBasedStatusBarAppearance": True,
                        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
                        "UIApplicationSceneManifest": {
                            "UIApplicationSupportsMultipleScenes": False,
                            "UISceneConfigurations": {
                                "UIWindowSceneSessionRoleApplication": [{
                                    "UISceneConfigurationName": "Default",
                                    "UISceneDelegateClassName": "$(PRODUCT_MODULE_NAME).SceneDelegate",
                                }],
                            },
                        },
                        "LSApplicationQueriesSchemes": ["whatsapp", "tel", "mailto"],
                    },
                },
            },
        },
    }
    (out / "project.yml").write_text(json.dumps(project, ensure_ascii=False, indent=2), encoding="utf-8")


def generate(args):
    cfg = validate(args)
    out = Path(args.out).resolve()
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SOURCES, out / "Sources")
    resources = out / "Resources"
    assets = resources / "Assets.xcassets"
    assets.mkdir(parents=True)
    (assets / "Contents.json").write_text('{"info":{"author":"xcode","version":1}}', encoding="utf-8")

    (resources / "tatbiqi.json").write_text(json.dumps({
        "storeUrl": cfg["store_url"],
        "primaryColor": "#" + cfg["color"],
        "whatsappNumber": cfg["whatsapp"],
        "whatsappMessage": cfg["whatsapp_message"],
    }, ensure_ascii=False), encoding="utf-8")

    logo = None
    icon_source = "default"
    if cfg["logo_url"]:
        try:
            logo = download_logo(cfg["logo_url"])
            icon_source = "logo"
        except Exception as e:  # a bad logo should not fail the build
            print(f"⚠️  logo skipped: {e}")
    try:
        write_icon(assets, logo, cfg["color"])
    except Exception as e:
        if logo is None:
            raise
        print(f"⚠️  logo unreadable, using default icon: {e}")
        shutil.rmtree(assets / "AppIcon.appiconset", ignore_errors=True)
        write_icon(assets, None, cfg["color"])
        icon_source = "default"
    write_launch_color(assets, cfg["color"])
    write_project_yml(out, cfg, args, args.team_id)

    result = {
        "bundleId": bundle_id(cfg["package"]),
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "icon": icon_source,
    }
    print(f"🎉 {json.dumps(result, ensure_ascii=False)}")
    if args.result_json:
        Path(args.result_json).write_text(json.dumps(result), encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description="Generate a Tatbiqi iOS project")
    p.add_argument("--store-url", required=True)
    p.add_argument("--app-name", required=True)
    p.add_argument("--package-name", required=True)
    p.add_argument("--primary-color", default="0F766E")
    p.add_argument("--whatsapp", default="")
    p.add_argument("--whatsapp-message", default="")
    p.add_argument("--logo-url", default="")
    p.add_argument("--version-code", type=int, default=1)
    p.add_argument("--version-name", default="1.0.0")
    p.add_argument("--team-id", default="", help="Apple Developer Team ID (for signed builds)")
    p.add_argument("--out", required=True)
    p.add_argument("--result-json", default="")
    args = p.parse_args()
    try:
        generate(args)
    except BuildError as e:
        print(f"❌ {e}", file=sys.stderr)
        print(f"::error::{e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
