package com.slopeconnector.model.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcStationFrames;
import com.slopeconnector.model.ModelSystemMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pure-white template renderer built from shared station cross-sections.
 *
 * <p>0.9.26 extended every prism at both ends to hide cracks.  Longitudinal side faces then overlap
 * on the bottom/top and z-fight, while the faceMask can still omit an externally-visible face.  This
 * renderer does neither: every segment gets four longitudinal faces, no internal end cap, and the
 * two neighboring segments literally reuse the same four station corner positions.</p>
 */
public final class ModelTemplateArcRenderer {
    private static final long CACHE_TTL = 20L;
    private static final Map<ArcRibbonBlockEntity, TemplateHandle> CACHE = new WeakHashMap<>();

    private ModelTemplateArcRenderer() {}

    public static void render(ArcRibbonBlockEntity entity, MatrixStack matrices,
                              VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        if (entity.getWorld() == null) return;
        TemplateHandle handle;
        try {
            handle = handle(entity);
        } catch (RuntimeException error) {
            renderFallback(entity, matrices, consumers, fallbackLight, overlay);
            return;
        }
        if (handle == null) {
            renderFallback(entity, matrices, consumers, fallbackLight, overlay);
            return;
        }
        List<Strip> strips = handle.byOwner().get(entity.getPos());
        if (strips == null || strips.isEmpty()) return;

        Sprite sprite = MinecraftClient.getInstance().getBlockRenderManager()
                .getModel(ModelSystemMod.MODEL_BLOCK.getDefaultState()).getParticleSprite();
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(ModelSystemMod.MODEL_BLOCK.getDefaultState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();
        for (Strip strip : strips) {
            for (int edge = 0; edge < 4; edge++) {
                int next = (edge + 1) & 3;
                quad(entity, consumer, position, normalMatrix, origin, sprite,
                        strip.start()[edge], strip.end()[edge], strip.end()[next], strip.start()[next],
                        strip.center(), fallbackLight, overlay);
            }
        }
    }

    private static TemplateHandle handle(ArcRibbonBlockEntity entity) {
        long tick = entity.getWorld() == null ? 0L : entity.getWorld().getTime();
        TemplateHandle cached = CACHE.get(entity);
        if (cached != null && tick - cached.builtTick() <= CACHE_TTL) return cached;
        ArcComponentFinder.Component component = ArcComponentFinder.build(entity);
        if (component == null || component.segments().isEmpty()) return null;
        List<ArcStationFrames.Station> stations = ArcStationFrames.build(component);
        if (stations.size() != component.segments().size() + 1) return null;
        Map<BlockPos, List<Strip>> byOwner = new HashMap<>();
        for (int i = 0; i < component.segments().size(); i++) {
            ArcComponentFinder.Segment segment = component.segments().get(i);
            Vec3d[] start = ArcStationFrames.section(stations.get(i));
            Vec3d[] end = ArcStationFrames.section(stations.get(i + 1));
            Vec3d center = stations.get(i).center().add(stations.get(i + 1).center()).multiply(0.5);
            byOwner.computeIfAbsent(segment.owner().getPos(), ignored -> new ArrayList<>())
                    .add(new Strip(start, end, center));
        }
        Map<BlockPos, List<Strip>> frozen = new HashMap<>();
        byOwner.forEach((pos, list) -> frozen.put(pos, List.copyOf(list)));
        TemplateHandle built = new TemplateHandle(tick, Map.copyOf(frozen));
        for (ArcRibbonBlockEntity member : component.members()) CACHE.put(member, built);
        return built;
    }

    /**
     * Component reconstruction can fail while chunks are still streaming.  The fallback deliberately
     * renders all four longitudinal sides of each raw prism, no expansion and no end caps.  It is
     * therefore still a full straight block-like strip and cannot reintroduce the old z-fight.
     */
    private static void renderFallback(ArcRibbonBlockEntity entity, MatrixStack matrices,
                                       VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        Sprite sprite = MinecraftClient.getInstance().getBlockRenderManager()
                .getModel(ModelSystemMod.MODEL_BLOCK.getDefaultState()).getParticleSprite();
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(ModelSystemMod.MODEL_BLOCK.getDefaultState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            float[] v = prism.xyz();
            if (v == null || v.length != 24) continue;
            Vec3d[] p = new Vec3d[8];
            for (int i = 0; i < 8; i++) {
                p[i] = new Vec3d(origin.getX()+v[i*3], origin.getY()+v[i*3+1], origin.getZ()+v[i*3+2]);
            }
            Vec3d center = average(p);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[0],p[4],p[5],p[1],center,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[1],p[5],p[6],p[2],center,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[2],p[6],p[7],p[3],center,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[3],p[7],p[4],p[0],center,fallbackLight,overlay);
        }
    }

    private static void quad(ArcRibbonBlockEntity entity, VertexConsumer consumer,
                             Matrix4f position, Matrix3f normalMatrix, BlockPos origin, Sprite sprite,
                             Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d segmentCenter,
                             int fallbackLight, int overlay) {
        Vec3d[] points = {a,b,c,d};
        Vector3f normal = normal(points);
        if (normal == null) return;
        Vec3d faceCenter = a.add(b).add(c).add(d).multiply(0.25);
        Vec3d outward = faceCenter.subtract(segmentCenter);
        if (normal.x*outward.x + normal.y*outward.y + normal.z*outward.z < 0.0) {
            Vec3d swap = points[1]; points[1] = points[3]; points[3] = swap;
            normal.mul(-1f);
        }
        int light = ModelRenderLighting.sample(entity.getWorld(), ModelSystemMod.MODEL_BLOCK.getDefaultState(),
                faceCenter, normal, fallbackLight);
        emit(consumer,position,normalMatrix,origin,points[0],sprite.getMinU(),sprite.getMinV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[1],sprite.getMaxU(),sprite.getMinV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[2],sprite.getMaxU(),sprite.getMaxV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[3],sprite.getMinU(),sprite.getMaxV(),normal,light,overlay);
    }

    private static Vector3f normal(Vec3d[] p) {
        Vector3f a = vec(p[0]), b = vec(p[1]), c = vec(p[2]);
        Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        return n.lengthSquared() < 1.0E-10f ? null : n.normalize();
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normalMatrix,
                             BlockPos origin, Vec3d point, float u, float v, Vector3f normal,
                             int light, int overlay) {
        consumer.vertex(position,(float)(point.x-origin.getX()),(float)(point.y-origin.getY()),(float)(point.z-origin.getZ()))
                .color(255,255,255,255).texture(u,v).overlay(overlay).light(light)
                .normal(normalMatrix,normal.x,normal.y,normal.z).next();
    }

    private static Vec3d average(Vec3d[] values) {
        Vec3d sum = Vec3d.ZERO; for (Vec3d value : values) sum = sum.add(value); return sum.multiply(1.0/values.length);
    }
    private static Vector3f vec(Vec3d value){return new Vector3f((float)value.x,(float)value.y,(float)value.z);}

    private record Strip(Vec3d[] start, Vec3d[] end, Vec3d center) {}
    private record TemplateHandle(long builtTick, Map<BlockPos, List<Strip>> byOwner) {}
}
