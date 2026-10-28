"""生成 WiFi 守门员的自适应启动图标（各密度 PNG）。"""
from PIL import Image, ImageDraw
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res")

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角背景 + 竖直渐变
bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
bgd = ImageDraw.Draw(bg)
for y in range(S):
    t = y / (S - 1)
    top = (14, 116, 144)      # #0E7490
    bot = (8, 47, 73)         # #082F49
    c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,)
    bgd.line([(0, y), (S, y)], fill=c)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
img.paste(bg, (0, 0), mask)
d = ImageDraw.Draw(img)

# WiFi 三弧
cx, cy = S // 2, int(S * 0.60)
for r, w in ((int(S * 0.34), int(S * 0.085)), (int(S * 0.23), int(S * 0.085)), (int(S * 0.12), int(S * 0.085))):
    box = [cx - r, cy - r, cx + r, cy + r]
    d.arc(box, start=225, end=315, fill=(255, 255, 255, 255), width=w)

# 圆点
dot_r = int(S * 0.055)
d.ellipse([cx - dot_r, cy - dot_r + int(S * 0.02), cx + dot_r, cy + dot_r + int(S * 0.02)],
          fill=(255, 255, 255, 255))

# 右下角绿色对勾徽标
badge_c = (int(S * 0.755), int(S * 0.755))
br = int(S * 0.145)
d.ellipse([badge_c[0] - br, badge_c[1] - br, badge_c[0] + br, badge_c[1] + br],
          fill=(22, 163, 74, 255), outline=(255, 255, 255, 255), width=int(S * 0.025))
w = int(S * 0.035)
d.line([(badge_c[0] - br * 0.45, badge_c[1]),
        (badge_c[0] - br * 0.08, badge_c[1] + br * 0.38),
        (badge_c[0] + br * 0.50, badge_c[1] - br * 0.36)],
       fill=(255, 255, 255, 255), width=w, joint="curve")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in DENSITIES.items():
    folder = os.path.join(OUT, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"), "PNG")

# 应用商店/文档用大图
img.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "icon-512.png"), "PNG")
print("icons written to", OUT)
