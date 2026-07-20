from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "resource-pack" / "source"
OUTPUT = ROOT / "resource-pack" / "assets" / "shakheddrones" / "textures" / "entity"
PARTS = ("upper", "side", "underside", "wing_upper", "wing_lower", "nose",
         "engine", "prop_or_edge", "tail_left", "tail_right", "service", "internal")

for drone in ("shakhed", "fp-1", "geran-4", "jet"):
    source = Image.open(SOURCE / f"{drone}-uv-generated.png").convert("RGB")
    cell_w, cell_h = source.width // 4, source.height // 3
    atlas = Image.new("RGB", (128, 96))
    target_dir = OUTPUT / drone
    target_dir.mkdir(parents=True, exist_ok=True)
    for index, part in enumerate(PARTS):
        col, row = index % 4, index // 4
        margin = 5
        tile = source.crop((col * cell_w + margin, row * cell_h + margin,
                            (col + 1) * cell_w - margin, (row + 1) * cell_h - margin))
        tile = tile.resize((32, 32), Image.Resampling.NEAREST)
        tile.save(target_dir / f"{part}.png", optimize=True)
        atlas.paste(tile, (col * 32, row * 32))
    atlas.save(OUTPUT / f"{drone}_uv.png", optimize=True)
print("Wrote layered UV maps for four long-range drones")
