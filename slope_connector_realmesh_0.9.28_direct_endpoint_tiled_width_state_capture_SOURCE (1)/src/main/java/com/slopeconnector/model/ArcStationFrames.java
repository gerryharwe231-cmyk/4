package com.slopeconnector.model;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical cross-section frames for one ordered ArcRibbon component.
 *
 * <p>Every internal topology node owns exactly one cross-section which both neighbouring segments
 * share.  Endpoint nodes are additionally snapped to the exact face centre of their ModelBlock and
 * their tangent is forced perpendicular to that face.  This matters when an endpoint enters a circle
 * immediately: the first circular chord is already rotated, but the seam against a cubic endpoint
 * must still be a flat block face.  Without this endpoint rule the outer side opens a gap while the
 * inner side overlaps/z-fights.</p>
 */
public final class ArcStationFrames {
    private static final double EPS = 1.0E-10;

    public record Station(Vec3d center, Vec3d tangent, Vec3d width, Vec3d radial,
                          double widthSpan, double radialSpan) {}

    private ArcStationFrames() {}

    public static List<Station> build(ArcComponentFinder.Component component) {
        List<ArcComponentFinder.Segment> segments = component.segments();
        if (segments.isEmpty()) return List.of();
        List<Station> out = new ArrayList<>(segments.size() + 1);
        for (int node = 0; node <= segments.size(); node++) {
            ArcComponentFinder.Segment previous = node > 0 ? segments.get(node - 1) : null;
            ArcComponentFinder.Segment next = node < segments.size() ? segments.get(node) : null;
            Vec3d rawCenter = previous == null ? next.c0() : previous.c1();
            Vec3d center = rawCenter;
            Vec3d tangent = tangent(previous, next);

            // Endpoint seam geometry has higher priority than the first/last circular chord.
            // Start ordered tangent points from endpoint into the arc. End ordered tangent points
            // from the arc into/out through the terminal endpoint.
            if (node == 0 && component.startModelBlock() != null) {
                EndpointSnap snap = endpointSnap(component.startModelBlock(), rawCenter, false);
                center = snap.center();
                tangent = snap.orderedTangent();
            } else if (node == segments.size() && component.endModelBlock() != null) {
                EndpointSnap snap = endpointSnap(component.endModelBlock(), rawCenter, true);
                center = snap.center();
                tangent = snap.orderedTangent();
            }

            Vec3d widthA = previous == null ? null : previous.width1();
            Vec3d widthB = next == null ? null : next.width0();
            Vec3d radialA = previous == null ? null : previous.radial1();
            Vec3d radialB = next == null ? null : next.radial0();
            Vec3d width = averageAxis(widthA, widthB, tangent, null);
            Vec3d radial = averageAxis(radialA, radialB, tangent, width);

            width = project(width, tangent, fallback(tangent)).normalize();
            radial = project(radial, tangent, tangent.crossProduct(width));
            radial = radial.subtract(width.multiply(radial.dotProduct(width)));
            if (radial.lengthSquared() < EPS) radial = tangent.crossProduct(width);
            radial = radial.normalize();
            Vec3d measuredRadial = alignedAverage(radialA, radialB);
            if (measuredRadial != null && radial.dotProduct(measuredRadial) < 0.0) radial = radial.multiply(-1.0);

            double widthSpan = averageSpan(previous == null ? Double.NaN : previous.widthSpan1(),
                    next == null ? Double.NaN : next.widthSpan0());
            double radialSpan = averageSpan(previous == null ? Double.NaN : previous.radialSpan1(),
                    next == null ? Double.NaN : next.radialSpan0());
            out.add(new Station(center, tangent, width, radial,
                    Math.max(1.0E-4, widthSpan), Math.max(1.0E-4, radialSpan)));
        }
        return List.copyOf(out);
    }

    public static Vec3d[] section(Station station) {
        Vec3d w = station.width().multiply(station.widthSpan() * 0.5);
        Vec3d r = station.radial().multiply(station.radialSpan() * 0.5);
        Vec3d c = station.center();
        return new Vec3d[] {
                c.subtract(w).subtract(r),
                c.add(w).subtract(r),
                c.add(w).add(r),
                c.subtract(w).add(r)
        };
    }

