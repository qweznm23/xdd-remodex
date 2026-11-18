import sys
from PIL import Image, ImageOps
Image.MAX_IMAGE_PIXELS = 40_000_000
with Image.open(sys.argv[1]) as source:
    source.seek(0)
    image = ImageOps.exif_transpose(source)
    dimension = int(sys.argv[2])
    image.thumbnail((dimension, dimension), Image.Resampling.LANCZOS)
    image.convert('RGB').save(sys.stdout.buffer, format='JPEG', quality=85, optimize=True)
