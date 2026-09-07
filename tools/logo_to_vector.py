"""Переводит логотип из assets/logo-ngok.svg в ресурсы Android.

Зачем: Android не умеет читать SVG, ему нужен свой векторный формат. Скрипт
избавляет от ручного переписывания контуров и от подбора масштаба на глаз.
Звать, если логотип колледжа обновится или понадобится другой размер.

Делает два файла:
  drawable/logo_ngok.xml            — логотип как есть, для экранов приложения
  drawable/ic_launcher_foreground.xml — он же, вписанный в иконку

    python tools/logo_to_vector.py
"""

from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets" / "logo-ngok.svg"
DRAWABLE = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"

BRAND = "#D60403"
# Логотип занимает центральные 72dp квадрата 108x108: края адаптивной иконки
# на разных оболочках обрезаются по-разному.
ICON_SIZE = 108.0
SAFE_ZONE = 72.0


def read_source() -> tuple[list[str], float, float]:
    svg = SOURCE.read_text(encoding="utf-8")
    paths = [" ".join(d.split()) for d in re.findall(r'<path d="([^"]+)"', svg)]
    if not paths:
        raise SystemExit(f"в {SOURCE} нет ни одного контура")
    box = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg)
    if not box:
        raise SystemExit(f"в {SOURCE} не нашёл viewBox")
    return paths, float(box.group(1)), float(box.group(2))


def contours(paths: list[str], indent: str) -> str:
    return "\n".join(
        f'{indent}<path\n{indent}    android:fillColor="{BRAND}"\n'
        f'{indent}    android:pathData="{d}" />'
        for d in paths
    )


def main() -> None:
    paths, width, height = read_source()

    # У исходника система координат перевёрнута по вертикали и увеличена
    # в десять раз — внутренняя группа возвращает контуры на место.
    flip = f'android:translateY="{height}"\n            android:scaleX="0.1"\n            android:scaleY="-0.1"'

    (DRAWABLE / "logo_ngok.xml").write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Собран из assets/logo-ngok.svg скриптом tools/logo_to_vector.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{int(width)}dp"
    android:height="{int(height)}dp"
    android:viewportWidth="{width}"
    android:viewportHeight="{height}">
    <group
        {flip}>
{contours(paths, "        ")}
    </group>
</vector>
''',
        encoding="utf-8",
    )

    scale = SAFE_ZONE / width
    top = (ICON_SIZE - height * scale) / 2
    (DRAWABLE / "ic_launcher_foreground.xml").write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Собран из assets/logo-ngok.svg скриптом tools/logo_to_vector.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="{(ICON_SIZE - SAFE_ZONE) / 2:.2f}"
        android:translateY="{top:.2f}"
        android:scaleX="{scale:.5f}"
        android:scaleY="{scale:.5f}">
        <group
            {flip}>
{contours(paths, "            ")}
        </group>
    </group>
</vector>
''',
        encoding="utf-8",
    )
    print(f"собрано из {SOURCE.name}: {len(paths)} контуров, масштаб иконки {scale:.4f}")


if __name__ == "__main__":
    main()
