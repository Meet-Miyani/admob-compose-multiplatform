#!/usr/bin/env python3
"""Generates the SVG artwork used by the repository README.

Every label is converted to outlines, because an SVG shown through <img> on
GitHub cannot load web fonts. Faces and colours come from the docs site
(docs-site/public/fonts and docs-site/src/styles/tokens.css).

Nothing here names a release number, so the artwork does not go stale when the
SDK version changes. File names avoid words such as "banner" and "ad-slot"
because content blockers hide images with those names.

    python3 -m venv .venv && .venv/bin/pip install fonttools brotli uharfbuzz
    .venv/bin/python .github/readme/build_assets.py
"""

from __future__ import annotations

import io
from functools import lru_cache
from pathlib import Path
from xml.sax.saxutils import escape

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
FONTS = ROOT / "docs-site/public/fonts"

ACCENT = "#ee3a20"
ACCENT_STRONG = "#c8371f"
STAGE = "#16181a"
STAGE_RAISED = "#1e2022"
STAGE_HAIR = "#2e3134"
STAGE_INK = "#edeeec"
STAGE_SLATE = "#a0a5a2"
STAGE_ACCENT = "#ff8b73"
SCREEN = "#fafbf9"
SCREEN_CARD = "#eceeeb"
SCREEN_INK = "#16181a"
SCREEN_SLATE = "#5a5f63"
SCREEN_HAIR = "#dde0dc"
BEZEL = "#0b0c0d"
BEZEL_EDGE = "#3a3c3f"
MARK_OK = "#3fb573"
AD_TINT = "#fbe1da"
AD_BLOCK = "#f4c4b8"

THEMES = {
    "light": dict(paper="#f3f4f2", ink="#16181a", ink2="#3a3e41", slate="#5a5f63",
                  hair="#d8dad6", accent="#b32a12", raised="#fafbf9"),
    "dark": dict(paper="#0e0f10", ink="#edeeec", ink2="#c4c8c5", slate="#9a9f9c",
                 hair="#2a2c2e", accent="#ff8b73", raised="#1e2022"),
}


class Face:
    def __init__(self, tt: TTFont):
        buffer = io.BytesIO()
        tt.flavor = None
        tt.save(buffer)
        self.tt = TTFont(io.BytesIO(buffer.getvalue()))
        self.glyphs = self.tt.getGlyphSet()
        self.upem = self.tt["head"].unitsPerEm
        self.hb = hb.Font(hb.Face(buffer.getvalue()))

    def shape(self, text: str):
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(self.hb, buf, {"kern": True, "liga": False})
        for info in buf.glyph_infos:
            if info.codepoint == 0:
                raise ValueError(f"missing glyph in {text!r}")
        return buf.glyph_infos, buf.glyph_positions


@lru_cache(maxsize=None)
def archivo(wght: int, wdth: int) -> Face:
    font = TTFont(FONTS / "archivo-wdth.woff2")
    return Face(instancer.instantiateVariableFont(font, {"wght": wght, "wdth": wdth}))


@lru_cache(maxsize=None)
def mono() -> Face:
    return Face(TTFont(FONTS / "jetbrains-mono-500.woff2"))


def width(text: str, face: Face, size: float, tracking: float = 0) -> float:
    _, positions = face.shape(text)
    scale = size / face.upem
    return sum(p.x_advance for p in positions) * scale + tracking * size * len(text)


def number(value: float) -> str:
    return f"{value:.1f}".rstrip("0").rstrip(".")


def label(text: str, x: float, y: float, size: float, face: Face, fill: str,
          anchor: str = "start", tracking: float = 0) -> str:
    """Returns the text as a single outlined <path>."""
    if anchor == "middle":
        x -= width(text, face, size, tracking) / 2
    elif anchor == "end":
        x -= width(text, face, size, tracking)
    infos, positions = face.shape(text)
    scale = size / face.upem
    pen = SVGPathPen(face.glyphs, ntos=number)
    cursor = x
    for info, position in zip(infos, positions):
        name = face.tt.getGlyphName(info.codepoint)
        transform = (scale, 0, 0, -scale,
                     cursor + position.x_offset * scale, y - position.y_offset * scale)
        face.glyphs[name].draw(TransformPen(pen, transform))
        cursor += position.x_advance * scale + tracking * size
    return f'<path fill="{fill}" d="{pen.getCommands()}"/>'


