package com.slopeconnector.model;

import com.slopeconnector.MaterialStateCodec;
import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class ModelRenderWandItem extends Item {
    public static final String ROOT = "SlopeConnectorModelRender";
    private static final String CAPTURED = "CapturedState";

    public ModelRenderWandItem(Settings settings) { super(settings); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world=context.getWorld();
        if (world.isClient()) return ActionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayerEntity player)) return ActionResult.PASS;
        BlockPos pos=context.getBlockPos();BlockState state=world.getBlockState(pos);ItemStack stack=context.getStack();

        if (state.getBlock()==ModelSystemMod.MODEL_BLOCK || state.getBlock()==ArcHotfixMod.ARC_RIBBON) {
            BlockState captured=getCaptured(stack);
            if (captured==null) {
                player.sendMessage(Text.literal("模型渲染杖还没有获取模型。请先右键普通方块、半砖、楼梯或栏杆。"),false);
                return ActionResult.SUCCESS;
            }
            applyToComponent(world,pos,captured,player);
            return ActionResult.SUCCESS;
        }

        if (state.isAir() || state.getRenderType()!= BlockRenderType.MODEL || state.hasBlockEntity()) {
            player.sendMessage(Text.literal("这个方块不是可烘焙方块模型，无法获取。"),false);
            return ActionResult.SUCCESS;
        }
        if (state.getBlock()==ArcHotfixMod.ARC_TRIM) {
            player.sendMessage(Text.literal("不能从弧边裁切承载方块获取模型。"),false);
            return ActionResult.SUCCESS;
        }
        setCaptured(stack,state);
        player.sendMessage(Text.literal("已获取模型："+ Registries.BLOCK.getId(state.getBlock())),false);
        return ActionResult.SUCCESS;
    }

    public static void setCaptured(ItemStack stack, BlockState state) {
        stack.getOrCreateSubNbt(ROOT).put(CAPTURED, MaterialStateCodec.write(state));
    }
    public static BlockState getCaptured(ItemStack stack) {
        if (stack==null||stack.isEmpty()||!(stack.getItem() instanceof ModelRenderWandItem)) return null;
        NbtCompound root=stack.getSubNbt(ROOT);if(root==null||!root.contains(CAPTURED))return null;
        return MaterialStateCodec.read(root.getCompound(CAPTURED));
    }
    public static void clearCaptured(ItemStack stack) { NbtCompound nbt=stack.getNbt();if(nbt!=null)nbt.remove(ROOT); }

    private static void applyToComponent(World world, BlockPos clicked, BlockState captured, ServerPlayerEntity player) {
        ArcComponentFinder.Component component;
        if (world.getBlockState(clicked).getBlock()==ModelSystemMod.MODEL_BLOCK) component=ArcComponentFinder.fromClickedModelBlock(world,clicked);
        else {
            var be=world.getBlockEntity(clicked);
            component=be instanceof ArcRibbonBlockEntity ribbon?ArcComponentFinder.build(ribbon):null;
        }
        if (component==null||component.segments().isEmpty()) {
            if (world.getBlockEntity(clicked) instanceof ModelBlockEntity model) {
                Direction inner = SlopeConnectorMod.settings(player).face == null
                        ? Direction.UP : SlopeConnectorMod.settings(player).face;
                model.setSkin(captured,Direction.NORTH,inner);
                player.sendMessage(Text.literal("未找到连接弧，仅替换了当前模型方块。"),false);
            } else player.sendMessage(Text.literal("没有找到与此位置连接的模型弧段。"),false);
            return;
        }

        List<ArcStationFrames.Station> stations = ArcStationFrames.build(component);
        ArcModelFrameLayout.Mapping mapping = ArcModelFrameLayout.resolve(component, stations);
        ArcModelFrameLayout.Layout startLayout = stations.isEmpty() ? null : mapping.apply(stations.get(0));
        ArcModelFrameLayout.Layout endLayout = stations.isEmpty() ? null : mapping.apply(stations.get(stations.size()-1));

        // The endpoint renderer receives the exact same model-frame axes/spans as the first/last arc
        // station.  Width > 1 therefore expands endpoints by repeating 1-block model tiles instead of
        // leaving a single 1x1 endpoint beside a wider arc.
        if(component.startModelBlock()!=null && !stations.isEmpty()) skinEndpoint(world,component.startModelBlock(),captured,
                endpointConnectionDirection(component.startModelBlock(),stations.get(0).center()),false,
                startLayout,mapping.lateralTiles(),mapping.verticalTiles());
        if(component.endModelBlock()!=null && !stations.isEmpty()) skinEndpoint(world,component.endModelBlock(),captured,
                endpointConnectionDirection(component.endModelBlock(),stations.get(stations.size()-1).center()),true,
                endLayout,mapping.lateralTiles(),mapping.verticalTiles());

        BlockState middle=ModelStateResolver.middleState(captured);
        for (ArcRibbonBlockEntity ribbon:component.members()) {
            ribbon.setData(middle,new ArrayList<>(ribbon.getPrisms()),new ArrayList<>(ribbon.getSurfaces()));
            world.updateListeners(ribbon.getPos(),world.getBlockState(ribbon.getPos()),world.getBlockState(ribbon.getPos()),3);
        }
        player.sendMessage(Text.literal("整段模型弧已替换为："+Registries.BLOCK.getId(captured.getBlock())+"（"+component.members().size()+" 个弧承载方块）"),false);
    }

    private static void skinEndpoint(World world,BlockPos pos,BlockState captured,Direction direction,
                                     boolean terminalEnd,ArcModelFrameLayout.Layout layout,
                                     int lateralTiles,int verticalTiles){
        if(!(world.getBlockEntity(pos) instanceof ModelBlockEntity model))return;
        model.setTerminalEnd(terminalEnd);
        if(layout!=null) model.setSeamLayout(layout,lateralTiles,verticalTiles);
        model.setSkin(captured,direction,model.getInnerArcDirection());
        model.onNeighborChanged();
        // Re-skinning an already-skinned ModelBlock does not change its holder BlockState, so the
        // vanilla/modded neighbors would otherwise receive no update.  Explicitly notify them so
        // Fence/Pane/Wall and Conquest connection code can rebuild against the represented model.
        world.updateNeighborsAlways(pos, ModelSystemMod.MODEL_BLOCK);
    }
    /** Direction from the endpoint block centre to the exact shared seam face. */
    private static Direction endpointConnectionDirection(BlockPos endpoint, Vec3d seamCenter) {
        return dominantDirection(seamCenter.subtract(Vec3d.ofCenter(endpoint)));
    }

    private static Direction dominantDirection(Vec3d vector){
        double ax=Math.abs(vector.x),ay=Math.abs(vector.y),az=Math.abs(vector.z);
        if(ay>ax&&ay>az)return vector.y>=0?Direction.UP:Direction.DOWN;
        if(ax>=az)return vector.x>=0?Direction.EAST:Direction.WEST;
        return vector.z>=0?Direction.SOUTH:Direction.NORTH;
    }
}
