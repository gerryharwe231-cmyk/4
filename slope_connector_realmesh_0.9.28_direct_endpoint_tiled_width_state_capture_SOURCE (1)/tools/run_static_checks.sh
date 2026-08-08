#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tests/test_geometry_and_uv.py
python3 tests/test_global_curvilinear_atlas.py
python3 tests/test_dimensions_native_endpoints.py
python3 tests/test_perimeter_atlas_and_long_arc.py
python3 tests/test_panel_and_geometry_parity.py
python3 tests/test_model_deform_and_preview.py
python3 tests/test_endpoint_light_uv_culling.py
python3 tests/test_0925_topology_orientation_conquest.py
python3 tests/test_0926_straight_seam_terminal.py
python3 tests/test_0927_shared_station_endpoint_priority.py
python3 tests/test_0928_endpoint_tiles_state_capture.py

python3 - "$ROOT" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
for path in root.rglob('*.json'):
    json.loads(path.read_text(encoding='utf-8'))
for path in root.rglob('*.java'):
    text=path.read_text(encoding='utf-8')
    if text.count('{')!=text.count('}'):
        raise SystemExit(f'brace mismatch: {path}')
print('JSON and Java structure checks passed')
PY

J19="$ROOT/libs/slopeconnector-0.9.19.jar"
J17="$ROOT/libs/slopeconnector-0.9.17.jar"
J10="$ROOT/libs/slopeconnector-0.9.10.jar"

# Existing 0.9.23 runtime API is still present.
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedArcBlockEntity | grep -q 'setData'
javap -classpath "$J19" -p 'com.slopeconnector.connected.ConnectedArcBlockEntity$Section' | grep -q 'Section(float\[\])'
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedArcGenerator | grep -q 'generate('
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedBlockClassifier | grep -q 'straightState'
javap -classpath "$J19" -p com.slopeconnector.hotfix.client.UvSafeArcRibbonRenderer | grep -q 'renderReplacement'
javap -classpath "$J17" -p com.slopeconnector.hotfix.ArcRibbonBlockEntity | grep -q 'getSurfaces'
javap -classpath "$J17" -p 'com.slopeconnector.hotfix.ArcRibbonBlockEntity$Prism' | grep -q 'materialHint'

# Exact original panel/client API required by the real G key.
javap -classpath "$J10" -p com.slopeconnector.client.ArcWandConfigScreen | grep -q 'public com.slopeconnector.client.ArcWandConfigScreen()'
javap -classpath "$J10" -p com.slopeconnector.client.ArcWandHud | grep -q 'isHoldingArcWand'
javap -classpath "$J10" -p com.slopeconnector.ArcSlopeWandItem | grep -q 'method_7884'
# The model-BE bypass targets the exact hasBlockEntity invocation in the user's embedded 0.9.10.
javap -classpath "$J10" -p -c com.slopeconnector.ArcSlopeWandItem | grep -q 'class_2680.method_31709'

echo 'bundled API checks passed'

# One and only one real G registration. No constant-patching fake key path is active.
grep -q 'KeyBindingHelper.registerKeyBinding' src/main/java/com/slopeconnector/surface/client/SurfaceRefineClient.java
grep -q 'InputUtil.GLFW_KEY_G' src/main/java/com/slopeconnector/surface/client/SurfaceRefineClient.java
! grep -R -q 'registerKeyBinding' src/main/java/com/slopeconnector/model/client/ModelSystemClient.java
! test -e src/main/java/com/slopeconnector/surface/mixin/ArcPanelKeyMixin.java

echo 'real G key registration checks passed'

# The 0.9.23 panel/geometry sources remain active.
MIXINS=src/main/resources/slopeconnector_surface_refine.mixins.json
grep -q 'ArcDimensionScreenMixin' "$MIXINS"
grep -q 'ArcHudPromptMixin' "$MIXINS"
grep -q 'ArcRibbonDimensionMixin' "$MIXINS"
grep -q 'ConnectedArcGeneratorMixin' "$MIXINS"
grep -q 'ConnectedArcRendererMixin' "$MIXINS"
grep -q 'ConnectedNeighborStateMixin' "$MIXINS"

echo '0.9.23 panel and geometry mixin checks passed'

# New model pipeline constraints.
! test -e src/main/java/com/slopeconnector/model/ModelArcWandHandler.java
grep -q 'return ActionResult.PASS;' src/main/java/com/slopeconnector/surface/SurfaceRefineMod.java
grep -q 'ArcWandModelBlockEntityBypassMixin' "$MIXINS"
grep -q 'ModelSystemMod.MODEL_BLOCK ? false : state.hasBlockEntity()' src/main/java/com/slopeconnector/surface/mixin/ArcWandModelBlockEntityBypassMixin.java
grep -q 'entity.getSourceState().getBlock() == ModelSystemMod.MODEL_BLOCK' src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java
grep -q 'ModelTemplateArcRenderer.render' src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java
grep -q 'UnifiedSurfaceArcRenderer.renderReplacement' src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java
grep -q 'ModelArcRenderer.renderReplacement' src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java

echo 'model-only gate and white-template geometry checks passed'

rm -rf /tmp/slopeconnector-segment-chain-test
mkdir -p /tmp/slopeconnector-segment-chain-test
javac -d /tmp/slopeconnector-segment-chain-test \
  src/main/java/com/slopeconnector/surface/geometry/SegmentChainOrder.java \
  tests/java/SegmentChainOrderHarness.java
java -cp /tmp/slopeconnector-segment-chain-test SegmentChainOrderHarness

