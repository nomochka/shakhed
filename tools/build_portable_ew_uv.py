from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source" / "portable-ew-uv-generated.png"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "entity" / "portable-ew"
PARTS = ("upper", "side", "emitter", "display", "grip", "battery_bay", "battery", "internal")

source = Image.open(SOURCE).convert("RGB")
cell_w, cell_h = source.width // 4, source.height // 2
OUTPUT.mkdir(parents=True, exist_ok=True)
for index, part in enumerate(PARTS):
    col, row = index % 4, index // 4
    margin = 5
    tile = source.crop((col * cell_w + margin, row * cell_h + margin,
                        (col + 1) * cell_w - margin, (row + 1) * cell_h - margin))
    tile.resize((32, 32), Image.Resampling.NEAREST).save(OUTPUT / f"{part}.png", optimize=True)
print("Wrote portable EW layered textures")