    private static EndpointSnap endpointSnap(BlockPos endpoint, Vec3d rawCenter, boolean terminal) {
        Vec3d blockCenter = Vec3d.ofCenter(endpoint);
        Vec3d delta = rawCenter.subtract(blockCenter);
        Direction towardArcFace = dominant(delta);
        Vec3d faceVector = unit(towardArcFace);
        Vec3d faceCenter = blockCenter.add(faceVector.multiply(0.5));
        // Start: block -> arc. End: arc -> block, so reverse the block->arc face vector.
        Vec3d orderedTangent = terminal ? faceVector.multiply(-1.0) : faceVector;
        return new EndpointSnap(faceCenter, orderedTangent);
    }

    private static Direction dominant(Vec3d vector) {
        double ax = Math.abs(vector.x), ay = Math.abs(vector.y), az = Math.abs(vector.z);
        if (ay >= ax && ay >= az) return vector.y >= 0.0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return vector.x >= 0.0 ? Direction.EAST : Direction.WEST;
        return vector.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Vec3d unit(Direction direction) {
        return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static Vec3d tangent(ArcComponentFinder.Segment previous, ArcComponentFinder.Segment next) {
        Vec3d a = previous == null ? null : direction(previous);
        Vec3d b = next == null ? null : direction(next);
        if (a == null) return b == null ? new Vec3d(1, 0, 0) : b;
        if (b == null) return a;
        Vec3d sum = a.add(b);
        if (sum.lengthSquared() < EPS) return b;
        return sum.normalize();
    }

    private static Vec3d direction(ArcComponentFinder.Segment segment) {
        Vec3d value = segment.c1().subtract(segment.c0());
        return value.lengthSquared() < EPS ? new Vec3d(1, 0, 0) : value.normalize();
    }

    private static Vec3d averageAxis(Vec3d first, Vec3d second, Vec3d tangent, Vec3d exclude) {
        Vec3d value = alignedAverage(first, second);
        if (value == null) value = exclude == null ? fallback(tangent) : tangent.crossProduct(exclude);
        value = project(value, tangent, fallback(tangent));
        if (exclude != null) {
            value = value.subtract(exclude.multiply(value.dotProduct(exclude)));
            if (value.lengthSquared() < EPS) value = tangent.crossProduct(exclude);
        }
        return value.lengthSquared() < EPS ? fallback(tangent) : value.normalize();
    }

    private static Vec3d alignedAverage(Vec3d first, Vec3d second) {
        if (first == null && second == null) return null;
        if (first == null) return second;
        if (second == null) return first;
        Vec3d b = second;
        if (first.dotProduct(b) < 0.0) b = b.multiply(-1.0);
        Vec3d sum = first.add(b);
        return sum.lengthSquared() < EPS ? first : sum.normalize();
    }

    private static Vec3d project(Vec3d value, Vec3d tangent, Vec3d fallback) {
        Vec3d result = value.subtract(tangent.multiply(value.dotProduct(tangent)));
        if (result.lengthSquared() >= EPS) return result;
        result = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
        return result.lengthSquared() < EPS ? new Vec3d(0, 1, 0) : result;
    }

    private static Vec3d fallback(Vec3d tangent) {
        Vec3d axis = Math.abs(tangent.y) < 0.75 ? new Vec3d(0, 1, 0)
                : (Math.abs(tangent.x) < 0.75 ? new Vec3d(1, 0, 0) : new Vec3d(0, 0, 1));
        return project(axis, tangent, new Vec3d(0, 0, 1)).normalize();
    }

    private static double averageSpan(double a, double b) {
        boolean aOk = Double.isFinite(a) && a > 1.0E-6;
        boolean bOk = Double.isFinite(b) && b > 1.0E-6;
        if (aOk && bOk) return (a + b) * 0.5;
        if (aOk) return a;
        if (bOk) return b;
        return 1.0;
    }

    private record EndpointSnap(Vec3d center, Vec3d orderedTangent) {}
}
