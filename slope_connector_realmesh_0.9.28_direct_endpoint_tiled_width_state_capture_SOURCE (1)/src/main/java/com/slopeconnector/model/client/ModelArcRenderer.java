package com.slopeconnector.model.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcModelFrameLayout;
import com.slopeconnector.model.ArcStationFrames;
import com.slopeconnector.model.ModelStateResolver;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Baked-model deformation over the already-generated ModelBlock arc.
 *
 * <p>0.9.28 treats every captured model as a real 1x1x1 tile.  Longitudinally, laterally and
 * vertically the model is repeated in one-block cells; it is never stretched across a multi-block
 * ribbon.  The same ArcStationFrames/ArcModelFrameLayout is shared with both endpoint renderers,
 * so a direct endpoint->circle seam has one exact section instead of an outer gap + inner overlap.</p>
 */
public final class ModelArcRenderer {
    private static final double SOURCE_SLICE = 1.0 / 32.0;
    private static final int MAX_MODULES = 4096;
    private static final long CACHE_TTL = 20L;
    private static final double EPS = 1.0E-8;
    private static final Map<ArcRibbonBlockEntity, MeshHandle> CACHE = new WeakHashMap<>();

    private ModelArcRenderer() {}

    public static boolean renderReplacement(ArcRibbonBlockEntity entity, float tickDelta,
                                            MatrixStack matrices, VertexConsumerProvider consumers,
                                            int fallbackLight, int overlay) {
        if (entity.getWorld() == null) return false;
        MeshHandle handle;
        try {
            handle = handle(entity);
        } catch (RuntimeException error) {
            return false;
        }
        if (handle == null) return false;
        List<Triangle> triangles = handle.byOwner().get(entity.getPos());
        if (triangles == null || triangles.isEmpty()) return false;

        BlockState state = handle.state();
        RenderLayer layer = RenderLayers.getBlockLayer(state);
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();

        for (Triangle triangle : triangles) {
            int color = 0xFFFFFF;
            if (triangle.tintIndex() >= 0) {
                int sampled = MinecraftClient.getInstance().getBlockColors().getColor(
                        state, entity.getWorld(), BlockPos.ofFloored(triangle.worldCenter()), triangle.tintIndex());
                if (sampled != -1) color = sampled & 0xFFFFFF;
            }
            int packedLight = ModelRenderLighting.sample(
                    entity.getWorld(), state, triangle.worldCenter(), triangle.normal(), fallbackLight);
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            emit(consumer, position, normalMatrix, triangle.a(), origin,
                    triangle.normal(), packedLight, overlay, red, green, blue);
            emit(consumer, position, normalMatrix, triangle.b(), origin,
                    triangle.normal(), packedLight, overlay, red, green, blue);
            emit(consumer, position, normalMatrix, triangle.c(), origin,
                    triangle.normal(), packedLight, overlay, red, green, blue);
            emit(consumer, position, normalMatrix, triangle.c(), origin,
                    triangle.normal(), packedLight, overlay, red, green, blue);
        }
        return true;
    }

    private static MeshHandle handle(ArcRibbonBlockEntity entity) {
        long tick = entity.getWorld() == null ? 0L : entity.getWorld().getTime();
        MeshHandle cached = CACHE.get(entity);
        if (cached != null && tick - cached.builtTick() <= CACHE_TTL
                && cached.state().equals(entity.getSourceState())) return cached;
        ArcComponentFinder.Component component = ArcComponentFinder.build(entity);
        if (component == null) return null;
        MeshHandle built = compile(component, tick);
        for (ArcRibbonBlockEntity member : component.members()) CACHE.put(member, built);
        return built;
    }

