import importlib.util

spec = importlib.util.find_spec("zstandard")
if spec is not None:
    HAS_ZSTD = True
else:
    HAS_ZSTD = False
