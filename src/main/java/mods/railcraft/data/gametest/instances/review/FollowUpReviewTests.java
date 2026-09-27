package mods.railcraft.data.gametest.instances.review;

import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.getField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.reproduced;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setupError;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import mods.railcraft.Railcraft;
import mods.railcraft.RailcraftConfig;
import mods.railcraft.api.carts.RollingStock;
import mods.railcraft.world.entity.RailcraftEntityTypes;
import mods.railcraft.world.entity.vehicle.EnergyMinecart;
import mods.railcraft.world.level.block.entity.worldspike.WorldSpikeBlockEntity;
import mods.railcraft.world.level.block.track.behaivor.HighSpeedTrackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Findings fixed after the first round: M8, L7 and H14's orphaned entity tickets, and a guard for L5.
 */
final class FollowUpReviewTests {

  private FollowUpReviewTests() {}

  static void register(ReviewTests.Registrar registrar) {
    registrar.add("m8_steam_locomotive_pulls_while_full",
        FollowUpReviewTests::m8SteamLocomotivePullsWhileFull);
    registrar.add("l5_advance_on_track_once_per_tick",
        FollowUpReviewTests::l5AdvanceOnTrackOncePerTick);
    registrar.add("l7_parallel_slope_caps_speed", FollowUpReviewTests::l7ParallelSlopeCapsSpeed);
    registrar.add("h14_orphaned_entity_tickets_released",
        FollowUpReviewTests::h14OrphanedEntityTicketsReleased);
  }

  private static void rails(GameTestHelper helper, int z) {
    for (var x = 0; x <= 8; x++) {
      helper.setBlock(new BlockPos(x, 1, z), Blocks.RAIL);
    }
  }

