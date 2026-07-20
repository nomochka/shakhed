from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source" / "equipment-atlas-transparent.png"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "item"
NAMES = [
    "shakhed", "fp-1", "geran-4", "jet",
    "copter", "anti-air", "ew-station", "ew-battery",
    "copter-battery", "controller", "launch-pad", "wire",
]

atlas = Image.open(SOURCE).convert("RGBA")
cell_width = atlas.width // 4
cell_height = atlas.height // 3
OUTPUT.mkdir(parents=True, exist_ok=True)

for index, name in enumerate(NAMES):
    column = index % 4
    row = index // 4
    cell = atlas.crop((column * cell_width, row * cell_height,
                       (column + 1) * cell_width, (row + 1) * cell_height))
    alpha_box = cell.getchannel("A").getbbox()
    if alpha_box is None:
        raise RuntimeError(f"Empty atlas cell: {name}")
    sprite = cell.crop(alpha_box)
    side = max(sprite.width, sprite.height)
    padded = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    padded.alpha_composite(sprite, ((side - sprite.width) // 2, (side - sprite.height) // 2))
    padded.resize((128, 128), Image.Resampling.NEAREST).save(OUTPUT / f"{name}.png", optimize=True)

print(f"Wrote {len(NAMES)} textures to {OUTPUT}")