def wrap(text: str, face: Face, size: float, max_width: float) -> list[str]:
    lines, current = [], ""
    for word in text.split():
        trial = f"{current} {word}".strip()
        if width(trial, face, size) <= max_width:
            current = trial
        else:
            lines.append(current)
            current = word
    return lines + [current]


def rect(x, y, w, h, r=0, fill="none", stroke=None, stroke_width=1, extra="") -> str:
    border = f' stroke="{stroke}" stroke-width="{stroke_width}"' if stroke else ""
    return (f'<rect x="{number(x)}" y="{number(y)}" width="{number(w)}" height="{number(h)}" '
            f'rx="{number(r)}" fill="{fill}"{border}{extra}/>')


def svg(w: int, h: int, title: str, body: str, style: str = "") -> str:
    style_block = f"<style>{style}</style>" if style else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'role="img" aria-label="{escape(title)}"><title>{escape(title)}</title>{style_block}{body}</svg>\n')


def logo(x, y, size, frame, pill=ACCENT) -> str:
    return (f'<g transform="translate({x} {y}) scale({size / 64})">'
            f'<rect x="16.5" y="5.5" width="31" height="53" rx="11" fill="none" stroke="{frame}" stroke-width="9"/>'
            f'<rect x="23" y="39" width="18" height="10" rx="5" fill="{pill}"/></g>')


# ------------------------------------------------------------------ phones


def feed(x, y, w, h, title_size, large_title, rows=4) -> str:
    """The stand-in app under every format: a short journal list."""
    pad = w * 0.09
    out = [rect(x, y, w, h, fill=SCREEN)]
    regular = archivo(700, 100)
    top = y + h * 0.17
    if large_title:
        out.append(label("Journal", x + pad, top + title_size * 0.5, title_size * 1.4, regular, SCREEN_INK))
        top += title_size * 1.25
    else:
        out.append(label("Journal", x + pad, top, title_size, regular, SCREEN_INK))
        top += title_size * 0.7
    row_h = (y + h - top - pad) / rows
    names = ["Morning run", "Reading", "Sketching", "Harbour walk"]
    body = archivo(600, 100)
    for i in range(rows):
        ry = top + i * row_h
        out.append(rect(x + pad, ry, w - 2 * pad, row_h - h * 0.018, w * 0.05, SCREEN_CARD))
        out.append(label(names[i % 4], x + pad * 1.7, ry + row_h * 0.42, title_size * 0.58, body, SCREEN_INK))
        out.append(rect(x + pad * 1.7, ry + row_h * 0.56, w * 0.42, h * 0.012, h * 0.006, SCREEN_SLATE,
                        extra=' opacity=".55"'))
    return "".join(out)