  /**
   * Places a rail with the given shape. The rail is set twice, the second time the block doesn't
   * change, so the rail doesn't reshape itself to its neighbours.
   */
  private static void setRail(GameTestHelper helper, BlockPos pos, RailShape shape) {
    var state = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
    helper.setBlock(pos, state);
    helper.getLevel().setBlock(helper.absolutePos(pos), state,
        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
  }

  /**
   * Chunks the Railcraft ticket controller force loads for the given owner, read from NeoForge's
   * forced chunk store by reflection.
   */
  private static <T> LongSet tickets(ServerLevel level, T owner, boolean blocks) {
    var result = new LongOpenHashSet();
    var storage = level.getDataStorage().get(TicketStorage.TYPE);
    if (storage == null) {
      return result;
    }
    Long2ObjectMap<Set<?>> sources = getField(
        blocks ? storage.getBlockForcedChunks() : storage.getEntityForcedChunks(),
        "sourcesLoading");
    for (var entry : Long2ObjectMaps.fastIterable(sources)) {
      for (var ticketOwner : entry.getValue()) {
        if (owner.equals(getField(ticketOwner, "owner"))
            && Railcraft.CHUNK_CONTROLLER.id().equals(getField(ticketOwner, "id"))) {
          result.add(entry.getLongKey());
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // M8
  // ---------------------------------------------------------------------------------------------

  /**
   * M8: a steam locomotive whose fuel slots are full must not search the train for fuel every
   * tick. The chest cart counts every slot read, which is what a search of its inventory does.
   * The carts don't need rails to be linked.
   */
  static void m8SteamLocomotivePullsWhileFull(GameTestHelper helper) {
    // No rails: on a rail vanilla reads the chest's slots every tick for its redstone slowdown
    var level = helper.getLevel();
    var reads = new AtomicInteger();
    var locomotive = helper.spawn(RailcraftEntityTypes.STEAM_LOCOMOTIVE.get(),
        new Vec3(2.5, 1.0625, 4.5));
    var chest = new MinecartChest(EntityType.CHEST_MINECART, level) {
      @Override
      public ItemStack getItem(int slot) {
        reads.incrementAndGet();
        return super.getItem(slot);
      }
    };
    var chestPos = helper.absoluteVec(new Vec3(4.5, 1.0625, 4.5));
    chest.setPos(chestPos);
    level.addFreshEntity(chest);
    for (var slot = 0; slot < 8; slot++) {
      chest.setItem(slot, new ItemStack(Items.COAL, 64));
    }
    var readsWhileFull = new int[1];
    helper.startSequence()
        .thenIdle(2)
        .thenExecute(() -> {
          if (!RollingStock.getOrThrow(locomotive).link(RollingStock.getOrThrow(chest))) {
            chest.discard();
            setupError(helper, "M8", "could not link the chest cart to the locomotive");
          }
        })
        // Fill the fuel slots, one item a tick moves into the fuel slot
        .thenIdle(200)
        .thenExecute(() -> reads.set(0))
        .thenIdle(40)
        .thenExecute(() -> {
          readsWhileFull[0] = reads.get();
          chest.discard();
          locomotive.discard();
          // A single search of the chest reads each of its 27 slots at least once
          if (readsWhileFull[0] > 27 * 5) {
            reproduced(helper, "M8", "a steam locomotive with full fuel slots read its chest "
                + "cart's slots " + readsWhileFull[0] + " times in 40 ticks (a search every "
                + "tick)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L5
  // ---------------------------------------------------------------------------------------------

  /**
   * L5: advanceOnTrack must run once per tick. The review suspected it ran from the mixin and
   * again from the subclasses' moveAlongTrack, but with the old minecart behaviour vanilla never
   * calls the minecart's moveAlongTrack (only the new, experimental behaviour does, which the
   * mixin doesn't patch). Kept as a guard.
   */
  static void l5AdvanceOnTrackOncePerTick(GameTestHelper helper) {
    rails(helper, 4);
    var level = helper.getLevel();
    var calls = new AtomicInteger();
    var cart = new EnergyMinecart(RailcraftEntityTypes.ENERGY_MINECART.get(), level) {
      @Override
      public void advanceOnTrack(ServerLevel serverLevel) {
        calls.incrementAndGet();
        super.advanceOnTrack(serverLevel);
      }
    };
    cart.setPos(helper.absoluteVec(new Vec3(4.5, 1.0625, 4.5)));
    level.addFreshEntity(cart);
    var ticks = 20;
    helper.startSequence()
        .thenIdle(1)
        .thenExecute(() -> calls.set(0))
        .thenIdle(ticks)
        .thenExecute(() -> {
          var count = calls.get();
          cart.discard();
          if (count == 0) {
            setupError(helper, "L5", "advanceOnTrack was never called");
          }
          if (count > ticks + 1) {
            reproduced(helper, "L5", "advanceOnTrack ran " + count + " times in " + ticks
                + " ticks on an energy cart sitting on a rail");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L7
  // ---------------------------------------------------------------------------------------------

  /**
   * L7: a slope on a parallel line next to a straight track must not cap the straight track's
   * high speed.
   */
  static void l7ParallelSlopeCapsSpeed(GameTestHelper helper) {
    for (var z = 1; z <= 7; z++) {
      setRail(helper, new BlockPos(2, 1, z), RailShape.NORTH_SOUTH);
    }
    setRail(helper, new BlockPos(3, 1, 4), RailShape.ASCENDING_NORTH);
    helper.startSequence()
        .thenExecute(() -> {
          var slope = helper.getBlockState(new BlockPos(3, 1, 4));
          if (!slope.is(Blocks.RAIL) || slope.getValue(RailBlock.SHAPE) != RailShape.ASCENDING_NORTH) {
            setupError(helper, "L7", "the parallel slope wasn't placed: " + slope);
          }
          var speed = HighSpeedTrackUtil.getMaxSpeed(helper.getLevel(), null,
              helper.absolutePos(new BlockPos(2, 1, 4)));
          var expected = RailcraftConfig.SERVER.highSpeedTrackMaxSpeed.get();
          if (speed != expected) {
            reproduced(helper, "L7", "a straight north-south track next to a parallel slope got "
                + "a max speed of " + speed + " instead of " + expected);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H14
  // ---------------------------------------------------------------------------------------------

  /**
   * H14: entity chunk tickets whose entity no longer exists are released, tickets of entities
   * which exist are kept.
   */
  static void h14OrphanedEntityTicketsReleased(GameTestHelper helper) {
    var cart = helper.spawn(EntityType.MINECART, new Vec3(4.5, 1, 4.5));
    helper.startSequence()
        .thenExecute(() -> {
          var level = helper.getLevel();
          var chunkPos = ChunkPos.containing(helper.absolutePos(new BlockPos(4, 1, 4)));
          var gone = UUID.randomUUID();
          var alive = cart.getUUID();
          for (var owner : new UUID[] {gone, alive}) {
            Railcraft.CHUNK_CONTROLLER.forceChunk(level, owner, chunkPos.x(), chunkPos.z(), true,
                false);
          }
          var chunks = new HashMap<UUID, LongSet>();
          chunks.put(gone, LongSet.of(chunkPos.pack()));
          chunks.put(alive, LongSet.of(chunkPos.pack()));
          WorldSpikeBlockEntity.RailcraftValidationTicket.releaseOrphanedEntityTickets(level,
              chunks);
          var goneTickets = tickets(level, gone, false).size();
          var aliveTickets = tickets(level, alive, false).size();
          Railcraft.CHUNK_CONTROLLER.forceChunk(level, alive, chunkPos.x(), chunkPos.z(), false,
              false);
          Railcraft.CHUNK_CONTROLLER.forceChunk(level, gone, chunkPos.x(), chunkPos.z(), false,
              false);
          cart.discard();
          if (goneTickets != 0 || aliveTickets != 1) {
            reproduced(helper, "H14", "after checking entity tickets, the missing entity still "
                + "held " + goneTickets + " tickets and the existing cart held " + aliveTickets
                + " (expected 0 and 1)");
          }
        })
        .thenSucceed();
  }
}
