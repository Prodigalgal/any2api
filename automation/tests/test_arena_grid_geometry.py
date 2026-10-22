import io
import json
import shutil
import subprocess

import pytest
from PIL import Image

from any2api_automation.providers import arena_challenge as challenge


def _read_native_geometry(
    columns: int,
    overhang: float,
    *,
    tile_opacity: float = 1,
    image_opacity: float = 1,
    visibility: str = "visible",
) -> dict:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the Arena geometry collector")
    script = (
        "const input = "
        + json.dumps(
            {
                "columns": columns,
                "overhang": overhang,
                "tileOpacity": tile_opacity,
                "imageOpacity": image_opacity,
                "visibility": visibility,
            }
        )
        + ";\n"
        + """
const payload = {left: 100, top: 50, width: 386, height: 520, right: 486, bottom: 570};
const tileWidth = (payload.width + 2 * input.overhang) / input.columns;
const tiles = Array.from({length: input.columns ** 2}, (_, index) => ({
  getBoundingClientRect: () => {
    const left = payload.left - input.overhang + (index % input.columns) * tileWidth;
    const top = payload.top + 125 + Math.floor(index / input.columns) * 390 / input.columns;
    return {left, top, width: tileWidth, height: 390 / input.columns,
      right: left + tileWidth, bottom: top + 390 / input.columns};
  },
  classList: {contains: name => name === 'rc-imageselect-tileselected' && index === 0},
  querySelectorAll: () => [{complete: true, naturalWidth: 300, currentSrc: 'synthetic-grid-image', isImage: true}]
}));
global.getComputedStyle = element => ({
  opacity: String(element.isImage ? input.imageOpacity : input.tileOpacity),
  visibility: input.visibility, display: 'block'
});
global.document = {querySelector: selector => {
  if (selector === '.rc-imageselect-payload') return {getBoundingClientRect: () => payload};
  if (selector === '.rc-imageselect-desc-wrapper') return {textContent: 'synthetic instruction'};
  if (selector === '#recaptcha-verify-button') return {
    disabled: false, getAttribute: () => null, classList: {contains: () => false}
  };
  throw new Error('Unexpected selector');
}};
const collect = """
        + challenge._GRID_STATE
        + ";\nprocess.stdout.write(JSON.stringify(collect(tiles)));"
    )
    result = subprocess.run(
        [node, "-e", script], capture_output=True, text=True, timeout=10, check=False
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def _screenshot(scale: int) -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (386 * scale, 520 * scale), "white").save(output, format="PNG")
    return output.getvalue()


@pytest.mark.parametrize("columns", [3, 4])
@pytest.mark.parametrize("overhang", [0, 2])
@pytest.mark.parametrize("scale", [1, 2])
def test_native_grid_edges_remain_labelable_inside_the_actual_screenshot(columns, overhang, scale):
    state = _read_native_geometry(columns, overhang)
    image = _screenshot(scale)
    numbered = challenge._numbered_grid_image(image, state["cellBoxes"])
    assert Image.open(io.BytesIO(numbered)).size == (386 * scale, 520 * scale)
    assert state["selected"] == [1]
    assert state["ready"] is True
    assert len(state["cellBoxes"]) == columns**2
    assert min(bounds[0] for bounds in state["cellBoxes"]) >= 0
    assert max(bounds[0] + bounds[2] for bounds in state["cellBoxes"]) <= 1.000001


@pytest.mark.parametrize("columns", [3, 4])
@pytest.mark.parametrize("overhang", [2.01, 4])
def test_grid_overflow_beyond_the_observed_edge_is_rejected(columns, overhang):
    state = _read_native_geometry(columns, overhang)
    with pytest.raises(ValueError, match="outside the image"):
        challenge._numbered_grid_image(_screenshot(1), state["cellBoxes"])


@pytest.mark.parametrize(
    "style",
    [
        {"tile_opacity": 0.01},
        {"tile_opacity": 0.5},
        {"image_opacity": 0.01},
        {"visibility": "hidden"},
    ],
)
def test_dynamic_grid_waits_for_actual_tile_visibility_and_fade_completion(style):
    state = _read_native_geometry(3, 2, **style)
    assert state["ready"] is False