def scene(fmt: str, x, y, w, h, dynamic: bool = False) -> str:
    """Draws one ad format over a screen. The ad is always the orange region.

    With dynamic=True the parts that change over time (progress, countdowns)
    are left out, so an animated card can draw its own moving versions.
    """
    pad = w * 0.08
    bars = lambda bx, by, bw, n=2, colour=SCREEN_INK: "".join(
        rect(bx, by + i * h * 0.04, bw * (1 if i == 0 else 0.68), h * 0.018, h * 0.009, colour) for i in range(n))
    mono_face = mono()

    if fmt == "banner":
        bh = h * 0.13
        by = y + h - bh - pad * 0.9
        return (rect(x + pad, by, w - 2 * pad, bh, w * 0.05, AD_TINT, ACCENT, 1.5)
                + rect(x + pad * 1.7, by + bh * 0.26, bh * 0.48, bh * 0.48, bh * 0.1, AD_BLOCK)
                + bars(x + pad * 1.7 + bh * 0.68, by + bh * 0.3, w * 0.42))

    if fmt == "interstitial":
        close = w * 0.075
        return (rect(x, y, w, h, fill=AD_TINT)
                + rect(x + pad * 1.4, y + h * 0.24, w - pad * 2.8, h * 0.5, w * 0.05, AD_BLOCK)
                + bars(x + pad * 1.4, y + h * 0.79, w - pad * 2.8)
                + f'<circle cx="{number(x + w - pad - close / 2)}" cy="{number(y + h * 0.15)}" r="{number(close)}" fill="{SCREEN_INK}"/>'
                + label("×", x + w - pad - close / 2, y + h * 0.15 + close * 0.62, close * 1.7, mono_face, SCREEN, "middle"))

    if fmt == "rewarded":
        pill_w, pill_h = w * 0.3, h * 0.07
        progress = "" if dynamic else (
            rect(x + pad, y + h * 0.125, w * 0.42, h * 0.012, h * 0.006, ACCENT)
            + label("0:15", x + w - pad - pill_w / 2, y + h * 0.095 + pill_h * 0.72, pill_h * 0.66, mono_face, SCREEN, "middle"))
        pill = rect(x + w - pad - pill_w, y + h * 0.095, pill_w, pill_h, pill_h / 2, SCREEN_INK)
        return (rect(x, y, w, h, fill=AD_TINT)
                + pill + progress
                + rect(x + pad * 1.4, y + h * 0.26, w - pad * 2.8, h * 0.5, w * 0.05, AD_BLOCK)
                + bars(x + pad * 1.4, y + h * 0.81, w - pad * 2.8))

    if fmt == "rewarded-interstitial":
        card_x, card_w = x + pad * 1.3, w - pad * 2.6
        card_y, card_h = y + h * 0.33, h * 0.36
        skip_w, skip_h = card_w * 0.56, card_h * 0.2
        return (rect(x, y, w, h, fill=SCREEN_INK, extra=' opacity=".42"')
                + rect(card_x, card_y, card_w, card_h, w * 0.06, AD_TINT, ACCENT, 1.5)
                + bars(card_x + card_w * 0.13, card_y + card_h * 0.17, card_w * 0.74, 2)
                + ("" if dynamic else label("in 3…", x + w / 2, card_y + card_h * 0.61, card_h * 0.15, mono_face, SCREEN_INK, "middle"))
                + rect(x + w / 2 - skip_w / 2, card_y + card_h * 0.7, skip_w, skip_h, skip_h / 2, AD_BLOCK))

    if fmt == "app-open":
        icon = w * 0.24
        return (rect(x, y, w, h, fill=AD_TINT)
                + rect(x + w / 2 - icon / 2, y + h * 0.17, icon, icon, icon * 0.26, SCREEN_INK)
                + logo(x + w / 2 - icon * 0.36, y + h * 0.17 + icon * 0.14, icon * 0.72, SCREEN, STAGE_ACCENT)
                + rect(x + pad * 1.4, y + h * 0.42, w - pad * 2.8, h * 0.33, w * 0.05, AD_BLOCK)
                + rect(x + pad * 1.4, y + h * 0.82, w - pad * 2.8, h * 0.075, h * 0.0375, SCREEN_INK))

    if fmt == "native":
        card_x, card_w = x + w * 0.09, w * 0.82
        card_y, card_h = y + h * 0.36, h * 0.44
        return (rect(card_x, card_y, card_w, card_h, w * 0.05, AD_TINT, ACCENT, 1.5)
                + rect(card_x + card_w * 0.08, card_y + card_h * 0.08, card_w * 0.84, card_h * 0.42, w * 0.03, AD_BLOCK)
                + bars(card_x + card_w * 0.08, card_y + card_h * 0.56, card_w * 0.84)
                + rect(card_x + card_w * 0.08, card_y + card_h * 0.79, card_w * 0.84, card_h * 0.13, card_h * 0.065, SCREEN_INK))

    raise ValueError(fmt)


def phone(uid, x, y, w, h, ios, scenes: str, title_size) -> str:
    inset = w * 0.045
    radius = w * (0.17 if ios else 0.13)
    sx, sy, sw, sh = x + inset, y + inset, w - 2 * inset, h - 2 * inset
    out = [rect(x, y, w, h, radius, BEZEL, BEZEL_EDGE, 1.5)]
    out.append(f'<clipPath id="{uid}">{rect(sx, sy, sw, sh, radius - inset)}</clipPath>')
    out.append(f'<g clip-path="url(#{uid})">{feed(sx, sy, sw, sh, title_size, ios)}{scenes}</g>')
    if ios:
        island_w, island_h = w * 0.3, h * 0.03
        out.append(rect(x + w / 2 - island_w / 2, sy + h * 0.016, island_w, island_h, island_h / 2, BEZEL))
    else:
        out.append(f'<circle cx="{number(x + w / 2)}" cy="{number(sy + h * 0.03)}" r="{number(h * 0.011)}" fill="{BEZEL}"/>')
    return "".join(out)


