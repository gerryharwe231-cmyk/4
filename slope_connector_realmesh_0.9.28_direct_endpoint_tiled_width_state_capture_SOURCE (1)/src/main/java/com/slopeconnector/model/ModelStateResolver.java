package com.slopeconnector.model;

import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.Optional;

/** Resolves captured models without discarding ordinary stair/slab orientation state. */
public final class ModelStateResolver {
    private ModelStateResolver() {}

    /**
     * Only actual connected-profile families are normalized to one straight X-running source module.
     * Ordinary directional models (stairs, trap-like shapes, logs) and slabs keep the exact captured
     * BlockState, including facing/half/type/shape.  The renderer transports that preserved source
     * orientation into the arc frame instead of rewriting it here.
     */
    public static BlockState middleState(BlockState captured) {
        if (!ConnectionStateHelper.isSupported(captured)) return captured;
        BlockState state = ConnectionStateHelper.straightState(captured);
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")) {
                state = setNamed(state, property, "x");
            } else if (name.equals("facing") || name.equals("horizontal_facing")) {
                state = setNamed(state, property, "east");
            }
        }
        return state;
    }

    /**
     * Connected endpoints need a native neighbour-aware state, but ordinary captured models must not
     * have their facing/half/type overwritten.  For ordinary models the endpoint renderer performs
     * the geometry rotation from the captured source orientation into the seam direction.
     */
    public static BlockState endpointState(BlockState captured, Direction connectionDirection,
                                           boolean terminalEnd,
                                           net.minecraft.world.BlockView world,
                                           net.minecraft.util.math.BlockPos pos) {
        if (!ConnectionStateHelper.isSupported(captured)) return captured;
        BlockState state = ConnectionStateHelper.endpointState(captured, connectionDirection, world, pos);
        if (connectionDirection == null || connectionDirection.getAxis().isVertical()) return state;
        Direction seamDirection = terminalEnd ? connectionDirection.getOpposite() : connectionDirection;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")) {
                state = setNamed(state, property, seamDirection.getAxis() == Direction.Axis.X ? "x" : "z");
            } else if (name.equals("facing") || name.equals("horizontal_facing")) {
                state = setNamed(state, property, seamDirection.getName());
            }
        }
        return state;
    }

    public static BlockState endpointState(BlockState captured, Direction connectionDirection,
                                           net.minecraft.world.BlockView world,
                                           net.minecraft.util.math.BlockPos pos) {
        return endpointState(captured, connectionDirection, false, world, pos);
    }

    public static boolean hasExplicitOrientation(BlockState state) {
        if (state == null) return false;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")
                    || name.equals("facing") || name.equals("horizontal_facing")) return true;
        }
        return false;
    }

    /**
     * Direction of +Q in the captured baked model.  EAST/WEST and SOUTH/NORTH are deliberately
     * different: this preserves the player's captured stair facing instead of reducing it to X/Z.
     */
    public static Direction longitudinalDirection(BlockState state) {
        if (state == null) return Direction.EAST;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("facing") && !name.equals("horizontal_facing")) continue;
            Direction direction = Direction.byName(valueName(state, property));
            if (direction != null && !direction.getAxis().isVertical()) return direction;
        }
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("axis") && !name.equals("horizontal_axis")) continue;
            String value = valueName(state, property);
            if (value.equals("z")) return Direction.SOUTH;
            if (value.equals("x")) return Direction.EAST;
        }
        return Direction.EAST;
    }

    public static Direction.Axis longitudinalAxis(BlockState state) {
        return longitudinalDirection(state).getAxis();
    }

    private static BlockState setNamed(BlockState state, Property<?> property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isEmpty()) return state;
        return withRaw(state, property, (Comparable<?>) parsed.get());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState withRaw(BlockState state, Property property, Comparable value) {
        return property.getValues().contains(value) ? state.with(property, value) : state;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static String valueName(BlockState state, Property property) {
        Comparable value = state.get(property);
        return property.name(value).toLowerCase(Locale.ROOT);
    }
}