    private static MeshHandle compile(ArcComponentFinder.Component component, long tick) {
        BlockState state = component.leader().getSourceState();
        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        List<BakedQuad> quads = collectQuads(model, state);
        if (quads.isEmpty()) return new MeshHandle(tick, state, Map.of());

        SourceOrientation source = SourceOrientation.of(state);
        CurveSampler curve = new CurveSampler(component);
        double total = curve.totalLength();
        if (total < EPS) return new MeshHandle(tick, state, Map.of());

        int moduleCount = Math.max(1, Math.min(MAX_MODULES, (int)Math.round(total)));
        double moduleLength = total / moduleCount;
        int lateralTiles = curve.lateralTiles();
        int verticalTiles = curve.verticalTiles();
        Map<BlockPos, List<Triangle>> byOwner = new HashMap<>();

        for (int module = 0; module < moduleCount; module++) {
            double moduleStart = module * moduleLength;
            for (int lateralTile = 0; lateralTile < lateralTiles; lateralTile++) {
                for (int verticalTile = 0; verticalTile < verticalTiles; verticalTile++) {
                    for (BakedQuad quad : quads) {
                        List<SourceVertex> polygon = decode(quad, source);
                        if (polygon.size() < 3) continue;

                        // Hidden contact faces are culled exactly as normal neighbouring blocks would.
                        if (isBoundaryFace(polygon, Axis.Q, 0.0)
                                && (module > 0 || component.startModelBlock() != null)) continue;
                        if (isBoundaryFace(polygon, Axis.Q, 1.0)
                                && (module + 1 < moduleCount || component.endModelBlock() != null)) continue;
                        if (isBoundaryFace(polygon, Axis.LATERAL, -0.5) && lateralTile > 0) continue;
                        if (isBoundaryFace(polygon, Axis.LATERAL, 0.5) && lateralTile + 1 < lateralTiles) continue;
                        if (isBoundaryFace(polygon, Axis.VERTICAL, -0.5) && verticalTile > 0) continue;
                        if (isBoundaryFace(polygon, Axis.VERTICAL, 0.5) && verticalTile + 1 < verticalTiles) continue;

                        // Every repeated source tile is one block.  Clip overhanging model geometry to
                        // that block before repetition, otherwise connected arms get duplicated.
                        polygon = clip(polygon, Axis.Q, 0.0, true);
                        polygon = clip(polygon, Axis.Q, 1.0, false);
                        if (lateralTiles > 1) {
                            polygon = clip(polygon, Axis.LATERAL, -0.5, true);
                            polygon = clip(polygon, Axis.LATERAL, 0.5, false);
                        }
                        if (verticalTiles > 1) {
                            polygon = clip(polygon, Axis.VERTICAL, -0.5, true);
                            polygon = clip(polygon, Axis.VERTICAL, 0.5, false);
                        }
                        if (polygon.size() < 3) continue;

                        subdivideAndWarp(polygon, quad, moduleStart, moduleLength,
                                source, curve, lateralTile, verticalTile,
                                lateralTiles, verticalTiles, byOwner);
                    }
                }
            }
        }

        Map<BlockPos, List<Triangle>> frozen = new HashMap<>();
        byOwner.forEach((pos, list) -> frozen.put(pos, List.copyOf(list)));
        return new MeshHandle(tick, state, Map.copyOf(frozen));
    }

    private static List<BakedQuad> collectQuads(BakedModel model, BlockState state) {
        List<BakedQuad> result = new ArrayList<>();
        long seed = state.getRenderingSeed(BlockPos.ORIGIN);
        for (Direction face : Direction.values()) {
            result.addAll(model.getQuads(state, face, Random.create(seed + face.ordinal())));
        }
        result.addAll(model.getQuads(state, null, Random.create(seed + 91L)));
        return result;
    }