# -------------------------------------------------------------------- hero

FORMATS = [
    ("banner", "AdFormat.Banner"),
    ("interstitial", "AdFormat.Interstitial"),
    ("rewarded", "AdFormat.Rewarded"),
    ("rewarded-interstitial", "AdFormat.RewardedInterstitial"),
    ("app-open", "AdFormat.AppOpen"),
    ("native", "AdFormat.Native"),
]
# The hero plays through the six formats three times and then rests on the
# first. A README has no pause control, so it must not loop forever.
CYCLE_SECONDS = 14.4
CYCLES = 3


def hero_style() -> str:
    count = len(FORMATS)
    span = 100 / count
    fade = 2.5
    rules = [f".f{{opacity:0;animation:{CYCLE_SECONDS}s linear {CYCLES}}}", ".f0{opacity:1}"]
    for i in range(count):
        start, end = i * span, (i + 1) * span
        if i == 0:
            frames = f"0%,{end:.2f}%{{opacity:1}}{end + fade:.2f}%,{100 - fade:.2f}%{{opacity:0}}100%{{opacity:1}}"
        else:
            frames = (f"0%,{start:.2f}%{{opacity:0}}{start + fade:.2f}%,{end:.2f}%{{opacity:1}}"
                      f"{min(end + fade, 100):.2f}%,100%{{opacity:0}}")
        rules.append(f"@keyframes k{i}{{{frames}}}.f{i}{{animation-name:k{i}}}")
    rules.append("@media (prefers-reduced-motion:reduce){.f{animation:none}}")
    return "".join(rules)


def hero(theme: str) -> str:
    t = THEMES[theme]
    W, H = 1280, 520
    left = 64
    stage_x, stage_y, stage_w, stage_h = 852, 28, 400, H - 56
    display = archivo(800, 112)
    tracking = -0.035

    out = [rect(1, 1, W - 2, H - 2, 28, t["paper"], t["hair"], 1.5)]
    out.append(logo(left - 6, 48, 40, t["ink"]))
    out.append(label("admob-cmp", left + 38, 77, 28, archivo(700, 112), t["ink"], tracking=-0.02))

    # Status pill, then the searchable product line.
    pill_text = "Open source · Apache-2.0"
    pill_face = mono()
    pill_w = width(pill_text, pill_face, 14) + 44
    out.append(rect(left, 124, pill_w, 34, 17, t["raised"], t["hair"], 1.2))
    out.append(f'<circle cx="{left + 18}" cy="141" r="4" fill="{MARK_OK}"/>')
    out.append(label(pill_text, left + 32, 146, 14, pill_face, t["ink"]))
    out.append(label("Kotlin Multiplatform SDK", left + pill_w + 16, 146, 14, pill_face, t["slate"]))

    # Fit the long headline line to the column.
    line_two = ["from common", "Main", "."]
    column = stage_x - left - 36
    size = 100.0
    while width("".join(line_two), display, size, tracking) > column:
        size -= 0.5
    y1, y2 = 248, 248 + size * 1.04
    out.append(label("Ship AdMob", left - 3, y1, size, display, t["ink"], tracking=tracking))
    cursor = left - 3
    for part, colour in zip(line_two, (t["ink"], t["accent"], t["ink"])):
        out.append(label(part, cursor, y2, size, display, colour, tracking=tracking))
        cursor += width(part, display, size, tracking)

    body = archivo(420, 100)
    copy_y = y2 + 52
    out.append(label("Six ad formats, UMP consent and ATT behind one Kotlin API,", left, copy_y, 22, body, t["ink2"]))
    out.append(label("on Android and iOS.", left, copy_y + 30, 22, body, t["ink2"]))

    # Coordinate strip; deliberately has no version number.
    strip_y = copy_y + 54
    coordinate = "dev.avinya.ads:admob-cmp"
    out.append(rect(left, strip_y, width(coordinate, mono(), 17) + 76, 46, 12, STAGE))
    out.append(label("$", left + 22, strip_y + 29, 17, mono(), STAGE_SLATE))
    out.append(label(coordinate, left + 50, strip_y + 29, 17, mono(), STAGE_INK))

    # The dark stage holds both devices, as on the docs landing page.
    out.append(rect(stage_x, stage_y, stage_w, stage_h, 24, STAGE, STAGE_HAIR, 1))
    phone_w, phone_h, gap = 164, 348, 24
    px = stage_x + (stage_w - 2 * phone_w - gap) / 2
    py = stage_y + 60
    sw, sh = phone_w * 0.91, phone_h - phone_w * 0.09

    def scenes(sx, sy):
        return "".join(f'<g class="f f{i}">{scene(name, sx, sy, sw, sh)}</g>'
                       for i, (name, _) in enumerate(FORMATS))

    inset = phone_w * 0.045
    out.append(phone("sa", px, py, phone_w, phone_h, False, scenes(px + inset, py + inset), 15))
    second = px + phone_w + gap
    out.append(phone("si", second, py, phone_w, phone_h, True, scenes(second + inset, py + inset), 15))

    for i, (_, api) in enumerate(FORMATS):
        out.append(f'<g class="f f{i}">{label(api, stage_x + stage_w / 2, stage_y + 38, 16, mono(), STAGE_ACCENT, "middle")}</g>')
    caption_y = py + phone_h + 28
    out.append(label("Android · API 26+", px + phone_w / 2, caption_y, 13, mono(), STAGE_SLATE, "middle"))
    out.append(label("iOS · 15.0+", second + phone_w / 2, caption_y, 13, mono(), STAGE_SLATE, "middle"))

    return svg(W, H, "AdMob CMP. Ship AdMob from commonMain: the Compose Multiplatform AdMob SDK for Android and iOS.",
               "".join(out), hero_style())


