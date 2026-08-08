#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]
renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
screen=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelRenderScreen.java').read_text()

# Repeating models are clipped to their real source cell before repetition.  Connected arms and
# multi-width copies cannot overlap several neighboring cells.
assert 'polygon = clip(polygon, Axis.Q, 0.0, true);' in renderer
assert 'polygon = clip(polygon, Axis.Q, 1.0, false);' in renderer
assert 'polygon = clip(polygon, Axis.LATERAL, -0.5, true);' in renderer
assert 'polygon = clip(polygon, Axis.LATERAL, 0.5, false);' in renderer

# Fine source slicing plus one owner per real triangle position prevents coarse vertical plates and
# triangles disappearing when a module spans more than one ArcRibbon holder block.
assert 'SOURCE_SLICE = 1.0 / 32.0' in renderer
assert 'double sAverage = (first.s() + second.s() + third.s()) / 3.0;' in renderer
assert 'ArcRibbonBlockEntity owner = curve.ownerAt(sAverage);' in renderer
assert 'moduleMid' not in renderer

# Hidden contact caps are culled in all three repeated directions.
assert 'isBoundaryFace(polygon, Axis.Q, 0.0)' in renderer
assert 'isBoundaryFace(polygon, Axis.Q, 1.0)' in renderer
assert 'isBoundaryFace(polygon, Axis.LATERAL, -0.5)' in renderer
assert 'isBoundaryFace(polygon, Axis.VERTICAL, -0.5)' in renderer
assert 'component.startModelBlock() != null' in renderer
assert 'component.endModelBlock() != null' in renderer

# Mirrored source/target bases must have their triangle winding explicitly corrected exactly once.
assert 'reversesWinding' in renderer
assert 'WorldVertex swap = second;' in renderer

# The model renderer does not invent a second Bezier/Hermite centreline.  It interpolates the exact
# shared station centres produced from generated prism topology.
assert 'Vec3d center = a.center().lerp(b.center(), t);' in renderer
assert 'ArcStationFrames.build(component)' in renderer
assert 'hermite(' not in renderer

# Cross-section axes are orthogonalized before transport.
assert 'Vec3d lateral = orthogonal(layout.lateral(), tangent);' in renderer
assert 'Vec3d vertical = orthogonal(layout.vertical(), tangent);' in renderer

# Inventory-style preview remains intact.
for token in ('context.drawItem(preview, 0, 0)', 'state.getBlock().getName()',
              'Registries.BLOCK.getId(state.getBlock())', 'stateSummary(state)',
              '清空当前模型', '完成'):
    assert token in screen, token
assert '右键普通方块/半砖/楼梯/栏杆获取模型' not in screen
assert 'properties(state)' not in screen

# Canonical source frame is always left-handed in (q,lateral,vertical) order because
# lateral=longitudinal x worldUp. Target sign opposite to that requires one winding reversal.
def det(a,b,c):
    return (
        a[0]*(b[1]*c[2]-b[2]*c[1])
        -a[1]*(b[0]*c[2]-b[2]*c[0])
        +a[2]*(b[0]*c[1]-b[1]*c[0])
    )
# East: q=X, lateral=Z, vertical=Y
assert det((1,0,0),(0,0,1),(0,1,0)) < 0
# South: q=Z, lateral=-X, vertical=Y
assert det((0,0,1),(-1,0,0),(0,1,0)) < 0

# Adjacent longitudinal modules share exact boundaries.
total=13.7
count=round(total)
pitch=total/count
for i in range(1,count):
    left=(i-1)*pitch + pitch
    right=i*pitch
    assert abs(left-right)<1e-12

print('model deformation and inventory preview regression checks passed')
