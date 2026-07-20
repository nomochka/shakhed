from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source" / "ammo-atlas-transparent.png"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "item"

atlas = Image.open(SOURCE).convert("RGBA")
cell_width = atlas.width // 3
for column, name in enumerate(("bullet-crate", "missile-crate", "anti-air-missile")):
    cell = atlas.crop((column * cell_width, 0, (column + 1) * cell_width, atlas.height))
    box = cell.getchannel("A").getbbox()
    if box is None:
        raise RuntimeError(f"Empty atlas cell: {name}")
    sprite = cell.crop(box)
    side = max(sprite.size)
    output = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    output.alpha_composite(sprite, ((side - sprite.width) // 2, (side - sprite.height) // 2))
    output.resize((128, 128), Image.Resampling.NEAREST).save(OUTPUT / f"{name}.png", optimize=True)

print("Wrote ammunition crate and missile textures")