# ------------------------------------------------------------------- cards

CARDS = [
    ("f1-anchored", "banner", "Banner",
     "Anchored, inline adaptive, fixed or collapsible.", "BannerAdView(placement)"),
    ("f2-fullscreen", "interstitial", "Interstitial",
     "Full screen at a natural break in the flow.", "interstitial(placement)"),
    ("f3-reward", "rewarded", "Rewarded",
     "Opt-in full screen, with a typed reward event.", "rewarded(placement)"),
    ("f4-reward-break", "rewarded-interstitial", "Rewarded interstitial",
     "Rewarded at a transition, with a skippable intro.", "rewardedInterstitial(p)"),
    ("f5-launch", "app-open", "App-open",
     "Shown on foreground, with cooldowns built in.", "AppOpenAdCoordinator"),
    ("f6-inline", "native", "Native",
     "Drawn in Compose from a typed layout DSL.", "NativeAdView(session, …)"),
]


# The cards sit below the fold, so a short run would finish before anyone
# reached them. They repeat for about five minutes and then rest on the same
# frame shown to people who prefer reduced motion. A README has no pause
# control, so they must not loop forever.
CARD_CYCLE_SECONDS = 6
CARD_CYCLES = 50
CARD_STAGGER_SECONDS = 0.8


def frames(name: str, stops: list[tuple[str, str]], rest: str = "") -> str:
    body = "".join(f"{at}{{{style}}}" for at, style in stops)
    rest_rule = f".{name}{{{rest}}}" if rest else ""
    return f"{rest_rule}@keyframes {name}{{{body}}}.{name}{{animation-name:{name}}}"


def moving(name: str, body: str, origin: str = "") -> str:
    style = f' style="transform-box:fill-box;transform-origin:{origin}"' if origin else ""
    return f'<g class="m {name}"{style}>{body}</g>'


def swap(name: str, first: bool, start: float, end: float) -> str:
    """One label in a counter, shown between two points of the cycle."""
    if first:
        stops = [(f"0%,{end - 0.1:.1f}%", "opacity:1"), (f"{end:.1f}%,100%", "opacity:0")]
    else:
        stops = [(f"0%,{start - 0.1:.1f}%", "opacity:0"), (f"{start:.1f}%,{end - 0.1:.1f}%", "opacity:1"),
                 (f"{end:.1f}%,100%", "opacity:0")]
    return frames(name, stops, "" if first else "opacity:0")


