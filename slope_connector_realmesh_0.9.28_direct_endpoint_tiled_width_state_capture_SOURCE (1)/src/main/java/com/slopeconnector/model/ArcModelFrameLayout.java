package com.slopeconnector.model;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Shared mapping from generated ArcStationFrames to model-local lateral/vertical axes.
 * Both the curved middle renderer and the two endpoint renderers use this exact mapping, so widening
 * can never mean one axis in the middle and another axis at the endpoint.
 */
public final class ArcModelFrameLayout {
    private static final double EPS = 1.0E-10;

    public record Layout(Vec3d lateral, Vec3d vertical, double lateralSpan, double verticalSpan) {}

    public static final class Mapping {
        private final boolean lateralUsesWidth;
        private final double lateralSign;
        private final double verticalSign;
        private final int lateralTiles;
        private final int verticalTiles;

        private Mapping(boolean lateralUsesWidth, double lateralSign, double verticalSign,
                        int lateralTiles, int verticalTiles) {
            this.lateralUsesWidth = lateralUsesWidth;
            this.lateralSign = lateralSign;
            this.verticalSign = verticalSign;
            this.lateralTiles = lateralTiles;
            this.verticalTiles = verticalTiles;
        }

        public Layout apply(ArcStationFrames.Station station) {
            Vec3d lateral = (lateralUsesWidth ? station.width() : station.radial()).multiply(lateralSign);
            Vec3d vertical = (lateralUsesWidth ? station.radial() : station.width()).multiply(verticalSign);
            double lateralSpan = lateralUsesWidth ? station.widthSpan() : station.radialSpan();
            double verticalSpan = lateralUsesWidth ? station.radialSpan() : station.widthSpan();
            return new Layout(lateral.normalize(), vertical.normalize(), lateralSpan, verticalSpan);
        }

        public int lateralTiles() { return lateralTiles; }
        public int verticalTiles() { return verticalTiles; }
    }

    private ArcModelFrameLayout() {}

    public static Mapping resolve(ArcComponentFinder.Component component, List<ArcStationFrames.Station> stations) {
        if (stations == null || stations.isEmpty()) return new Mapping(true, 1.0, 1.0, 1, 1);
        ArcStationFrames.Station middle = stations.get(stations.size() / 2);
        Vec3d preferredInner = preferredInner(component);
        boolean lateralUsesWidth;
        double lateralSign;
        double verticalSign;

        if (preferredInner != null && preferredInner.lengthSquared() > EPS) {
            preferredInner = preferredInner.normalize();
            lateralUsesWidth = Math.abs(middle.width().dotProduct(preferredInner))
                    >= Math.abs(middle.radial().dotProduct(preferredInner));
            Vec3d lateral = lateralUsesWidth ? middle.width() : middle.radial();
            Vec3d vertical = lateralUsesWidth ? middle.radial() : middle.width();
            lateralSign = lateral.dotProduct(preferredInner) < 0.0 ? -1.0 : 1.0;
            Vec3d worldUp = new Vec3d(0, 1, 0);
            verticalSign = Math.abs(vertical.dotProduct(worldUp)) > 0.15
                    && vertical.dotProduct(worldUp) < 0.0 ? -1.0 : 1.0;
        } else {
            Vec3d worldUp = new Vec3d(0, 1, 0);
            boolean verticalUsesWidth = Math.abs(middle.width().dotProduct(worldUp))
                    >= Math.abs(middle.radial().dotProduct(worldUp));
            lateralUsesWidth = !verticalUsesWidth;
            Vec3d vertical = verticalUsesWidth ? middle.width() : middle.radial();
            lateralSign = 1.0;
            verticalSign = vertical.dotProduct(worldUp) < 0.0 ? -1.0 : 1.0;
        }

        double maxLateral = 1.0;
        double maxVertical = 1.0;
        for (ArcStationFrames.Station station : stations) {
            double l = lateralUsesWidth ? station.widthSpan() : station.radialSpan();
            double v = lateralUsesWidth ? station.radialSpan() : station.widthSpan();
            maxLateral = Math.max(maxLateral, l);
            maxVertical = Math.max(maxVertical, v);
        }
        // Width settings are integer block counts. Round to the nearest tile count instead of
        // stretching one source block's texture/model across the whole span.
        int lateralTiles = Math.max(1, Math.min(64, (int)Math.round(maxLateral)));
        int verticalTiles = Math.max(1, Math.min(64, (int)Math.round(maxVertical)));
        return new Mapping(lateralUsesWidth, lateralSign, verticalSign, lateralTiles, verticalTiles);
    }

    private static Vec3d preferredInner(ArcComponentFinder.Component component) {
        BlockPos[] endpoints = {component.startModelBlock(), component.endModelBlock()};
        for (BlockPos endpoint : endpoints) {
            if (endpoint == null) continue;
            BlockEntity blockEntity = component.world().getBlockEntity(endpoint);
            if (blockEntity instanceof ModelBlockEntity model) {
                Direction direction = model.getInnerArcDirection();
                if (direction != null) {
                    return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
                }
            }
        }
        return null;
    }
}
