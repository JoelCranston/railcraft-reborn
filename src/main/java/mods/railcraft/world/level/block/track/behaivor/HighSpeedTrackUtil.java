package mods.railcraft.world.level.block.track.behaivor;

import org.jspecify.annotations.Nullable;
import mods.railcraft.RailcraftConfig;
import mods.railcraft.api.track.RailShapeUtil;
import mods.railcraft.api.track.TrackType;
import mods.railcraft.api.track.TrackUtil;
import mods.railcraft.api.track.TypedTrack;
import mods.railcraft.world.entity.vehicle.MinecartUtil;
import mods.railcraft.world.level.block.track.TrackTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

public final class HighSpeedTrackUtil {

  private static final int LOOK_AHEAD_DIST = 2;
  private static final float SPEED_SLOPE = 0.45F;

  public static double getMaxSpeed(Level level, @Nullable AbstractMinecart cart, BlockPos pos) {
    var railShape = TrackUtil.getTrackDirection(level, pos, cart);
    return railShape.isSlope()
        ? SPEED_SLOPE
        : speedForNextTrack(level, pos, railShape, null, 0, cart,
            RailcraftConfig.SERVER.highSpeedTrackMaxSpeed.get());
  }

  public static void checkSafetyAndExplode(Level level, BlockPos pos, AbstractMinecart cart) {
    if (!isTrackSafeForHighSpeed(level, pos, cart)) {
      MinecartUtil.explodeCart(cart);
    }
  }

  public static boolean isTrackSafeForHighSpeed(Level level, BlockPos pos, AbstractMinecart cart) {
    if (!isHighSpeedTrackAt(level, pos)) {
      return false;
    }
    var railShape = TrackUtil.getTrackDirection(level, pos, cart);
    if (RailShapeUtil.isTurn(railShape)) {
      return false;
    }
    if (RailShapeUtil.isNorthSouth(railShape)) {
      BlockPos north = pos.north();
      BlockPos south = pos.south();
      return (isTrackHighSpeedCapable(level, north) || isTrackHighSpeedCapable(level, north.above())
          || isTrackHighSpeedCapable(level, north.below()))
          && (isTrackHighSpeedCapable(level, south) || isTrackHighSpeedCapable(level, south.above())
              || isTrackHighSpeedCapable(level, south.below()));
    } else if (RailShapeUtil.isEastWest(railShape)) {
      BlockPos east = pos.east();
      BlockPos west = pos.west();
      return (isTrackHighSpeedCapable(level, east) || isTrackHighSpeedCapable(level, east.above())
          || isTrackHighSpeedCapable(level, east.below()))
          && (isTrackHighSpeedCapable(level, west) || isTrackHighSpeedCapable(level, west.above())
              || isTrackHighSpeedCapable(level, west.below()));
    }
    return false;
  }

  private static boolean isTrackHighSpeedCapable(Level level, BlockPos pos) {
    return !level.isLoaded(pos) || isHighSpeedTrackAt(level, pos);
  }

  private static boolean isHighSpeedTrackAt(BlockGetter level, BlockPos pos) {
    return getTrackTypeAt(level, pos, level.getBlockState(pos)).isHighSpeed();
  }

  private static TrackType getTrackTypeAt(BlockGetter level, BlockPos pos, BlockState state) {
    return state.getBlock() instanceof TypedTrack typedTrack
        ? typedTrack.getTrackType()
        : TrackTypes.IRON.get();
  }

  /**
   * Looks ahead along the track's own exits for a slope, which caps the speed.
   *
   * @param cameFrom - the direction the search arrived from, it isn't searched again
   */
  private static double speedForNextTrack(Level level, BlockPos pos, RailShape railShape,
      @Nullable Direction cameFrom, int dist, @Nullable AbstractMinecart cart, double maxSpeed) {
    if (dist >= LOOK_AHEAD_DIST) {
      return maxSpeed;
    }

    for (var direction : exits(railShape)) {
      if (direction == cameFrom) {
        continue;
      }
      var nextPos = pos.relative(direction);
      var foundTrack = BaseRailBlock.isRail(level, nextPos);
      if (!foundTrack) {
        if (BaseRailBlock.isRail(level, nextPos.above())) {
          foundTrack = true;
          nextPos = nextPos.above();
        } else if (BaseRailBlock.isRail(level, nextPos.below())) {
          foundTrack = true;
          nextPos = nextPos.below();
        }
      }
      if (foundTrack) {
        var nextShape = TrackUtil.getTrackDirection(level, nextPos, cart);
        if (nextShape.isSlope()
            || speedForNextTrack(level, nextPos, nextShape, direction.getOpposite(), dist + 1,
                cart, maxSpeed) == SPEED_SLOPE) {
          return SPEED_SLOPE;
        }
      }
    }

    return maxSpeed;
  }

  /**
   * The directions a cart can leave a track with the given shape.
   */
  private static Direction[] exits(RailShape railShape) {
    return switch (railShape) {
      case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH ->
          new Direction[] {Direction.NORTH, Direction.SOUTH};
      case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST ->
          new Direction[] {Direction.EAST, Direction.WEST};
      case SOUTH_EAST -> new Direction[] {Direction.SOUTH, Direction.EAST};
      case SOUTH_WEST -> new Direction[] {Direction.SOUTH, Direction.WEST};
      case NORTH_WEST -> new Direction[] {Direction.NORTH, Direction.WEST};
      case NORTH_EAST -> new Direction[] {Direction.NORTH, Direction.EAST};
    };
  }
}
