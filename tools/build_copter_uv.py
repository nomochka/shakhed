from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source" / "copter-uv-generated.png"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "entity" / "copter_uv.png"

source = Image.open(SOURCE).convert("RGB")
cell_w, cell_h = source.width // 4, source.height // 3
atlas = Image.new("RGB", (128, 96))
for index in range(12):
    col, row = index % 4, index // 4
    # Avoid the white separator pixels around generated cells.
    margin = 5
    tile = source.crop((col * cell_w + margin, row * cell_h + margin,
                        (col + 1) * cell_w - margin, (row + 1) * cell_h - margin))
    atlas.paste(tile.resize((32, 32), Image.Resampling.NEAREST), (col * 32, row * 32))
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
atlas.save(OUTPUT, optimize=True)
print(f"Wrote copter UV map: {OUTPUT}")