def motion(fmt: str, x, y, w, h) -> tuple[str, str]:
    """Returns the moving layers and CSS for one format card.

    The un-animated state of every layer is the resting picture of the ad.
    """
    pad = w * 0.08
    mono_face = mono()

    if fmt == "banner":
        height = h * 0.13
        top = y + h - height - pad * 0.9
        travel = number(height + pad * 2)
        second = (rect(x + pad + 1, top + 1, w - 2 * pad - 2, height - 2, w * 0.05, AD_TINT)
                  + rect(x + pad * 1.7, top + height * 0.3, w * 0.42, h * 0.018, h * 0.009, SCREEN_INK)
                  + rect(x + pad * 1.7, top + height * 0.3 + h * 0.04, w * 0.28, h * 0.018, h * 0.009, SCREEN_INK)
                  + rect(x + w - pad * 1.7 - height * 0.62, top + height * 0.19, height * 0.62, height * 0.62, height * 0.31, ACCENT))
        layers = moving("anchor-in", scene(fmt, x, y, w, h) + moving("anchor-swap", second))
        css = (frames("anchor-in", [("0%,10%", f"transform:translateY({travel}px)"), ("24%,84%", "transform:translateY(0)"),
                                    ("96%,100%", f"transform:translateY({travel}px)")])
               + frames("anchor-swap", [("0%,48%", "opacity:0"), ("56%,100%", "opacity:1")], "opacity:0"))
        return layers, css

    if fmt == "interstitial":
        close = w * 0.075
        ring = (f'<circle cx="{number(x + w - pad - close / 2)}" cy="{number(y + h * 0.15)}" r="{number(close * 1.5)}" '
                f'fill="none" stroke="{ACCENT}" stroke-width="1.5"/>')
        layers = moving("sheet-in", scene(fmt, x, y, w, h) + moving("sheet-ring", ring, "center"))
        offscreen = f"transform:translateY({number(h)}px)"
        css = (frames("sheet-in", [("0%,12%", offscreen), ("26%,82%", "transform:translateY(0)"), ("94%,100%", offscreen)])
               + frames("sheet-ring", [("0%,60%", "opacity:0;transform:scale(.6)"), ("68%", "opacity:1;transform:scale(1)"),
                                       ("80%,100%", "opacity:0;transform:scale(1.6)")], "opacity:0"))
        return layers, css

    if fmt == "rewarded":
        pill_w, pill_h = w * 0.3, h * 0.07
        bar_w = w * 0.42
        fill = rect(x + pad, y + h * 0.125, bar_w, h * 0.012, h * 0.006, ACCENT)
        centre, baseline, size = x + w - pad - pill_w / 2, y + h * 0.095 + pill_h * 0.72, pill_h * 0.66
        chip_w, chip_h = w * 0.68, h * 0.1
        chip = (rect(x + w / 2 - chip_w / 2, y + h * 0.45, chip_w, chip_h, chip_h / 2, SCREEN_INK)
                + label("+10 coins", x + w / 2, y + h * 0.45 + chip_h * 0.68, chip_h * 0.5, mono_face, SCREEN, "middle"))
        times = [("0:15", 18, 32), ("0:10", 32, 46), ("0:05", 46, 60), ("0:00", 60, 100)]
        counters, css = "", ""
        for i, (text, start, end) in enumerate(times):
            counters += moving(f"count-{i}", label(text, centre, baseline, size, mono_face, SCREEN, "middle"))
            css += swap(f"count-{i}", i == 0, start, end)
        layers = moving("reward-in", scene(fmt, x, y, w, h, dynamic=True) + moving("reward-bar", fill, "left center")
                        + counters + moving("reward-chip", chip, "center"))
        css += (frames("reward-in", [("0%,6%", "opacity:0"), ("16%,90%", "opacity:1"), ("98%,100%", "opacity:0")])
                + frames("reward-bar", [("0%,18%", "transform:scaleX(0)"), ("70%,100%", "transform:scaleX(1)")], "transform:scaleX(.55)")
                + frames("reward-chip", [("0%,68%", "opacity:0;transform:scale(.6)"), ("76%,90%", "opacity:1;transform:scale(1)"),
                                         ("96%,100%", "opacity:0;transform:scale(.9)")], "opacity:0"))
        return layers, css

    if fmt == "rewarded-interstitial":
        card_y, card_h = y + h * 0.33, h * 0.36
        counters, css = "", ""
        for i, (text, start, end) in enumerate([("in 3…", 16, 30), ("in 2…", 30, 44), ("in 1…", 44, 100)]):
            counters += moving(f"offer-{i}", label(text, x + w / 2, card_y + card_h * 0.61, card_h * 0.15, mono_face, SCREEN_INK, "middle"))
            css += swap(f"offer-{i}", i == 0, start, end)
        layers = (moving("offer-in", scene(fmt, x, y, w, h, dynamic=True) + counters)
                  + moving("offer-full", scene("interstitial", x, y, w, h)))
        css += (frames("offer-in", [("0%,6%", "opacity:0"), ("14%,58%", "opacity:1"), ("64%,100%", "opacity:0")])
                + frames("offer-full", [("0%,58%", "opacity:0"), ("66%,88%", "opacity:1"), ("96%,100%", "opacity:0")], "opacity:0"))
        return layers, css

    if fmt == "app-open":
        icon, gap = w * 0.2, w * 0.09
        left = x + (w - 3 * icon - 2 * gap) / 2
        tiles, tapped = "", ""
        for row in range(4):
            for column in range(3):
                tx, ty = left + column * (icon + gap), y + h * 0.17 + row * (icon + gap)
                if (row, column) == (1, 1):
                    tapped = moving("home-tap", rect(tx, ty, icon, icon, icon * 0.26, SCREEN_INK)
                                    + logo(tx + icon * 0.14, ty + icon * 0.14, icon * 0.72, SCREEN, STAGE_ACCENT), "center")
                else:
                    tiles += rect(tx, ty, icon, icon, icon * 0.26, SCREEN)
        home = rect(x, y, w, h, fill=SCREEN_HAIR) + tiles + tapped
        layers = moving("home-in", home) + moving("open-in", scene(fmt, x, y, w, h))
        css = (frames("home-in", [("0%,26%", "opacity:1"), ("36%,92%", "opacity:0"), ("100%", "opacity:1")], "opacity:0")
               + frames("home-tap", [("0%,12%", "transform:scale(1)"), ("18%", "transform:scale(.8)"), ("24%,100%", "transform:scale(1)")])
               + frames("open-in", [("0%,26%", "opacity:0"), ("36%,80%", "opacity:1"), ("90%,100%", "opacity:0")]))
        return layers, css

    if fmt == "native":
        card_y, card_h = y + h * 0.36, h * 0.44
        row_h, side = h * 0.125, w * 0.09
        names = ["Morning run", "Reading", "Sketching", "Harbour walk"]
        body_face = archivo(600, 100)

        def row(top: float, index: int) -> str:
            return (rect(x + side, top, w - 2 * side, row_h - h * 0.018, w * 0.05, SCREEN_CARD)
                    + label(names[index % 4], x + side * 1.7, top + row_h * 0.42, 5.2, body_face, SCREEN_INK)
                    + rect(x + side * 1.7, top + row_h * 0.56, w * 0.42, h * 0.012, h * 0.006, SCREEN_SLATE, extra=' opacity=".55"'))

        rows = "".join(row(card_y - row_h * (i + 1), i) for i in range(6))
        rows += "".join(row(card_y + card_h + h * 0.02 + row_h * i, i + 2) for i in range(4))
        header_h = h * 0.17 + 6.3
        header = rect(x, y, w, header_h, fill=SCREEN) + label("Journal", x + side, y + h * 0.17, 9, archivo(700, 100), SCREEN_INK)
        layers = (rect(x, y, w, h, fill=SCREEN)
                  + moving("list-in", rows + scene(fmt, x, y, w, h))
                  + header)
        far = number(h * 0.66)
        near = number(h * 0.3)
        css = frames("list-in", [("0%,10%", f"transform:translateY({far}px)"), ("26%,32%", f"transform:translateY({near}px)"),
                                 ("46%,86%", "transform:translateY(0)"), ("100%", f"transform:translateY({far}px)")])
        return layers, css

    raise ValueError(fmt)


