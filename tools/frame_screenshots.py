"""Frames phone screenshots for the Google Play listing: the PM mark and a caption above the
screen, on the logo's charcoal, at 1080 × 1920 (9:16).

Usage: python tools/frame_screenshots.py SOURCE_DIR
SOURCE_DIR holds full-resolution (1080 × 2400) screenshots named as in SHOTS below. The framed
images are written to docs/play-store/screenshots/. Needs Pillow and Windows' Segoe UI fonts.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_logo import CHARCOAL, PROJECT, WHITE, render  # noqa: E402

OUT = os.path.join(PROJECT, "docs", "play-store", "screenshots")
FONT = os.path.join(r"C:\Windows\Fonts", "seguisb.ttf")

# (source file, caption), in listing order. Play shows up to eight.
SHOTS = [
    ("live", "See what you're using right now"),
    ("clock-kwh", "Your whole day on one clock"),
    ("clock-kw", "Spot every spike in power"),
    ("clock-cost", "See what every part of the day costs"),
    ("month-chart", "Track every day, week, month and year"),
    ("prices", "Know when power is cheap"),
    ("plans-top", "Find the cheapest plan for how you use power"),
    ("tariff", "Built for time-of-use plans"),
]

W, H = 1080, 1920
SCREEN_W = 880
MARGIN_TOP = 80
CAPTION_LINES = 2
LINE_HEIGHT = 76
STATUS_BAR = 90  # px at 1080 wide: below the status icons, above the app bar
BORDER = (0x4A, 0x45, 0x46)


def wrap(draw, text, font, width):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=font) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


def frame(source, caption):
    canvas = Image.new("RGB", (W, H), CHARCOAL)
    draw = ImageDraw.Draw(canvas)
    mark = render(150, 120, background=None)
    canvas.paste(mark, ((W - 150) // 2, MARGIN_TOP - 15), mark)

    # Captions get a two-line band, centred in it, so every screen starts at the same height.
    font = ImageFont.truetype(FONT, 62)
    lines = wrap(draw, caption, font, 940)
    band_top = MARGIN_TOP + 150
    y = band_top + (CAPTION_LINES - len(lines)) * LINE_HEIGHT // 2
    for line in lines:
        draw.text((W / 2, y), line, font=font, fill=WHITE, anchor="ma")
        y += LINE_HEIGHT

    # The status bar is left off (its clock would differ from shot to shot), and the screen runs
    # off the bottom edge, like a phone held up to the camera.
    screen = Image.open(source).convert("RGB")
    screen = screen.crop((0, STATUS_BAR, screen.width, screen.height))
    scale = SCREEN_W / screen.width
    screen = screen.resize((SCREEN_W, round(screen.height * scale)), Image.LANCZOS)
    top = band_top + CAPTION_LINES * LINE_HEIGHT + 40
    visible = screen.crop((0, 0, SCREEN_W, H - top))
    mask = Image.new("L", visible.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, SCREEN_W - 1, visible.height + 60], radius=48, fill=255)
    left = (W - SCREEN_W) // 2
    draw.rounded_rectangle([left - 4, top - 4, left + SCREEN_W + 3, H + 60], radius=52, fill=BORDER)
    canvas.paste(visible, (left, top), mask)
    return canvas


def main(source_dir):
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        if old.endswith(".png"):
            os.remove(os.path.join(OUT, old))
    for i, (name, caption) in enumerate(SHOTS, 1):
        out = os.path.join(OUT, f"{i:02d}-{name}.png")
        frame(os.path.join(source_dir, name + ".png"), caption).save(out, optimize=True)
        print("wrote", os.path.relpath(out, PROJECT))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
