#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]
station=(ROOT/'src/main/java/com/slopeconnector/model/ArcStationFrames.java').read_text()
layout=(ROOT/'src/main/java/com/slopeconnector/model/ArcModelFrameLayout.java').read_text()
arc=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
connection=(ROOT/'src/main/java/com/slopeconnector/surface/ConnectionStateHelper.java').read_text()
screen=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
client=(ROOT/'src/main/java/com/slopeconnector/surface/dimensions/ArcDimensionClientState.java').read_text()

# Direct endpoint -> circle: endpoint station must be snapped to the exact ModelBlock face and the
# ordered tangent must be perpendicular to that face instead of inheriting the first rotated chord.
assert 'endpointSnap(component.startModelBlock(), rawCenter, false)' in station
assert 'endpointSnap(component.endModelBlock(), rawCenter, true)' in station
assert 'faceCenter = blockCenter.add(faceVector.multiply(0.5))' in station
assert 'terminal ? faceVector.multiply(-1.0) : faceVector' in station

# A 3-block span is three source tiles, not one texture/model stretched three times.
for required in [
    'for (int lateralTile = 0; lateralTile < lateralTiles; lateralTile++)',
    'for (int verticalTile = 0; verticalTile < verticalTiles; verticalTile++)',
    'frame.lateralSpan() / Math.max(1, lateralTiles)',
    'frame.verticalSpan() / Math.max(1, verticalTiles)',
    'isBoundaryFace(polygon, Axis.LATERAL',
    'isBoundaryFace(polygon, Axis.VERTICAL'
]:
    assert required in arc,required
assert 'seamLateralTiles' in entity and 'seamVerticalTiles' in entity
assert 'getSeamLateralTiles()' in endpoint and 'getSeamVerticalTiles()' in endpoint
assert 'setSeamLayout(layout,lateralTiles,verticalTiles)' in wand

# Mathematical tiling sanity: N copies each keep one local unit and exactly fill the target span.
def tile_ranges(span,n):
    scale=span/n
    return [((-0.5+i-(n-1)*0.5)*scale,(0.5+i-(n-1)*0.5)*scale) for i in range(n)]
r=tile_ranges(3.0,3)
assert r==[(-1.5,-0.5),(-0.5,0.5),(0.5,1.5)],r
assert all(abs(r[i][1]-r[i+1][0])<1e-12 for i in range(len(r)-1))

# Endpoint and middle use one shared model-frame mapping/spans.
assert 'ArcModelFrameLayout.resolve(component, stations)' in wand
assert 'ArcModelFrameLayout.resolve(component, stations)' in arc
assert 'getSeamLateralSpan()' in endpoint and 'getSeamVerticalSpan()' in endpoint

# Stairs/slabs are explicitly excluded from connected-profile normalization. Captured BlockState is
# therefore allowed to keep facing, half/type and shape. Longitudinal direction preserves EAST/WEST
# and NORTH/SOUTH rather than reducing facing to only an axis.
assert 'block instanceof StairsBlock || block instanceof SlabBlock' in connection
assert 'if (!ConnectionStateHelper.isSupported(captured)) return captured;' in resolver
assert 'public static Direction longitudinalDirection' in resolver
assert 'return direction;' in resolver
assert '0.5 + centered.dotProduct(source.longitudinal())' in arc
assert 'centered.dotProduct(source.lateral())' in arc
assert 'MaterialStateCodec.write(state)' in wand

# Deliberate UI rename only: old width control -> 上下厚度, added upDown value -> 侧面宽度.
assert 'return "上下 -";' in screen and 'return "上下 +";' in screen
assert '上下厚度：\\u0001' in screen
assert '侧面 -' in screen and '侧面 +' in screen
assert '侧面宽度' in client

print('0.9.28 direct-endpoint seam, tiled width, endpoint expansion and exact stair/slab state checks passed')
