package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.surface.geometry.SegmentChainOrder;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reconstructs one connected ArcRibbon component from real prism centreline geometry. */
public final class ArcComponentFinder {
    public static final double JOIN_EPS = 0.22;
    public static final double TOPOLOGY_ENDPOINT_EPS = 0.08;
    private static final int DISCOVERY_RADIUS = 3;
    private static final int ENDPOINT_SEARCH_RADIUS = 3;
    private static final int MAX_ENTITIES = 8192;

    private ArcComponentFinder() {}

    public static Component fromClickedModelBlock(World world, BlockPos endpoint) {
        ArcRibbonBlockEntity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (int dx=-5;dx<=5;dx++) for (int dy=-5;dy<=5;dy++) for (int dz=-5;dz<=5;dz++) {
            BlockEntity be = world.getBlockEntity(endpoint.add(dx,dy,dz));
            if (!(be instanceof ArcRibbonBlockEntity ribbon)) continue;
            for (RawSegment segment : rawSegments(ribbon)) {
                double d0 = segment.c0.squaredDistanceTo(Vec3d.ofCenter(endpoint));
                double d1 = segment.c1.squaredDistanceTo(Vec3d.ofCenter(endpoint));
                double value = Math.min(d0,d1);
                if (value < best) { best=value; nearest=ribbon; }
            }
        }
        return nearest == null ? null : build(nearest);
    }

    public static Component build(ArcRibbonBlockEntity seed) {
        if (seed == null || seed.getWorld() == null) return null;
        World world = seed.getWorld();
        List<ArcRibbonBlockEntity> members = discover(world, seed);
        List<RawSegment> raw = new ArrayList<>();
        for (ArcRibbonBlockEntity member : members) raw.addAll(rawSegments(member));
        List<Segment> ordered = order(raw);
        if (ordered.isEmpty()) return null;
        ArcRibbonBlockEntity leader = ordered.get(0).owner;
        BlockPos startModel = nearestModelBlock(world, ordered.get(0).c0);
        BlockPos endModel = nearestModelBlock(world, ordered.get(ordered.size()-1).c1);
        return new Component(world, List.copyOf(members), List.copyOf(ordered), leader, startModel, endModel);
    }

    private static List<ArcRibbonBlockEntity> discover(World world, ArcRibbonBlockEntity seed) {
        List<ArcRibbonBlockEntity> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<ArcRibbonBlockEntity> queue = new ArrayDeque<>();
        queue.add(seed); visited.add(seed.getPos());
        while (!queue.isEmpty() && result.size() < MAX_ENTITIES) {
            ArcRibbonBlockEntity current = queue.removeFirst();
            result.add(current);
            BlockPos base = current.getPos();
            for (int dx=-DISCOVERY_RADIUS;dx<=DISCOVERY_RADIUS;dx++)
                for (int dy=-DISCOVERY_RADIUS;dy<=DISCOVERY_RADIUS;dy++)
                    for (int dz=-DISCOVERY_RADIUS;dz<=DISCOVERY_RADIUS;dz++) {
                        BlockPos pos = base.add(dx,dy,dz);
                        if (visited.contains(pos)) continue;
                        BlockEntity be = world.getBlockEntity(pos);
                        if (!(be instanceof ArcRibbonBlockEntity other)) continue;
                        if (!touches(current, other)) continue;
                        visited.add(pos); queue.add(other);
                    }
        }
        return result;
    }

    private static boolean touches(ArcRibbonBlockEntity a, ArcRibbonBlockEntity b) {
        double limit = TOPOLOGY_ENDPOINT_EPS*TOPOLOGY_ENDPOINT_EPS;
        List<RawSegment> aa = rawSegments(a), bb = rawSegments(b);
        for (RawSegment x : aa) for (RawSegment y : bb) {
            if (x.c0.squaredDistanceTo(y.c0)<=limit || x.c0.squaredDistanceTo(y.c1)<=limit
                    || x.c1.squaredDistanceTo(y.c0)<=limit || x.c1.squaredDistanceTo(y.c1)<=limit) return true;
        }
        return false;
    }

