"""Собирает векторные ресурсы Android из логотипа колледжа.

Зачем: Android не умеет читать SVG, ему нужен свой векторный формат. Скрипт
избавляет от ручного переписывания контуров и от подбора масштаба на глаз.
Звать, если логотип колледжа обновится или понадобится переделать иконку.

Делает два файла:
  drawable/logo_ngok.xml              — логотип как есть, для экранов приложения
  drawable/ic_launcher_foreground.xml — иконка: свой знак приложения, а под ним
                                        логотип колледжа подписью

    python tools/logo_to_vector.py
"""

from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets" / "logo-ngok.svg"
DRAWABLE = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"

BRAND = "#D60403"

# Рисунок держится в центральных 72dp квадрата 108x108: края адаптивной иконки
# на разных оболочках обрезаются по-разному.
ICON_SIZE = 108.0

# Знак приложения — циферблат со стрелками на девяти часах, времени первой
# пары. Логотип колледжа стоит под ним подписью и потому мельче: приложение
# опознают по своему знаку, а чужой логотип здесь говорит, чьё это расписание.
DIAL_CX, DIAL_CY, DIAL_R = 54.0, 43.0, 21.0
LOGO_WIDTH = 46.0
LOGO_TOP = 71.0


def circle(cx: float, cy: float, r: float) -> str:
    """Круг дугами: отдельного элемента для круга в ресурсах Android нет."""
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z"


def path(data: str, even_odd: bool = False) -> str:
    fill_type = '\n        android:fillType="evenOdd"' if even_odd else ""
    return (f'    <path\n        android:fillColor="{BRAND}"{fill_type}\n'
            f'        android:pathData="{data}" />')


def dial() -> str:
    """Циферблат: красное кольцо и две стрелки, показывающие девять часов."""
    ring = f"{circle(DIAL_CX, DIAL_CY, DIAL_R)} {circle(DIAL_CX, DIAL_CY, DIAL_R - 4.5)}"
    hour_w, minute_w = 3.6, 3.2
    # Часовая — вверх, минутная — влево: так читается «девять».
    hour = (f"M{DIAL_CX - hour_w / 2},{DIAL_CY - DIAL_R + 7.5} "
            f"h{hour_w} v{DIAL_R - 5.5} h{-hour_w} Z")
    minute = (f"M{DIAL_CX - DIAL_R + 7},{DIAL_CY - minute_w / 2} "
              f"h{DIAL_R - 4.5} v{minute_w} h{-(DIAL_R - 4.5)} Z")
    return "\n".join([path(ring, even_odd=True), path(hour), path(minute)])


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
    # в десять раз — эта группа возвращает контуры на место.
    flip = (f'android:translateY="{height}"\n'
            f'            android:scaleX="0.1"\n'
            f'            android:scaleY="-0.1"')

    (DRAWABLE / "logo_ngok.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
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
""",
        encoding="utf-8",
    )

    scale = LOGO_WIDTH / width
    (DRAWABLE / "ic_launcher_foreground.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  Иконка приложения: свой знак — циферблат на девяти часах — и логотип
  колледжа подписью под ним. Собрано скриптом tools/logo_to_vector.py.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{dial()}
    <group
        android:translateX="{(ICON_SIZE - LOGO_WIDTH) / 2:.2f}"
        android:translateY="{LOGO_TOP:.2f}"
        android:scaleX="{scale:.5f}"
        android:scaleY="{scale:.5f}">
        <group
            {flip}>
{contours(paths, "            ")}
        </group>
    </group>
</vector>
""",
        encoding="utf-8",
    )
    print(f"собрано из {SOURCE.name}: {len(paths)} контуров, "
          f"логотип в иконке {LOGO_WIDTH:.0f}x{height * scale:.1f}dp")


if __name__ == "__main__":
    main()
