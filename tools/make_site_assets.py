import os, glob
from PIL import Image

root = r"C:\Users\demon\Desktop\gif\docs"
out = os.path.join(root, "assets")
os.makedirs(out, exist_ok=True)

def fit(im, maxw):
    if im.width > maxw:
        h = int(im.height * maxw / im.width)
        im = im.resize((maxw, h), Image.LANCZOS)
    return im

shots = [
    ("screens/home.png", "shot-home.png", 720),
    ("screens/editor.png", "shot-editor.png", 720),
    ("screens/crop_ratio_1to1.png", "shot-crop.png", 720),
    ("screens/exit_prompt.png", "shot-exit.png", 720),
    ("screens/export_size_hd.png", "shot-export.png", 720),
    ("screens/image_sticker.png", "shot-sticker.png", 720),
    ("screens/multi_image_no_dialog.png", "shot-multi.png", 720),
    ("screens/doodle.png", "shot-doodle.png", 720),
    ("screens/text_resized.png", "shot-text.png", 720),
    ("screens/gallery.png", "shot-gallery.png", 720),
    ("screens/templates.png", "shot-templates.png", 720),
]

for src, name, w in shots:
    p = os.path.join(root, src)
    if not os.path.exists(p):
        print("missing", src)
        continue
    im = Image.open(p).convert("RGBA")
    im = fit(im, w)
    dst = os.path.join(out, name)
    im.save(dst, "PNG", optimize=True)
    print(name, im.size, round(os.path.getsize(dst) / 1024, 1), "KB")

for p in glob.glob(os.path.join(root, "posters", "*.png")):
    im = Image.open(p).convert("RGB")
    im = fit(im, 760)
    name = os.path.basename(p).replace(".png", ".jpg")
    dst = os.path.join(out, name)
    im.save(dst, "JPEG", quality=88, optimize=True, progressive=True)
    print(name, im.size, round(os.path.getsize(dst) / 1024, 1), "KB")

icon = os.path.join(root, "screens", "app_icon_1024.png")
if os.path.exists(icon):
    im = Image.open(icon).convert("RGBA").resize((256, 256), Image.LANCZOS)
    dst = os.path.join(out, "app-icon.png")
    im.save(dst, "PNG", optimize=True)
    print("app-icon.png", round(os.path.getsize(dst) / 1024, 1), "KB")

gif = os.path.join(root, "screens", "device_export.gif")
if os.path.exists(gif):
    im = Image.open(gif)
    frames = []
    for i in range(getattr(im, "n_frames", 1)):
        im.seek(i)
        frames.append(im.convert("RGB").resize((320, 320), Image.LANCZOS))
    dst = os.path.join(out, "demo.gif")
    frames[0].save(dst, save_all=True, append_images=frames[1:], duration=200, loop=0, optimize=True)
    print("demo.gif", round(os.path.getsize(dst) / 1024, 1), "KB")
print("total KB", round(sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out)) / 1024, 1))
