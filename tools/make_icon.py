# -*- coding: utf-8 -*-
"""生成 GIF Studio 应用图标：渐变底 + 白色 "GIF Studio" 文字，输出各密度 PNG。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

RES = Path(r"C:\Users\demon\Desktop\gif\app\src\main\res")
SIZE = 1024
FONT_BOLD = r"C:\Windows\Fonts\arialbd.ttf"

# 三种渐变色标：橙 -> 玫红 -> 紫
STOPS = [(0.0, (0xFF, 0x7A, 0x18)), (0.5, (0xFF, 0x3D, 0x77)), (1.0, (0x7B, 0x2F, 0xF7))]


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient_image(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t0 <= t <= t1:
                    k = (t - t0) / (t1 - t0) if t1 > t0 else 0
                    px[x, y] = lerp(c0, c1, k)
                    break
            else:
                px[x, y] = STOPS[-1][1]
    return img


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def build():
    grad = gradient_image(SIZE)
    mask = rounded_mask(SIZE, int(SIZE * 0.225))
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    icon.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(icon)
    # 顶部细高光，让渐变更有质感
    hi = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(hi).rounded_rectangle(
        [0, 0, SIZE - 1, int(SIZE * 0.5)], radius=int(SIZE * 0.225),
        fill=(255, 255, 255, 26)
    )
    icon.alpha_composite(hi)

    font_big = ImageFont.truetype(FONT_BOLD, 400)
    font_small = ImageFont.truetype(FONT_BOLD, 205)

    text1 = "GIF"
    text2 = "Studio"

    def center(text, font, y):
        box = d.textbbox((0, 0), text, font=font)
        w = box[2] - box[0]
        h = box[3] - box[1]
        d.text(((SIZE - w) / 2 - box[0], y - box[1]), text, font=font, fill=(255, 255, 255, 255))
        return h

    h1 = center(text1, font_big, int(SIZE * 0.19))
    center(text2, font_small, int(SIZE * 0.63))
    return icon


def build_foreground(size):
    """透明前景：白色 GIF / Studio 文字，占画布约 60%，供自适应图标使用。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f1 = ImageFont.truetype(FONT_BOLD, int(size * 0.34))
    f2 = ImageFont.truetype(FONT_BOLD, int(size * 0.18))
    for text, font, y in (("GIF", f1, int(size * 0.27)), ("Studio", f2, int(size * 0.60))):
        box = d.textbbox((0, 0), text, font=font)
        w = box[2] - box[0]
        d.text(((size - w) / 2 - box[0], y - box[1]), text, font=font, fill=(255, 255, 255, 255))
    return img


def main():
    icon = build()
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in densities.items():
        out_dir = RES / f"mipmap-{name}"
        out_dir.mkdir(parents=True, exist_ok=True)
        resized = icon.resize((px, px), Image.LANCZOS)
        for fname in ("ic_launcher.png", "ic_launcher_round.png"):
            resized.save(out_dir / fname, "PNG")
        print("wrote", out_dir, px)

    # 自适应图标前景（108dp 画布）
    for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        out_dir = RES / f"drawable-{name}"
        out_dir.mkdir(parents=True, exist_ok=True)
        build_foreground(px).save(out_dir / "ic_launcher_foreground.png", "PNG")
        print("wrote fg", out_dir, px)
    # 预览图（1024）
    preview_dir = RES.parents[3] / "docs" / "screens"
    preview_dir.mkdir(parents=True, exist_ok=True)
    icon.save(preview_dir / "app_icon_1024.png", "PNG")
    print("preview saved")


if __name__ == "__main__":
    main()
