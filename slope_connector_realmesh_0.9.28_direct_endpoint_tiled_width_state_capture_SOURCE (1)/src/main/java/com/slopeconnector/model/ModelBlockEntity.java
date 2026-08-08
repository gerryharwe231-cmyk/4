package com.slopeconnector.model;

import com.slopeconnector.MaterialStateCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public final class ModelBlockEntity extends BlockEntity {
    private BlockState capturedState = Blocks.WHITE_CONCRETE.getDefaultState();
    private BlockState displayState = Blocks.WHITE_CONCRETE.getDefaultState();
    private Direction arcDirection = Direction.NORTH;
    /** Existing arc-wand face setting, interpreted by the model renderer as the inner-arc side. */
    private Direction innerArcDirection = Direction.UP;
    private boolean skinned;
    /** True only for the terminal endpoint after the ordered arc; persisted to avoid render-time scans. */
    private boolean terminalEnd;
    /** Exact model-frame layout shared with the first/last curved station. */
    private Vec3d seamLateral = new Vec3d(0, 0, 1);
    private Vec3d seamVertical = new Vec3d(0, 1, 0);
    private double seamLateralSpan = 1.0;
    private double seamVerticalSpan = 1.0;
    private int seamLateralTiles = 1;
    private int seamVerticalTiles = 1;

    public ModelBlockEntity(BlockPos pos, BlockState state) {
        super(ModelSystemMod.MODEL_BLOCK_ENTITY, pos, state);
    }

    public boolean isSkinned() { return skinned; }
    public BlockState getCapturedState() { return capturedState; }
    public BlockState getDisplayState() { return displayState; }
    public Direction getArcDirection() { return arcDirection; }
    public Direction getInnerArcDirection() { return innerArcDirection; }
    public boolean isTerminalEnd() { return terminalEnd; }
    public Vec3d getSeamLateral() { return seamLateral; }
    public Vec3d getSeamVertical() { return seamVertical; }
    public double getSeamLateralSpan() { return seamLateralSpan; }
    public double getSeamVerticalSpan() { return seamVerticalSpan; }
    public int getSeamLateralTiles() { return seamLateralTiles; }
    public int getSeamVerticalTiles() { return seamVerticalTiles; }

    public void setSeamLayout(ArcModelFrameLayout.Layout layout,
                              int lateralTiles, int verticalTiles) {
        if (layout == null) return;
        this.seamLateral = safeAxis(layout.lateral(), new Vec3d(0, 0, 1));
        this.seamVertical = safeAxis(layout.vertical(), new Vec3d(0, 1, 0));
        this.seamLateralSpan = Math.max(1.0E-4, layout.lateralSpan());
        this.seamVerticalSpan = Math.max(1.0E-4, layout.verticalSpan());
        this.seamLateralTiles = Math.max(1, lateralTiles);
        this.seamVerticalTiles = Math.max(1, verticalTiles);
        markDirty();
    }

    public void setTerminalEnd(boolean terminalEnd) {
        this.terminalEnd = terminalEnd;
        if (skinned) refreshDisplayState();
        markDirty();
    }


    /** Stores arc orientation even while the block is still the pure-white unskinned endpoint. */
    public void setArcMetadata(Direction arcDirection, Direction innerArcDirection) {
        this.arcDirection = arcDirection == null ? Direction.NORTH : arcDirection;
        this.innerArcDirection = innerArcDirection == null ? Direction.UP : innerArcDirection;
        if (skinned) refreshDisplayState();
        markDirty();
        if (world != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    public void setSkin(BlockState captured, Direction arcDirection) {
        setSkin(captured, arcDirection, innerArcDirection);
    }

    public void setSkin(BlockState captured, Direction arcDirection, Direction innerArcDirection) {
        this.capturedState = sanitize(captured);
        this.arcDirection = arcDirection == null ? Direction.NORTH : arcDirection;
        this.innerArcDirection = innerArcDirection == null ? Direction.UP : innerArcDirection;
        this.skinned = true;
        refreshDisplayState();
        if (world != null) {
            BlockState blockState = world.getBlockState(pos);
            if (blockState.getBlock() == ModelSystemMod.MODEL_BLOCK && !blockState.get(ModelBlock.SKINNED)) {
                world.setBlockState(pos, blockState.with(ModelBlock.SKINNED, true), 3);
            }
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
        markDirty();
    }

    public void clearSkin() {
        this.skinned = false;
        this.terminalEnd = false;
        this.capturedState = Blocks.WHITE_CONCRETE.getDefaultState();
        this.displayState = this.capturedState;
        this.seamLateral = new Vec3d(0, 0, 1);
        this.seamVertical = new Vec3d(0, 1, 0);
        this.seamLateralSpan = 1.0;
        this.seamVerticalSpan = 1.0;
        this.seamLateralTiles = 1;
        this.seamVerticalTiles = 1;
        if (world != null) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() == ModelSystemMod.MODEL_BLOCK && state.get(ModelBlock.SKINNED)) {
                world.setBlockState(pos, state.with(ModelBlock.SKINNED, false), 3);
            }
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
        markDirty();
    }

    public void refreshDisplayState() {
        if (!skinned) {
            displayState = Blocks.WHITE_CONCRETE.getDefaultState();
            return;
        }
        if (world != null) {
            displayState = ModelStateResolver.endpointState(capturedState, arcDirection, terminalEnd, world, pos);
        } else {
            displayState = capturedState;
        }
        markDirty();
    }

    public void onNeighborChanged() {
        BlockState before = displayState;
        refreshDisplayState();
        if (world != null && !before.equals(displayState)) {
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    private static BlockState sanitize(BlockState state) {
        if (state == null || state.isAir() || state.getBlock() == ModelSystemMod.MODEL_BLOCK) {
            return Blocks.WHITE_CONCRETE.getDefaultState();
        }
        return state;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("Skinned", skinned);
        nbt.putBoolean("TerminalEnd", terminalEnd);
        nbt.put("CapturedState", MaterialStateCodec.write(capturedState));
        nbt.put("DisplayState", MaterialStateCodec.write(displayState));
        putVec(nbt, "SeamLateral", seamLateral);
        putVec(nbt, "SeamVertical", seamVertical);
        nbt.putDouble("SeamLateralSpan", seamLateralSpan);
        nbt.putDouble("SeamVerticalSpan", seamVerticalSpan);
        nbt.putInt("SeamLateralTiles", seamLateralTiles);
        nbt.putInt("SeamVerticalTiles", seamVerticalTiles);
        nbt.putString("ArcDirection", arcDirection.getName());
        nbt.putString("InnerArcDirection", innerArcDirection.getName());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        skinned = nbt.getBoolean("Skinned");
        terminalEnd = nbt.getBoolean("TerminalEnd");
        capturedState = nbt.contains("CapturedState")
                ? sanitize(MaterialStateCodec.read(nbt.getCompound("CapturedState")))
                : Blocks.WHITE_CONCRETE.getDefaultState();
        displayState = nbt.contains("DisplayState")
                ? sanitize(MaterialStateCodec.read(nbt.getCompound("DisplayState"))) : capturedState;
        seamLateral = getVec(nbt, "SeamLateral", new Vec3d(0, 0, 1));
        seamVertical = getVec(nbt, "SeamVertical", new Vec3d(0, 1, 0));
        seamLateralSpan = nbt.contains("SeamLateralSpan") ? Math.max(1.0E-4, nbt.getDouble("SeamLateralSpan")) : 1.0;
        seamVerticalSpan = nbt.contains("SeamVerticalSpan") ? Math.max(1.0E-4, nbt.getDouble("SeamVerticalSpan")) : 1.0;
        seamLateralTiles = nbt.contains("SeamLateralTiles") ? Math.max(1, nbt.getInt("SeamLateralTiles")) : 1;
        seamVerticalTiles = nbt.contains("SeamVerticalTiles") ? Math.max(1, nbt.getInt("SeamVerticalTiles")) : 1;
        Direction parsed = Direction.byName(nbt.getString("ArcDirection"));
        arcDirection = parsed == null ? Direction.NORTH : parsed;
        Direction inner = Direction.byName(nbt.getString("InnerArcDirection"));
        innerArcDirection = inner == null ? Direction.UP : inner;
    }


    private static Vec3d safeAxis(Vec3d value, Vec3d fallback) {
        return value == null || value.lengthSquared() < 1.0E-12 ? fallback : value.normalize();
    }

    private static void putVec(NbtCompound nbt, String key, Vec3d value) {
        nbt.putDouble(key + "X", value.x);
        nbt.putDouble(key + "Y", value.y);
        nbt.putDouble(key + "Z", value.z);
    }

    private static Vec3d getVec(NbtCompound nbt, String key, Vec3d fallback) {
        if (!nbt.contains(key + "X") || !nbt.contains(key + "Y") || !nbt.contains(key + "Z")) return fallback;
        Vec3d value = new Vec3d(nbt.getDouble(key + "X"), nbt.getDouble(key + "Y"), nbt.getDouble(key + "Z"));
        return safeAxis(value, fallback);
    }

    @Override public NbtCompound toInitialChunkDataNbt() { return createNbt(); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
