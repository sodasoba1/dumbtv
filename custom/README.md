# Custom banner artwork

Place source SVG files anywhere under this directory. The **Convert custom banner SVGs**
GitHub Actions workflow automatically renders them as white-on-transparent PNGs and
commits the generated files to:

`launcher/src/main/assets/custom-banners/`

Conversion rules:

- 1024 × 512 px RGBA PNG canvas
- artwork scaled proportionally, never stretched
- centred with at least 10 px of transparent canvas padding
- SVG vector paint colours converted to white
- existing opacity and transparency retained

Nested source folders are mirrored in the generated asset folder. Keep the SVG source
files here; do not manually edit generated PNGs. The workflow can also be run manually
from the repository's Actions tab.
