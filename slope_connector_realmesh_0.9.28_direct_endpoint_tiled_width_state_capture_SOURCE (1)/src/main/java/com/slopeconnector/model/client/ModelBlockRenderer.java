package com.slopeconnector.model.client;

import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelStateResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
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
import java.util.List;

/**
 * Endpoint renderer using the exact shared arc-frame layout stamped by ModelRenderWandItem.
 * Multi-block dimensions repeat one captured 1x1x1 model tile instead of stretching it.  The source
 * BlockState is preserved for stairs/slabs; its baked facing/half/type is converted into canonical
 * source coordinates and transported into the endpoint seam frame.
 */
public final class ModelBlockRenderer implements BlockEntityRenderer<ModelBlockEntity> {
    private static final double EPS = 1.0E-9;

    public ModelBlockRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public void render(ModelBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        if (!entity.isSkinned() || entity.getWorld() == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        BlockState state = entity.getDisplayState();
        BakedModel model;
        List<BakedQuad> quads;
        try {
            model = client.getBlockRenderManager().getModel(state);
            quads = collect(model, state);
        } catch (RuntimeException error) {
            state = Blocks.WHITE_CONCRETE.getDefaultState();
            model = client.getBlockRenderManager().getModel(state);
            quads = collect(model, state);
        }
        if (quads.isEmpty()) return;

        SourceOrientation source = SourceOrientation.of(state);
        Direction targetDirection = endpointLongitudinal(entity);
        Vec3d targetLongitudinal = unit(targetDirection);
        Vec3d targetLateral = orthogonal(entity.getSeamLateral(), targetLongitudinal,
                targetLongitudinal.crossProduct(new Vec3d(0, 1, 0)));
        Vec3d targetVertical = orthogonal(entity.getSeamVertical(), targetLongitudinal, new Vec3d(0, 1, 0));
        targetVertical = targetVertical.subtract(targetLateral.multiply(targetVertical.dotProduct(targetLateral)));
        if (targetVertical.lengthSquared() < EPS) targetVertical = targetLongitudinal.crossProduct(targetLateral);
        targetVertical = targetVertical.normalize();

        int lateralTiles = Math.max(1, entity.getSeamLateralTiles());
        int verticalTiles = Math.max(1, entity.getSeamVerticalTiles());
        double lateralScale = entity.getSeamLateralSpan() / lateralTiles;
        double verticalScale = entity.getSeamVerticalSpan() / verticalTiles;

        RenderLayer layer = RenderLayers.getBlockLayer(state);
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos blockPos = entity.getPos();

        for (int lateralTile = 0; lateralTile < lateralTiles; lateralTile++) {
            for (int verticalTile = 0; verticalTile < verticalTiles; verticalTile++) {
                for (BakedQuad quad : quads) {
                    SourceVertex[] sourceVertices = decode(quad, source);
                    if (sourceVertices == null) continue;
                    if (isBoundaryFace(sourceVertices, Axis.LATERAL, -0.5) && lateralTile > 0) continue;
                    if (isBoundaryFace(sourceVertices, Axis.LATERAL, 0.5) && lateralTile + 1 < lateralTiles) continue;
                    if (isBoundaryFace(sourceVertices, Axis.VERTICAL, -0.5) && verticalTile > 0) continue;
                    if (isBoundaryFace(sourceVertices, Axis.VERTICAL, 0.5) && verticalTile + 1 < verticalTiles) continue;

                    Vertex[] vertices = new Vertex[4];
                    for (int i = 0; i < 4; i++) {
                        SourceVertex sourceVertex = sourceVertices[i];
                        double lateralOffset = lateralTile - (lateralTiles - 1) * 0.5;
                        double verticalOffset = verticalTile - (verticalTiles - 1) * 0.5;
                        Vec3d local = new Vec3d(0.5, 0.5, 0.5)
                                .add(targetLongitudinal.multiply(sourceVertex.q() - 0.5))
                                .add(targetLateral.multiply((sourceVertex.lateral() + lateralOffset) * lateralScale))
                                .add(targetVertical.multiply((sourceVertex.vertical() + verticalOffset) * verticalScale));
                        vertices[i] = new Vertex(local, sourceVertex.u(), sourceVertex.v());
                    }
                    Vector3f normal = faceNormal(vertices);
                    if (normal == null) continue;

                    int tint = 0xFFFFFF;
                    if (quad.hasColor()) {
                        int sampled = client.getBlockColors().getColor(state, entity.getWorld(), blockPos, quad.getColorIndex());
                        if (sampled != -1) tint = sampled & 0xFFFFFF;
                    }
                    Vec3d quadCenter = new Vec3d(
                            blockPos.getX() + averageX(vertices),
                            blockPos.getY() + averageY(vertices),
                            blockPos.getZ() + averageZ(vertices));
                    int packedLight = ModelRenderLighting.sample(
                            entity.getWorld(), state, quadCenter, normal, fallbackLight);
                    int red = (tint >> 16) & 255;
                    int green = (tint >> 8) & 255;
                    int blue = tint & 255;
                    for (Vertex vertex : vertices) {
                        emit(consumer, position, normalMatrix, vertex, normal,
                                packedLight, overlay, red, green, blue);
                    }
                }
            }
        }
    }

    private static List<BakedQuad> collect(BakedModel model, BlockState state) {
        List<BakedQuad> out = new ArrayList<>();
        long seed = state.getRenderingSeed(BlockPos.ORIGIN);
        for (Direction face : Direction.values()) {
            out.addAll(model.getQuads(state, face, Random.create(seed + face.ordinal())));
        }
        out.addAll(model.getQuads(state, null, Random.create(seed + 91L)));
        return out;
    }

    private static SourceVertex[] decode(BakedQuad quad, SourceOrientation source) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 6) return null;
        SourceVertex[] out = new SourceVertex[4];
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            Vec3d local = new Vec3d(
                    Float.intBitsToFloat(data[base]),
                    Float.intBitsToFloat(data[base + 1]),
                    Float.intBitsToFloat(data[base + 2]));
            Vec3d centered = local.subtract(new Vec3d(0.5, 0.5, 0.5));
            out[i] = new SourceVertex(
                    0.5 + centered.dotProduct(source.longitudinal()),
                    centered.dotProduct(source.lateral()),
                    centered.y,
                    Float.intBitsToFloat(data[base + 4]),
                    Float.intBitsToFloat(data[base + 5]));
        }
        return out;
    }

    private static boolean isBoundaryFace(SourceVertex[] vertices, Axis axis, double boundary) {
        for (SourceVertex vertex : vertices) {
            double value = switch (axis) {
                case LATERAL -> vertex.lateral();
                case VERTICAL -> vertex.vertical();
            };
            if (Math.abs(value - boundary) > 1.0E-5) return false;
        }
        return true;
    }

    private static Vector3f faceNormal(Vertex[] vertices) {
        Vector3f a = vec(vertices[0].pos()), b = vec(vertices[1].pos()), c = vec(vertices[2].pos());
        Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        if (normal.lengthSquared() < 1.0E-8f) return null;
        return normal.normalize();
    }

    /** Start points into the arc; terminal end continues out of the arc. */
    private static Direction endpointLongitudinal(ModelBlockEntity entity) {
        Direction towardArc = entity.getArcDirection() == null ? Direction.EAST : entity.getArcDirection();
        return entity.isTerminalEnd() ? towardArc.getOpposite() : towardArc;
    }

    private static Vec3d orthogonal(Vec3d axis, Vec3d tangent, Vec3d fallback) {
        Vec3d value = axis == null ? fallback : axis;
        value = value.subtract(tangent.multiply(value.dotProduct(tangent)));
        if (value.lengthSquared() < EPS) {
            value = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
        }
        if (value.lengthSquared() < EPS) value = new Vec3d(0, 1, 0);
        return value.normalize();
    }

    private static Vec3d unit(Direction direction) {
        return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static Vector3f vec(Vec3d value) {
        return new Vector3f((float)value.x, (float)value.y, (float)value.z);
    }

    private static double averageX(Vertex[] v) { return (v[0].pos().x + v[1].pos().x + v[2].pos().x + v[3].pos().x) * 0.25; }
    private static double averageY(Vertex[] v) { return (v[0].pos().y + v[1].pos().y + v[2].pos().y + v[3].pos().y) * 0.25; }
    private static double averageZ(Vertex[] v) { return (v[0].pos().z + v[1].pos().z + v[2].pos().z + v[3].pos().z) * 0.25; }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normalMatrix,
                             Vertex vertex, Vector3f normal, int light, int overlay,
                             int red, int green, int blue) {
        consumer.vertex(position, (float)vertex.pos().x, (float)vertex.pos().y, (float)vertex.pos().z)
                .color(red, green, blue, 255)
                .texture(vertex.u(), vertex.v())
                .overlay(overlay)
                .light(light)
                .normal(normalMatrix, normal.x, normal.y, normal.z)
                .next();
    }

    private record SourceOrientation(Vec3d longitudinal, Vec3d lateral) {
        static SourceOrientation of(BlockState state) {
            Direction direction = ModelStateResolver.longitudinalDirection(state);
            if (direction == null || direction.getAxis().isVertical()) direction = Direction.EAST;
            Vec3d longitudinal = new Vec3d(direction.getOffsetX(), 0, direction.getOffsetZ()).normalize();
            Vec3d lateral = longitudinal.crossProduct(new Vec3d(0, 1, 0)).normalize();
            return new SourceOrientation(longitudinal, lateral);
        }
    }

    private enum Axis { LATERAL, VERTICAL }
    private record SourceVertex(double q, double lateral, double vertical, float u, float v) {}
    private record Vertex(Vec3d pos, float u, float v) {}
}