    private static List<SourceVertex> decode(BakedQuad quad, SourceOrientation source) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 6) return List.of();
        List<SourceVertex> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            Vec3d local = new Vec3d(
                    Float.intBitsToFloat(data[base]),
                    Float.intBitsToFloat(data[base + 1]),
                    Float.intBitsToFloat(data[base + 2]));
            Vec3d centered = local.subtract(new Vec3d(0.5, 0.5, 0.5));
            double q = 0.5 + centered.dotProduct(source.longitudinal());
            double lateral = centered.dotProduct(source.lateral());
            double vertical = centered.y;
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            out.add(new SourceVertex(q, lateral, vertical, u, v));
        }
        return out;
    }

    private static void subdivideAndWarp(List<SourceVertex> source, BakedQuad quad,
                                         double moduleStart, double moduleLength,
                                         SourceOrientation orientation, CurveSampler curve,
                                         int lateralTile, int verticalTile,
                                         int lateralTiles, int verticalTiles,
                                         Map<BlockPos, List<Triangle>> byOwner) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (SourceVertex vertex : source) {
            min = Math.min(min, vertex.q());
            max = Math.max(max, vertex.q());
        }
        if (max - min < EPS) {
            emitPolygon(source, quad, moduleStart, moduleLength, orientation, curve,
                    lateralTile, verticalTile, lateralTiles, verticalTiles, byOwner);
            return;
        }
        int first = (int)Math.floor(min / SOURCE_SLICE);
        int last = (int)Math.ceil(max / SOURCE_SLICE) - 1;
        for (int cell = first; cell <= last; cell++) {
            double low = cell * SOURCE_SLICE;
            double high = (cell + 1) * SOURCE_SLICE;
            List<SourceVertex> clipped = clip(source, Axis.Q, low, true);
            clipped = clip(clipped, Axis.Q, high, false);
            if (clipped.size() >= 3) {
                emitPolygon(clipped, quad, moduleStart, moduleLength, orientation, curve,
                        lateralTile, verticalTile, lateralTiles, verticalTiles, byOwner);
            }
        }
    }

    private static boolean isBoundaryFace(List<SourceVertex> polygon, Axis axis, double boundary) {
        if (polygon.isEmpty()) return false;
        for (SourceVertex vertex : polygon) {
            if (Math.abs(value(vertex, axis) - boundary) > 1.0E-5) return false;
        }
        return true;
    }

    private static List<SourceVertex> clip(List<SourceVertex> input, Axis axis,
                                           double boundary, boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<SourceVertex> out = new ArrayList<>();
        SourceVertex previous = input.get(input.size() - 1);
        double previousValue = value(previous, axis);
        boolean previousInside = keepGreater ? previousValue >= boundary - EPS
                : previousValue <= boundary + EPS;
        for (SourceVertex current : input) {
            double currentValue = value(current, axis);
            boolean inside = keepGreater ? currentValue >= boundary - EPS
                    : currentValue <= boundary + EPS;
            if (inside != previousInside) {
                double denominator = currentValue - previousValue;
                double t = Math.abs(denominator) < 1.0E-12
                        ? 0.0 : (boundary - previousValue) / denominator;
                out.add(previous.lerp(current, t));
            }
            if (inside) out.add(current);
            previous = current;
            previousValue = currentValue;
            previousInside = inside;
        }
        return out;
    }

    private static double value(SourceVertex vertex, Axis axis) {
        return switch (axis) {
            case Q -> vertex.q();
            case LATERAL -> vertex.lateral();
            case VERTICAL -> vertex.vertical();
        };
    }

    private static void emitPolygon(List<SourceVertex> polygon, BakedQuad quad,
                                    double moduleStart, double moduleLength,
                                    SourceOrientation orientation, CurveSampler curve,
                                    int lateralTile, int verticalTile,
                                    int lateralTiles, int verticalTiles,
                                    Map<BlockPos, List<Triangle>> byOwner) {
        WorldVertex first = warp(polygon.get(0), moduleStart, moduleLength, curve,
                lateralTile, verticalTile, lateralTiles, verticalTiles);
        for (int index = 1; index + 1 < polygon.size(); index++) {
            WorldVertex second = warp(polygon.get(index), moduleStart, moduleLength, curve,
                    lateralTile, verticalTile, lateralTiles, verticalTiles);
            WorldVertex third = warp(polygon.get(index + 1), moduleStart, moduleLength, curve,
                    lateralTile, verticalTile, lateralTiles, verticalTiles);

            double sAverage = (first.s() + second.s() + third.s()) / 3.0;
            ArcRibbonBlockEntity owner = curve.ownerAt(sAverage);
            if (owner == null) continue;

            if (curve.reversesWinding(sAverage)) {
                WorldVertex swap = second;
                second = third;
                third = swap;
            }

            Vector3f normal = normal(first, second, third);
            if (normal == null) continue;
            Vec3d center = first.world().add(second.world()).add(third.world()).multiply(1.0 / 3.0);
            byOwner.computeIfAbsent(owner.getPos(), key -> new ArrayList<>())
                    .add(new Triangle(first, second, third, normal,
                            quad.hasColor() ? quad.getColorIndex() : -1, center));
        }
    }

    private static WorldVertex warp(SourceVertex source, double moduleStart,
                                    double moduleLength, CurveSampler curve,
                                    int lateralTile, int verticalTile,
                                    int lateralTiles, int verticalTiles) {
        double s = moduleStart + source.q() * moduleLength;
        Frame frame = curve.sample(s);
        double lateralScale = frame.lateralSpan() / Math.max(1, lateralTiles);
        double verticalScale = frame.verticalSpan() / Math.max(1, verticalTiles);
        double lateralOffset = lateralTile - (lateralTiles - 1) * 0.5;
        double verticalOffset = verticalTile - (verticalTiles - 1) * 0.5;
        double lateral = (source.lateral() + lateralOffset) * lateralScale;
        double vertical = (source.vertical() + verticalOffset) * verticalScale;
        Vec3d world = frame.center()
                .add(frame.lateral().multiply(lateral))
                .add(frame.vertical().multiply(vertical));
        return new WorldVertex(world, source.u(), source.v(), s);
    }

    private static Vector3f normal(WorldVertex a, WorldVertex b, WorldVertex c) {
        Vector3f u = new Vector3f((float)(b.world().x - a.world().x),
                (float)(b.world().y - a.world().y), (float)(b.world().z - a.world().z));
        Vector3f v = new Vector3f((float)(c.world().x - a.world().x),
                (float)(c.world().y - a.world().y), (float)(c.world().z - a.world().z));
        u.cross(v);
        return u.lengthSquared() < 1.0E-10f ? null : u.normalize();
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normals,
                             WorldVertex vertex, BlockPos origin, Vector3f normal,
                             int light, int overlay, int red, int green, int blue) {
        consumer.vertex(position,
                        (float)(vertex.world().x - origin.getX()),
                        (float)(vertex.world().y - origin.getY()),
                        (float)(vertex.world().z - origin.getZ()))
                .color(red, green, blue, 255)
                .texture(vertex.u(), vertex.v())
                .overlay(overlay).light(light)
                .normal(normals, normal.x, normal.y, normal.z).next();
    }

    private static final class CurveSampler {
        private final List<ArcComponentFinder.Segment> segments;
        private final List<ArcStationFrames.Station> stations;
        private final ArcModelFrameLayout.Mapping mapping;
        private final double[] stationS;
        private final double total;

        CurveSampler(ArcComponentFinder.Component component) {
            this.segments = component.segments();
            this.stations = ArcStationFrames.build(component);
            this.mapping = ArcModelFrameLayout.resolve(component, stations);
            this.stationS = new double[stations.size()];
            double cumulative = 0.0;
            for (int i = 1; i < stations.size(); i++) {
                cumulative += stations.get(i - 1).center().distanceTo(stations.get(i).center());
                stationS[i] = cumulative;
            }
            this.total = cumulative;
        }

        double totalLength() { return total; }
        int lateralTiles() { return mapping.lateralTiles(); }
        int verticalTiles() { return mapping.verticalTiles(); }

        ArcRibbonBlockEntity ownerAt(double s) {
            if (segments.isEmpty()) return null;
            return segments.get(segmentIndexAt(Math.max(0.0, Math.min(total, s)))).owner();
        }

        boolean reversesWinding(double s) {
            Frame frame = sample(s);
            // Source canonical coordinates are (longitudinal, lateral=longitudinal x UP, vertical=UP),
            // whose determinant in q/lateral/vertical order is -1.
            double target = frame.tangent().crossProduct(frame.lateral()).dotProduct(frame.vertical());
            return target > 0.0;
        }

        Frame sample(double s) {
            if (segments.isEmpty() || stations.size() != segments.size() + 1) {
                return new Frame(Vec3d.ZERO, new Vec3d(1,0,0), new Vec3d(0,0,1), new Vec3d(0,1,0),1,1);
            }
            if (s <= 0.0) return stationFrame(stations.get(0));
            if (s >= total) return stationFrame(stations.get(stations.size() - 1));
            int index = segmentIndexAt(s);
            ArcStationFrames.Station a = stations.get(index);
            ArcStationFrames.Station b = stations.get(index + 1);
            double length = Math.max(EPS, stationS[index + 1] - stationS[index]);
            double t = Math.max(0.0, Math.min(1.0, (s - stationS[index]) / length));
            Vec3d center = a.center().lerp(b.center(), t);
            Vec3d tangent = normalizedLerp(a.tangent(), b.tangent(), t, b.center().subtract(a.center()));
            ArcStationFrames.Station interpolated = new ArcStationFrames.Station(
                    center, tangent,
                    normalizedLerp(a.width(), b.width(), t, a.width()),
                    normalizedLerp(a.radial(), b.radial(), t, a.radial()),
                    lerp(a.widthSpan(), b.widthSpan(), t),
                    lerp(a.radialSpan(), b.radialSpan(), t));
            ArcModelFrameLayout.Layout layout = mapping.apply(interpolated);
            Vec3d lateral = orthogonal(layout.lateral(), tangent);
            Vec3d vertical = orthogonal(layout.vertical(), tangent);
            vertical = vertical.subtract(lateral.multiply(vertical.dotProduct(lateral)));
            if (vertical.lengthSquared() < EPS) vertical = tangent.crossProduct(lateral);
            vertical = vertical.normalize();
            return new Frame(center, tangent, lateral, vertical,
                    layout.lateralSpan(), layout.verticalSpan());
        }

        private Frame stationFrame(ArcStationFrames.Station station) {
            ArcModelFrameLayout.Layout layout = mapping.apply(station);
            return new Frame(station.center(), station.tangent(), layout.lateral(), layout.vertical(),
                    layout.lateralSpan(), layout.verticalSpan());
        }

        private int segmentIndexAt(double s) {
            int low = 0, high = Math.max(0, stationS.length - 2);
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (stationS[middle] <= s) low = middle;
                else high = middle - 1;
            }
            return low;
        }

        private static Vec3d orthogonal(Vec3d axis, Vec3d tangent) {
            Vec3d value = axis.subtract(tangent.multiply(axis.dotProduct(tangent)));
            if (value.lengthSquared() < EPS) {
                Vec3d fallback = Math.abs(tangent.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
                value = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
            }
            return value.normalize();
        }

        private static Vec3d normalizedLerp(Vec3d first, Vec3d second, double t, Vec3d fallback) {
            Vec3d value = first.lerp(second, t);
            if (value.lengthSquared() < EPS) value = fallback;
            return value.lengthSquared() < EPS ? new Vec3d(1,0,0) : value.normalize();
        }

        private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    }

    /** Canonical source frame that preserves the captured facing, half/type and baked UVs. */
    private record SourceOrientation(Vec3d longitudinal, Vec3d lateral) {
        static SourceOrientation of(BlockState state) {
            Direction direction = ModelStateResolver.longitudinalDirection(state);
            if (direction == null || direction.getAxis().isVertical()) direction = Direction.EAST;
            Vec3d longitudinal = new Vec3d(direction.getOffsetX(), 0, direction.getOffsetZ()).normalize();
            Vec3d up = new Vec3d(0, 1, 0);
            Vec3d lateral = longitudinal.crossProduct(up).normalize();
            return new SourceOrientation(longitudinal, lateral);
        }
    }

    private enum Axis { Q, LATERAL, VERTICAL }
    private record Frame(Vec3d center, Vec3d tangent, Vec3d lateral, Vec3d vertical,
                         double lateralSpan, double verticalSpan) {}
    private record SourceVertex(double q, double lateral, double vertical, float u, float v) {
        SourceVertex lerp(SourceVertex other, double t) {
            return new SourceVertex(q + (other.q - q) * t,
                    lateral + (other.lateral - lateral) * t,
                    vertical + (other.vertical - vertical) * t,
                    (float)(u + (other.u - u) * t),
                    (float)(v + (other.v - v) * t));
        }
    }
    private record WorldVertex(Vec3d world, float u, float v, double s) {}
    private record Triangle(WorldVertex a, WorldVertex b, WorldVertex c,
                            Vector3f normal, int tintIndex, Vec3d worldCenter) {}
    private record MeshHandle(long builtTick, BlockState state,
                              Map<BlockPos, List<Triangle>> byOwner) {}
}
