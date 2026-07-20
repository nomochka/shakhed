from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source" / "material-atlas.png"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "material"
NAMES = ("tan_composite", "aircraft_aluminum", "dark_composite", "jet_metal",
         "drone_polymer", "olive_steel", "battery_panel", "gunmetal")

atlas = Image.open(SOURCE).convert("RGB")
width, height = atlas.width // 4, atlas.height // 2
OUTPUT.mkdir(parents=True, exist_ok=True)
for index, name in enumerate(NAMES):
    x, y = index % 4, index // 4
    tile = atlas.crop((x * width, y * height, (x + 1) * width, (y + 1) * height))
    tile.resize((32, 32), Image.Resampling.NEAREST).save(OUTPUT / f"{name}.png", optimize=True)
print(f"Wrote {len(NAMES)} voxel materials")