def card(index: int, name: str, fmt: str, title: str, description: str, api: str) -> str:
    W, H = 400, 228
    out = [rect(1, 1, W - 2, H - 2, 20, STAGE, STAGE_HAIR, 1.5)]
    phone_w, phone_h = 94, 196
    px, py = 18, 16
    inset = phone_w * 0.045
    screen_w, screen_h = phone_w * 0.91, phone_h - phone_w * 0.09
    layers, css = motion(fmt, px + inset, py + inset, screen_w, screen_h)
    out.append(phone(f"s{index}", px, py, phone_w, phone_h, False, layers, 9))
    # A negative delay staggers the cards without a pause before the first frame.
    style = (f".m{{animation-duration:{CARD_CYCLE_SECONDS}s;animation-iteration-count:{CARD_CYCLES};"
             f"animation-delay:{-index * CARD_STAGGER_SECONDS:g}s;animation-timing-function:cubic-bezier(.3,.7,.2,1)}}"
             + css + "@media (prefers-reduced-motion:reduce){.m{animation:none}}")

    x = px + phone_w + 20
    available = W - x - 18
    display = archivo(760, 106)
    size = 27.0
    while width(title, display, size, -0.02) > available:
        size -= 0.5
    out.append(label(title, x, 54, size, display, STAGE_INK, tracking=-0.02))

    body = archivo(420, 100)
    y = 88
    for line in wrap(description, body, 18, available):
        out.append(label(line, x, y, 18, body, STAGE_SLATE))
        y += 25

    chip_size = 13.5
    while width(api, mono(), chip_size) + 22 > available:
        chip_size -= 0.25
    chip_w = width(api, mono(), chip_size) + 22
    out.append(rect(x, 174, chip_w, 34, 8, STAGE_RAISED, STAGE_HAIR, 1))
    out.append(label(api, x + 11, 196, chip_size, mono(), STAGE_INK))
    return svg(W, H, f"{title}: {description}", "".join(out), style)


