"""Рисует растровые иконки приложения из того же логотипа, что и векторная.

Зачем: адаптивной иконки (mipmap-anydpi-v26) хватает лаунчеру, но не всему.
Системная плашка «скопировано», некоторые диалоги и оболочки просят обычный
растр — и, не найдя его, показывают стандартного андроида вместо нашей иконки.

Звать после tools/logo_to_vector.py или когда меняется рисунок иконки.

    python tools/make_launcher_icons.py
"""

from __future__ import annotations

import pathlib
import re

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets" / "logo-ngok.svg"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

BRAND = (214, 4, 3)
WHITE = (255, 255, 255)

# Плотности экрана и размер иконки для каждой.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Те же величины, что в tools/logo_to_vector.py, в долях от стороны 108.
DIAL_CX, DIAL_CY, DIAL_R = 54.0, 43.0, 21.0
LOGO_WIDTH, LOGO_TOP = 46.0, 71.0
# Рисуем крупнее вектора: у растровой иконки нет запаса на обрезку краёв.
CANVAS = 108.0
ZOOM = 1.25


def bezier(p0, p1, p2, p3, steps: int = 12):
    """Точки кубической кривой — Pillow умеет только ломаные."""
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        yield (
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        )


def parse_path(data: str) -> list[list[tuple[float, float]]]:
    """Разбирает контуры логотипа: в нём только M, c, l, v, h и z."""
    tokens = re.findall(r"[MmCcLlHhVvZz]|-?\d*\.?\d+", data)
    contours: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    x = y = 0.0
    command = ""
    i = 0

    def number() -> float:
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        token = tokens[i]
        if re.match(r"[A-Za-z]", token):
            command = token
            i += 1
            if command in "Zz":
                if current:
                    contours.append(current)
                    current = []
                continue

        if command in "Mm":
            dx, dy = number(), number()
            x, y = (x + dx, y + dy) if command == "m" else (dx, dy)
            if current:
                contours.append(current)
            current = [(x, y)]
            command = "l" if command == "m" else "L"
        elif command in "Cc":
            coords = [number() for _ in range(6)]
            if command == "c":
                p1 = (x + coords[0], y + coords[1])
                p2 = (x + coords[2], y + coords[3])
                p3 = (x + coords[4], y + coords[5])
            else:
                p1, p2, p3 = (coords[0], coords[1]), (coords[2], coords[3]), (coords[4], coords[5])
            current.extend(bezier((x, y), p1, p2, p3))
            x, y = p3
        elif command in "Ll":
            dx, dy = number(), number()
            x, y = (x + dx, y + dy) if command == "l" else (dx, dy)
            current.append((x, y))
        elif command in "Hh":
            dx = number()
            x = x + dx if command == "h" else dx
            current.append((x, y))
        elif command in "Vv":
            dy = number()
            y = y + dy if command == "v" else dy
            current.append((x, y))
        else:
            i += 1

    if current:
        contours.append(current)
    return contours


def logo_contours() -> tuple[list[list[tuple[float, float]]], float, float]:
    svg = SOURCE.read_text(encoding="utf-8")
    box = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg)
    width, height = float(box.group(1)), float(box.group(2))
    out: list[list[tuple[float, float]]] = []
    for data in re.findall(r'<path d="([^"]+)"', svg):
        for contour in parse_path(data):
            # Исходник перевёрнут по вертикали и увеличен вдесятеро.
            out.append([(px * 0.1, height - py * 0.1) for px, py in contour])
    return out, width, height


def draw_icon(size: int, contours, logo_w: float, logo_h: float, round_icon: bool) -> Image.Image:
    scale = size / CANVAS * ZOOM
    shift = (size - CANVAS * scale) / 2

    def point(x: float, y: float) -> tuple[float, float]:
        return (x * scale + shift, y * scale + shift)

    image = Image.new("RGBA", (size, size), WHITE + (255,))
    draw = ImageDraw.Draw(image)

    if round_icon:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        mask = None

    # Циферблат: кольцо и две стрелки на девяти часах.
    outer = [point(DIAL_CX - DIAL_R, DIAL_CY - DIAL_R), point(DIAL_CX + DIAL_R, DIAL_CY + DIAL_R)]
    draw.ellipse([outer[0], outer[1]], fill=BRAND)
    inner_r = DIAL_R - 4.5
    draw.ellipse(
        [point(DIAL_CX - inner_r, DIAL_CY - inner_r), point(DIAL_CX + inner_r, DIAL_CY + inner_r)],
        fill=WHITE,
    )
    draw.rectangle(
        [point(DIAL_CX - 1.8, DIAL_CY - DIAL_R + 7.5), point(DIAL_CX + 1.8, DIAL_CY + 0.5)],
        fill=BRAND,
    )
    draw.rectangle(
        [point(DIAL_CX - DIAL_R + 7, DIAL_CY - 1.6), point(DIAL_CX + 0.5, DIAL_CY + 1.6)],
        fill=BRAND,
    )

    # Логотип колледжа под циферблатом.
    logo_scale = LOGO_WIDTH / logo_w
    left = (CANVAS - LOGO_WIDTH) / 2
    for contour in contours:
        if len(contour) < 3:
            continue
        draw.polygon(
            [point(left + px * logo_scale, LOGO_TOP + py * logo_scale) for px, py in contour],
            fill=BRAND,
        )

    if mask is not None:
        background = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        background.paste(image, (0, 0), mask)
        image = background
    return image


def main() -> None:
    contours, logo_w, logo_h = logo_contours()
    for density, size in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        draw_icon(size, contours, logo_w, logo_h, round_icon=False).save(
            folder / "ic_launcher.png"
        )
        draw_icon(size, contours, logo_w, logo_h, round_icon=True).save(
            folder / "ic_launcher_round.png"
        )
        print(f"mipmap-{density}: {size}x{size}")


if __name__ == "__main__":
    main()
