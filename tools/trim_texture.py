from pathlib import Path
from PIL import Image
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
image = Image.open(source).convert("RGBA")
box = image.getchannel("A").getbbox()
if box is None:
    raise RuntimeError("Texture is empty")
sprite = image.crop(box)
side = max(sprite.size)
output = Image.new("RGBA", (side, side), (0, 0, 0, 0))
output.alpha_composite(sprite, ((side - sprite.width) // 2, (side - sprite.height) // 2))
target.parent.mkdir(parents=True, exist_ok=True)
output.resize((128, 128), Image.Resampling.NEAREST).save(target, optimize=True)