    private static List<RawSegment> rawSegments(ArcRibbonBlockEntity entity) {
        List<RawSegment> out = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            float[] v=prism.xyz();
            if (v==null || v.length<24) continue;
            Vec3d c0=average(entity.getPos(),v,0,4), c1=average(entity.getPos(),v,4,8);
            if (c0.squaredDistanceTo(c1)<1.0E-8) continue;
            Vec3d widthVector0=edge(v,0,1).add(edge(v,3,2)).multiply(.5);
            Vec3d widthVector1=edge(v,4,5).add(edge(v,7,6)).multiply(.5);
            Vec3d radialVector0=edge(v,0,3).add(edge(v,1,2)).multiply(.5);
            Vec3d radialVector1=edge(v,4,7).add(edge(v,5,6)).multiply(.5);
            double widthSpan0=widthVector0.length(),widthSpan1=widthVector1.length();
            double radialSpan0=radialVector0.length(),radialSpan1=radialVector1.length();
            Vec3d width0=widthSpan0<1.0E-8?new Vec3d(0,0,1):widthVector0.multiply(1.0/widthSpan0);
            Vec3d width1=widthSpan1<1.0E-8?width0:widthVector1.multiply(1.0/widthSpan1);
            Vec3d radial0=radialSpan0<1.0E-8?new Vec3d(0,1,0):radialVector0.multiply(1.0/radialSpan0);
            Vec3d radial1=radialSpan1<1.0E-8?radial0:radialVector1.multiply(1.0/radialSpan1);
            out.add(new RawSegment(entity,c0,c1,width0,width1,radial0,radial1,widthSpan0,widthSpan1,radialSpan0,radialSpan1));
        }
        return out;
    }

    private static List<Segment> order(List<RawSegment> raw) {
        if (raw.isEmpty()) return List.of();
        List<SegmentChainOrder.Edge<RawSegment>> edges = new ArrayList<>(raw.size());
        for (RawSegment segment : raw) {
            edges.add(new SegmentChainOrder.Edge<>(chainPoint(segment.c0), chainPoint(segment.c1), segment));
        }
        List<SegmentChainOrder.Oriented<RawSegment>> chain = SegmentChainOrder.order(
                edges, TOPOLOGY_ENDPOINT_EPS);
        if (chain.isEmpty()) return List.of();
        // Never return a visually plausible *partial* component.  A partial ordered chain is the
        // exact failure mode that used to make long straight/outer sections disappear.  Callers can
        // fall back to raw-prism rendering instead of silently dropping unmatched segments.
        if (chain.size() != raw.size()) return List.of();

        List<Segment> out = new ArrayList<>(chain.size());
        Vec3d prevW=null, prevR=null;
        double cumulative=0.0;
        for (SegmentChainOrder.Oriented<RawSegment> ordered : chain) {
            RawSegment r=ordered.reversed()?ordered.value().reversed():ordered.value();
            Vec3d w0=r.width0,w1=r.width1,r0=r.radial0,r1=r.radial1;
            if(prevW!=null&&prevW.dotProduct(w0)<0){w0=w0.multiply(-1);w1=w1.multiply(-1);}
            if(prevR!=null&&prevR.dotProduct(r0)<0){r0=r0.multiply(-1);r1=r1.multiply(-1);}
            // Use the clustered topology node itself as the station coordinate.  Two generated
            // prism endpoints may differ by a few hundredths because one belongs to a straight
            // sample and the other to the circular sample.  Keeping the raw values creates a real
            // render gap later; the graph node is the shared geometric joint by definition.
            Vec3d c0=new Vec3d(ordered.start().x(),ordered.start().y(),ordered.start().z());
            Vec3d c1=new Vec3d(ordered.end().x(),ordered.end().y(),ordered.end().z());
            double length=c0.distanceTo(c1);
            if(length<1.0E-6)continue;
            out.add(new Segment(r.owner,c0,c1,w0,w1,r0,r1,
                    r.widthSpan0,r.widthSpan1,r.radialSpan0,r.radialSpan1,cumulative,length));
            cumulative+=length;prevW=w1;prevR=r1;
        }
        return List.copyOf(out);
    }

    private static SegmentChainOrder.Point chainPoint(Vec3d point) {
        return new SegmentChainOrder.Point(point.x, point.y, point.z);
    }

    private static Vec3d chooseOpenEndpoint(List<RawSegment> raw) {
        List<Vec3d> points=new ArrayList<>();for(RawSegment r:raw){points.add(r.c0);points.add(r.c1);}
        Vec3d bestOpen=null,bestAny=null;double limit=JOIN_EPS*JOIN_EPS;
        for(int i=0;i<points.size();i++){
            Vec3d p=points.get(i);if(bestAny==null||compare(p,bestAny)<0)bestAny=p;
            int neighbors=0;for(int j=0;j<points.size();j++)if(i!=j&&p.squaredDistanceTo(points.get(j))<=limit)neighbors++;
            if(neighbors==0&&(bestOpen==null||compare(p,bestOpen)<0))bestOpen=p;
        }
        return bestOpen==null?bestAny:bestOpen;
    }

    private static BlockPos nearestModelBlock(World world, Vec3d point) {
        BlockPos base=BlockPos.ofFloored(point);BlockPos best=null;double bestDistance=Double.POSITIVE_INFINITY;
        for(int dx=-ENDPOINT_SEARCH_RADIUS;dx<=ENDPOINT_SEARCH_RADIUS;dx++)
            for(int dy=-ENDPOINT_SEARCH_RADIUS;dy<=ENDPOINT_SEARCH_RADIUS;dy++)
                for(int dz=-ENDPOINT_SEARCH_RADIUS;dz<=ENDPOINT_SEARCH_RADIUS;dz++){
                    BlockPos pos=base.add(dx,dy,dz);if(world.getBlockState(pos).getBlock()!=ModelSystemMod.MODEL_BLOCK)continue;
                    double d=Vec3d.ofCenter(pos).squaredDistanceTo(point);if(d<bestDistance){bestDistance=d;best=pos;}
                }
        return best;
    }

    private static int compare(Vec3d a,Vec3d b){int y=Double.compare(a.y,b.y);if(y!=0)return y;int x=Double.compare(a.x,b.x);return x!=0?x:Double.compare(a.z,b.z);}
    private static Vec3d average(BlockPos holder,float[]v,int from,int to){double x=0,y=0,z=0;for(int i=from;i<to;i++){x+=v[i*3];y+=v[i*3+1];z+=v[i*3+2];}double n=to-from;return new Vec3d(holder.getX()+x/n,holder.getY()+y/n,holder.getZ()+z/n);}
    private static Vec3d edge(float[]v,int a,int b){return new Vec3d(v[b*3]-v[a*3],v[b*3+1]-v[a*3+1],v[b*3+2]-v[a*3+2]);}

    private record RawSegment(ArcRibbonBlockEntity owner,Vec3d c0,Vec3d c1,Vec3d width0,Vec3d width1,Vec3d radial0,Vec3d radial1,
                              double widthSpan0,double widthSpan1,double radialSpan0,double radialSpan1){
        RawSegment reversed(){return new RawSegment(owner,c1,c0,width1,width0,radial1,radial0,widthSpan1,widthSpan0,radialSpan1,radialSpan0);}}
    public record Segment(ArcRibbonBlockEntity owner,Vec3d c0,Vec3d c1,Vec3d width0,Vec3d width1,Vec3d radial0,Vec3d radial1,
                          double widthSpan0,double widthSpan1,double radialSpan0,double radialSpan1,double s0,double length){}
    public record Component(World world,List<ArcRibbonBlockEntity> members,List<Segment> segments,ArcRibbonBlockEntity leader,BlockPos startModelBlock,BlockPos endModelBlock){
        public double totalLength(){Segment last=segments.get(segments.size()-1);return last.s0+last.length;}
    }
}