# ------------------------------------------------------------------- order

STEPS = [
    ("01", "Gather consent", "UMP form, privacy options and canRequestAds.", None),
    ("02", "Ask for tracking", "The iOS ATT prompt, after consent is known.", "iOS"),
    ("03", "Initialize", "Google Mobile Ads starts once, on both platforms.", None),
]


def order() -> str:
    W, H = 1280, 268
    margin, gap = 32, 44
    width_each = (W - 2 * margin - 3 * gap) / 4
    top, height_each = 32, H - 64
    display = archivo(760, 106)
    body = archivo(420, 100)
    out = [rect(1, 1, W - 2, H - 2, 24, STAGE, STAGE_HAIR, 1.5)]

    for i, (num, title, text, tag) in enumerate(STEPS):
        x = margin + i * (width_each + gap)
        out.append(rect(x, top, width_each, height_each, 16, STAGE_RAISED, STAGE_HAIR, 1))
        out.append(label(num, x + 22, top + 56, 40, mono(), STAGE_ACCENT))
        if tag:
            tag_w = width(tag, mono(), 13) + 20
            out.append(rect(x + width_each - tag_w - 18, top + 28, tag_w, 28, 14, "none", STAGE_HAIR, 1.2))
            out.append(label(tag, x + width_each - tag_w / 2 - 18, top + 47, 13, mono(), STAGE_SLATE, "middle"))
        out.append(label(title, x + 22, top + 106, 25, display, STAGE_INK, tracking=-0.02))
        y = top + 140
        for line in wrap(text, body, 18, width_each - 44):
            out.append(label(line, x + 22, y, 18, body, STAGE_SLATE))
            y += 25
        cx, cy = x + width_each + gap / 2, top + height_each / 2
        out.append(f'<path d="M{number(cx - 6)} {number(cy - 11)}l11 11-11 11" fill="none" stroke="{STAGE_ACCENT}" '
                   'stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>')

    x = margin + 3 * (width_each + gap)
    out.append(rect(x, top, width_each, height_each, 16, ACCENT_STRONG))
    white = "#ffffff"
    logo_size = 56
    out.append(logo(x + 16, top + 18, logo_size, white, white))
    out.append(label("Ready", x + 22, top + 106, 25, display, white, tracking=-0.02))
    y = top + 140
    for line in wrap("Load and show any format from commonMain.", body, 18, width_each - 44):
        out.append(label(line, x + 22, y, 18, body, white))
        y += 25
    return svg(W, H, "Startup order: gather UMP consent, ask for App Tracking Transparency on iOS, initialize Google Mobile Ads, then ready.",
               "".join(out))


def main() -> None:
    for theme in THEMES:
        (OUT / f"hero-{theme}.svg").write_text(hero(theme), encoding="utf-8")
    for index, (name, fmt, title, description, api) in enumerate(CARDS):
        (OUT / f"{name}.svg").write_text(card(index, name, fmt, title, description, api), encoding="utf-8")
    (OUT / "startup-order.svg").write_text(order(), encoding="utf-8")


if __name__ == "__main__":
    main()
