#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main"
RES = SRC / "res"
NS = "{http://schemas.android.com/apk/res/android}"

errors: list[str] = []
warnings: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def parse(path: Path):
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"XML hatası: {path.relative_to(ROOT)}: {exc}")
        return None


def resource_names(path: Path, tag: str) -> set[str]:
    root = parse(path)
    if root is None:
        return set()
    return {element.get("name") for element in root.findall(tag) if element.get("name")}


strings: dict[str, list[Path]] = defaultdict(list)
colors: dict[str, list[Path]] = defaultdict(list)
styles: dict[str, list[Path]] = defaultdict(list)
bools: dict[str, list[Path]] = defaultdict(list)
layouts: set[str] = set()
drawables: set[str] = set()
ids: set[str] = set()

for xml_path in sorted(RES.rglob("*.xml")):
    root = parse(xml_path)
    if root is None:
        continue

    relative = xml_path.relative_to(ROOT)
    if xml_path.parent.name.startswith("layout"):
        layouts.add(xml_path.stem)
    if xml_path.parent.name.startswith("drawable"):
        drawables.add(xml_path.stem)

    if root.tag != "resources":
        continue

    for child in root:
        name = child.get("name")
        if not name:
            continue
        if child.tag == "string":
            strings[name].append(relative)
        elif child.tag == "color":
            colors[name].append(relative)
        elif child.tag == "style":
            styles[name].append(relative)
        elif child.tag == "bool":
            bools[name].append(relative)

for name, files in strings.items():
    if len(files) > 1:
        fail(f"Yinelenen string: {name} -> {', '.join(str(path) for path in files)}")

for name, files in styles.items():
    if len(files) > 1:
        fail(f"Yinelenen style: {name} -> {', '.join(str(path) for path in files)}")

reference_pattern = re.compile(
    r"@(?:\+)?(string|color|style|bool|layout|drawable|id)/([A-Za-z0-9_.]+)"
)

reference_files = sorted(RES.rglob("*.xml")) + [SRC / "AndroidManifest.xml"]
for xml_path in reference_files:
    root = parse(xml_path)
    if root is None:
        continue
    for element in root.iter():
        for value in element.attrib.values():
            for kind, name in reference_pattern.findall(value):
                if kind == "id":
                    ids.add(name)
                elif kind == "string":
                    if name not in strings:
                        fail(f"Eksik string: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "color":
                    if name not in colors:
                        fail(f"Eksik color: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "style":
                    if name not in styles:
                        fail(f"Eksik style: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "bool":
                    if name not in bools:
                        fail(f"Eksik bool: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "layout":
                    if name not in layouts:
                        fail(f"Eksik layout: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "drawable":
                    if name not in drawables:
                        fail(f"Eksik drawable: {name} ({xml_path.relative_to(ROOT)})")

kotlin_files = sorted((SRC / "java").rglob("*.kt"))
for kotlin_path in kotlin_files:
    text = kotlin_path.read_text(encoding="utf-8")
    pools = {
        "id": ids,
        "string": set(strings),
        "color": set(colors),
        "drawable": drawables,
        "layout": layouts,
        "bool": set(bools),
    }
    for kind, pool in pools.items():
        for match in re.finditer(rf"R\.{kind}\.([A-Za-z0-9_]+)", text):
            name = match.group(1)
            if name not in pool:
                fail(f"Eksik R.{kind}.{name}: {kotlin_path.relative_to(ROOT)}")

manifest = parse(SRC / "AndroidManifest.xml")
if manifest is not None:
    permissions = {
        element.get(NS + "name")
        for element in manifest.findall("uses-permission")
    }
    if "android.permission.CAMERA" not in permissions:
        fail("Manifest'te CAMERA izni yok.")
    if "android.permission.INTERNET" in permissions:
        fail("Manifest'te INTERNET izni olmamalı (çevrimdışı uygulama).")

    features = {
        element.get(NS + "name"): element.get(NS + "required")
        for element in manifest.findall("uses-feature")
    }
    if features.get("android.hardware.camera") != "true":
        fail("android.hardware.camera uses-feature required=true olmalı.")
    if features.get("android.hardware.camera.ar") != "false":
        fail("android.hardware.camera.ar uses-feature required=false olmalı.")

    application = manifest.find("application")
    if application is None:
        fail("Manifest'te application yok.")
    else:
        metadata = {
            element.get(NS + "name"): element.get(NS + "value")
            for element in application.findall("meta-data")
        }
        if metadata.get("com.google.ar.core") != "optional":
            fail("com.google.ar.core meta-data değeri optional olmalı.")

        if application.get(NS + "allowBackup") != "false":
            warn("allowBackup=false önerilir (ölçüm verileri cihazda kalsın).")

        activities = {
            element.get(NS + "name")
            for element in application.findall("activity")
        }
        if ".MainActivity" not in activities:
            fail("Manifest'te .MainActivity kaydı yok.")
        if ".MeasureActivity" not in activities:
            fail("Manifest'te .MeasureActivity kaydı yok.")
        required_sources = [
            "MainActivity.kt",
            "MeasureActivity.kt",
            "ar/ArCoreRenderer.kt",
            "ar/CameraBackgroundRenderer.kt",
            "ar/MeasureState.kt",
            "ui/OverlayView.kt",
        ]
        for source in required_sources:
            if not (SRC / "java/com/wendigo/arolcum" / source).exists():
                fail(f"{source} bulunamadı.")

base_bools = resource_names(RES / "values/bools.xml", "bool")
night_bools = resource_names(RES / "values-night/bools.xml", "bool")
if base_bools != night_bools:
    fail("values ve values-night bool adları eşleşmiyor.")

base_colors = resource_names(RES / "values/colors.xml", "color")
night_colors = resource_names(RES / "values-night/colors.xml", "color")
missing_night_colors = night_colors - base_colors
if missing_night_colors:
    fail(
        "values-night renkleri values içinde yok: "
        + ", ".join(sorted(missing_night_colors))
    )

gradle_path = ROOT / "app/build.gradle.kts"
if not gradle_path.exists():
    fail("app/build.gradle.kts bulunamadı.")
else:
    gradle = gradle_path.read_text(encoding="utf-8")
    required_gradle = [
        ("versionCode = 2", "versionCode 2"),
        ('versionName = "0.5"', 'versionName "0.5"'),
        ("compileSdk = 34", "compileSdk 34"),
        ("minSdk = 24", "minSdk 24"),
        ("targetSdk = 34", "targetSdk 34"),
        ("com.google.ar:core:1.45.0", "ARCore 1.45.0"),
        ("junit:junit:4.13.2", "JUnit 4.13.2"),
    ]
    for needle, label in required_gradle:
        if needle not in gradle:
            fail(f"Gradle ayarı eksik: {label}")

print("AR Ölçüm statik doğrulama")
print(
    "  "
    f"string={len(strings)} color={len(colors)} style={len(styles)} "
    f"bool={len(bools)} layout={len(layouts)} drawable={len(drawables)} "
    f"id={len(ids)}"
)

for message in warnings:
    print(f"UYARI: {message}")
for message in errors:
    print(f"HATA: {message}")

if errors:
    print(f"\n{len(errors)} hata bulundu.")
    sys.exit(1)

print("\nTamam: hata yok.")
