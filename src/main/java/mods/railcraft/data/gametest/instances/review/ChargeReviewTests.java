package mods.railcraft.data.gametest.instances.review;

import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.getField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.reproduced;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setupError;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import mods.railcraft.RailcraftConfig;
import mods.railcraft.api.charge.Charge;
import mods.railcraft.charge.ChargeNetworkImpl;
import mods.railcraft.charge.ChargeProviderImpl;
import mods.railcraft.charge.ChargeSavedData;
import mods.railcraft.charge.ChargeStorageBlockImpl;
import mods.railcraft.world.level.block.RailcraftBlocks;
import mods.railcraft.world.level.block.charge.BatterySpecs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Charge network findings: H8, H9, H10, H11, L2, L3, M1.
 */
final class ChargeReviewTests {

  private ChargeReviewTests() {}

  /**
   * Distance of the far away areas used by the chunk loading tests, far enough that nothing else
   * keeps those chunks loaded.
   */
  private static final int FAR = 20_000;

  static void register(ReviewTests.Registrar registrar) {
    registrar.add("h8_discovery_loads_chunks", ChargeReviewTests::h8DiscoveryLoadsChunks);
    registrar.add("h9_equal_grids_not_merged", ChargeReviewTests::h9EqualGridsNotMerged);
    registrar.add("h10_remove_charge_overdraws", ChargeReviewTests::h10RemoveChargeOverdraws);
    registrar.add("h11_split_leaves_null_grid", ChargeReviewTests::h11SplitLeavesNullGrid);
    registrar.add("l2_battery_record_always_dirty",
        ChargeReviewTests::l2BatteryRecordAlwaysDirty);
    registrar.add("l3_replaced_node_keeps_stale_spec",
        ChargeReviewTests::l3ReplacedNodeKeepsStaleSpec);
    registrar.add("m1_unloaded_node_not_evicted", ChargeReviewTests::m1UnloadedNodeNotEvicted);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  static ChargeNetworkImpl network(GameTestHelper helper) {
    return ChargeProviderImpl.DISTRIBUTION.network(helper.getLevel());
  }

  static ChargeNetworkImpl.ChargeNode node(GameTestHelper helper, BlockPos relativePos) {
    return network(helper).access(helper.absolutePos(relativePos));
  }

  static ChargeStorageBlockImpl battery(GameTestHelper helper, BlockPos relativePos) {
    return node(helper, relativePos).storage()
        .orElseThrow(() -> new IllegalStateException("No battery at " + relativePos));
  }

  static Map<BlockPos, ChargeNetworkImpl.ChargeNode> nodes(ChargeNetworkImpl network) {
    return getField(network, "nodes");
  }

  /**
   * The grid the node currently points at, without rebuilding it the way
   * {@link ChargeNetworkImpl.ChargeNode#getGrid()} does.
   */
  static ChargeNetworkImpl.ChargeGrid rawGrid(ChargeNetworkImpl.ChargeNode node) {
    return getField(node, "chargeGrid");
  }

  static Block nickelIron() {
    return RailcraftBlocks.NICKEL_IRON_BATTERY.get();
  }

  static float lossMultiplier() {
    return RailcraftConfig.SERVER.lossMultiplier.get().floatValue();
  }

  static boolean isFull(ServerLevel level, int chunkX, int chunkZ) {
    return level.getChunkSource().hasChunk(chunkX, chunkZ);
  }

  /**
   * Whether the chunk map still has a holder for the chunk, at any status.
   */
  static boolean hasHolder(ServerLevel level, int chunkX, int chunkZ) {
    return level.getChunkSource().chunkMap
        .getUpdatingChunkIfPresent(ChunkPos.pack(chunkX, chunkZ)) != null;
  }

  // ---------------------------------------------------------------------------------------------
  // H8
  // ---------------------------------------------------------------------------------------------

  private static final int H8_CHUNKS = 16;

  /**
   * H8: node discovery must not load chunks. A line of batteries runs through 16 chunks, of which
   * only the first stays force loaded. A charge network that starts from scratch (as it does after
   * a restart) and is seeded with the battery in the loaded chunk must not pull the unloaded
   * chunks back in while it discovers the rest of the line.
   *
   * <p>
   * The restart is simulated by driving a second, fresh {@link ChargeNetworkImpl} for the same
   * level directly, so the shared network other tests use is left alone. Chunks 1 and 2 stay
   * loaded as the border of the forced chunk and are not checked.
   */
  static void h8DiscoveryLoadsChunks(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(BlockPos.ZERO);
    var chunkX = SectionPos.blockToSectionCoord(origin.getX() + FAR);
    var chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
    var y = origin.getY() + 3;
    var z = SectionPos.sectionToBlockCoord(chunkZ) + 8;
    var positions = new ArrayList<BlockPos>();
    for (var x = SectionPos.sectionToBlockCoord(chunkX);
        x < SectionPos.sectionToBlockCoord(chunkX + H8_CHUNKS); x++) {
      positions.add(new BlockPos(x, y, z));
    }
    var first = positions.getFirst();
    helper.startSequence()
        .thenExecute(() -> {
          for (var i = 0; i < H8_CHUNKS; i++) {
            level.setChunkForced(chunkX + i, chunkZ, true);
          }
        })
        .thenWaitUntil(() -> {
          for (var i = 0; i < H8_CHUNKS; i++) {
            helper.assertTrue(isFull(level, chunkX + i, chunkZ), "chunk not loaded yet");
          }
        })
        .thenExecute(() -> positions.forEach(
            pos -> level.setBlock(pos, nickelIron().defaultBlockState(), Block.UPDATE_ALL)))
        .thenIdle(3)
        .thenExecute(() -> {
          var registered = positions.stream().filter(nodes(network(helper))::containsKey).count();
          if (registered != positions.size()) {
            setupError(helper, "H8", "only " + registered + " of " + positions.size()
                + " batteries registered");
          }
          for (var i = 1; i < H8_CHUNKS; i++) {
            level.setChunkForced(chunkX + i, chunkZ, false);
          }
        })
        .thenWaitUntil(() -> {
          for (var i = 3; i < H8_CHUNKS; i++) {
            helper.assertFalse(isFull(level, chunkX + i, chunkZ), "chunk still loaded");
          }
          // The far end must be gone from memory entirely, not just below FULL
          helper.assertFalse(hasHolder(level, chunkX + H8_CHUNKS - 1, chunkZ),
              "chunk still in memory");
        })
        .thenExecute(() -> {
          List<Integer> unloaded = new ArrayList<>();
          for (var i = 3; i < H8_CHUNKS; i++) {
            if (!hasHolder(level, chunkX + i, chunkZ)) {
              unloaded.add(i);
            }
          }
          // Simulated restart: a network that knows nothing yet
          var fresh = new ChargeNetworkImpl(Charge.distribution, level);
          fresh.addNode(first, level.getBlockState(first));
          for (var i = 0; i < 2 * positions.size(); i++) {
            fresh.tick();
          }
          var discovered = nodes(fresh).size();
          List<Integer> full = new ArrayList<>();
          List<Integer> reloaded = new ArrayList<>();
          for (var i = 3; i < H8_CHUNKS; i++) {
            if (isFull(level, chunkX + i, chunkZ)) {
              full.add(i);
              if (unloaded.contains(i)) {
                reloaded.add(i);
              }
            }
          }
          // Clean up
          positions.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(),
              Block.UPDATE_ALL));
          level.setChunkForced(chunkX, chunkZ, false);
          if (!full.isEmpty()) {
            reproduced(helper, "H8", "a fresh charge network seeded with one battery in the only "
                + "force-loaded chunk discovered " + discovered + " of " + positions.size()
                + " batteries and synchronously brought " + full.size() + " chunks back to FULL "
                + full + " (chunk index from the forced chunk); " + reloaded.size() + " of them "
                + reloaded + " had been unloaded from memory entirely");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H9
  // ---------------------------------------------------------------------------------------------

  /**
   * H9: a battery that joins two grids of the same size must merge them into one grid.
   */
  static void h9EqualGridsNotMerged(GameTestHelper helper) {
    var left = new BlockPos(2, 1, 4);
    var middle = new BlockPos(3, 1, 4);
    var right = new BlockPos(4, 1, 4);
    helper.startSequence()
        .thenExecute(() -> {
          helper.setBlock(left, nickelIron());
          helper.setBlock(right, nickelIron());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var leftGrid = node(helper, left).getGrid();
          var rightGrid = node(helper, right).getGrid();
          if (leftGrid == rightGrid || leftGrid.size() != 1 || rightGrid.size() != 1) {
            setupError(helper, "H9", "expected two separate single battery grids, got "
                + leftGrid + " and " + rightGrid);
          }
          helper.setBlock(middle, nickelIron());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var leftGrid = node(helper, left).getGrid();
          var middleGrid = node(helper, middle).getGrid();
          var rightGrid = node(helper, right).getGrid();
          if (leftGrid != rightGrid || leftGrid != middleGrid || leftGrid.size() != 3) {
            reproduced(helper, "H9", "joining two 1-battery grids with a third battery gave "
                + "left=" + leftGrid + ", middle=" + middleGrid + ", right=" + rightGrid
                + " (expected one grid of 3)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H10
  // ---------------------------------------------------------------------------------------------

  /**
   * H10: removeCharge must remove at most the requested amount, spread over the batteries.
   */
  static void h10RemoveChargeOverdraws(GameTestHelper helper) {
    var a = new BlockPos(2, 1, 4);
    var b = new BlockPos(3, 1, 4);
    helper.startSequence()
        .thenExecute(() -> {
          helper.setBlock(a, nickelIron());
          helper.setBlock(b, nickelIron());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var node = node(helper, a);
          var grid = node.getGrid();
          if (grid.size() != 2 || grid != node(helper, b).getGrid()) {
            setupError(helper, "H10", "batteries did not form one grid: " + grid);
          }
          var batteryA = battery(helper, a);
          var batteryB = battery(helper, b);
          // Same tick, no balancing or losses in between
          batteryA.setEnergyStored(100);
          batteryB.setEnergyStored(100);
          var removed = node.removeCharge(150, false);
          var drained = 200 - batteryA.getAmountAsInt() - batteryB.getAmountAsInt();
          if (removed > 150 || drained != removed) {
            reproduced(helper, "H10", "removeCharge(150) on a grid of two batteries holding 100 "
                + "each returned " + removed + " and drained " + drained + " (expected at most "
                + "150)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H11
  // ---------------------------------------------------------------------------------------------

  /**
   * H11: after a grid is split by removing a node, the remaining nodes must be on working grids,
   * without somebody first having to call {@link ChargeNetworkImpl.ChargeNode#getGrid()}.
   */
  static void h11SplitLeavesNullGrid(GameTestHelper helper) {
    var left = new BlockPos(2, 1, 4);
    var middle = new BlockPos(3, 1, 4);
    var right = new BlockPos(4, 1, 4);
    helper.startSequence()
        .thenExecute(() -> {
          helper.setBlock(left, nickelIron());
          helper.setBlock(middle, nickelIron());
          helper.setBlock(right, nickelIron());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var grid = node(helper, left).getGrid();
          if (grid.size() != 3) {
            setupError(helper, "H11", "batteries did not form one grid: " + grid);
          }
          battery(helper, left).setEnergyStored(1000);
          battery(helper, middle).setEnergyStored(1000);
          battery(helper, right).setEnergyStored(1000);
        })
        .thenIdle(2)
        .thenExecute(() -> helper.setBlock(middle, Blocks.AIR))
        .thenIdle(3)
        .thenExecute(() -> {
          // access() doesn't rebuild the grid, only getGrid() does
          var leftNode = node(helper, left);
          var rightNode = node(helper, right);
          var leftNull = leftNode.isGridNull();
          var rightNull = rightNode.isGridNull();
          var leftCapacity = leftNode.hasCapacity(1);
          var rightCapacity = rightNode.hasCapacity(1);
          var leftEnergy = leftNode.storage().map(ChargeStorageBlockImpl::getAmountAsInt)
              .orElse(-1);
          if (leftNull || rightNull || !leftCapacity || !rightCapacity) {
            reproduced(helper, "H11", "3 ticks after removing the middle of 3 charged batteries: "
                + "left grid null=" + leftNull + ", right grid null=" + rightNull
                + ", left hasCapacity(1)=" + leftCapacity + ", right hasCapacity(1)="
                + rightCapacity + " although the left battery holds " + leftEnergy);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L2
  // ---------------------------------------------------------------------------------------------

  /**
   * L2: a grid tick that doesn't change any battery must not mark the charge saved data dirty.
   *
   * <p>
   * Other tests in the same level tick their own grids, so this calls the private
   * {@code ChargeGrid#tick()} of this test's grid directly, within a single test step.
   */
  static void l2BatteryRecordAlwaysDirty(GameTestHelper helper) {
    var pos = new BlockPos(4, 1, 4);
    helper.startSequence()
        .thenExecute(() -> helper.setBlock(pos, RailcraftBlocks.ZINC_SILVER_BATTERY.get()))
        .thenIdle(5)
        .thenExecute(() -> {
          var grid = node(helper, pos).getGrid();
          if (grid.size() != 1 || grid.getLosses() != 0) {
            setupError(helper, "L2", "expected one lossless battery, got " + grid
                + " with losses " + grid.getLosses());
          }
          var data = ChargeSavedData.getFor(helper.getLevel());
          Object2IntMap<BlockPos> records = getField(data, "chargeLevels");
          var absolutePos = helper.absolutePos(pos);
          var before = records.getInt(absolutePos);
          var energyBefore = battery(helper, pos).getAmountAsInt();
          data.setDirty(false);
          try {
            var tick = ChargeNetworkImpl.ChargeGrid.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            tick.invoke(grid);
          } catch (NoSuchMethodException | IllegalAccessException
              | InvocationTargetException e) {
            throw new IllegalStateException(e);
          }
          var after = records.getInt(absolutePos);
          var energyAfter = battery(helper, pos).getAmountAsInt();
          if (before != after || energyBefore != energyAfter) {
            setupError(helper, "L2", "battery changed during the tick: " + before + " -> "
                + after);
          }
          if (data.isDirty()) {
            reproduced(helper, "L2", "one tick of a grid with a single lossless battery whose "
                + "record stayed at " + after + " marked the charge saved data dirty");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L3
  // ---------------------------------------------------------------------------------------------

  /**
   * L3: replacing a battery with a different kind of battery must replace its node in the grid,
   * including the losses it contributes.
   */
  static void l3ReplacedNodeKeepsStaleSpec(GameTestHelper helper) {
    var replaced = new BlockPos(2, 1, 4);
    var neighbour = new BlockPos(3, 1, 4);
    helper.startSequence()
        .thenExecute(() -> {
          helper.setBlock(replaced, nickelIron());
          helper.setBlock(neighbour, nickelIron());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var grid = node(helper, replaced).getGrid();
          if (grid.size() != 2) {
            setupError(helper, "L3", "batteries did not form one grid: " + grid);
          }
          helper.setBlock(replaced, RailcraftBlocks.NICKEL_ZINC_BATTERY.get());
        })
        .thenIdle(3)
        .thenExecute(() -> {
          var current = node(helper, replaced);
          var grid = current.getGrid();
          var stale = new ArrayList<String>();
          for (var member : grid) {
            BlockPos memberPos = getField(member, "pos");
            if (!member.isValid() || member != network(helper).access(memberPos)) {
              stale.add(member.toString());
            }
          }
          var expectedLosses = (BatterySpecs.NICKEL_ZINC.getLosses()
              + BatterySpecs.NICKEL_IRON.getLosses()) * lossMultiplier();
          var losses = grid.getLosses();
          if (!stale.isEmpty() || Math.abs(losses - expectedLosses) > 1.0E-4F) {
            reproduced(helper, "L3", "after replacing a nickel-iron battery (losses 0.3) with a "
                + "nickel-zinc one (0.2) the grid reports losses " + losses + " instead of "
                + expectedLosses + " and still contains " + stale.size() + " stale node(s) "
                + stale);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M1
  // ---------------------------------------------------------------------------------------------

  /**
   * M1: once the chunk of a node unloads, the network should forget the node instead of ticking
   * its grid forever.
   */
  static void m1UnloadedNodeNotEvicted(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(BlockPos.ZERO);
    var chunkX = SectionPos.blockToSectionCoord(origin.getX());
    var chunkZ = SectionPos.blockToSectionCoord(origin.getZ() + FAR);
    var pos = new BlockPos(SectionPos.sectionToBlockCoord(chunkX) + 8, origin.getY() + 3,
        SectionPos.sectionToBlockCoord(chunkZ) + 8);
    helper.startSequence()
        .thenExecute(() -> level.setChunkForced(chunkX, chunkZ, true))
        .thenWaitUntil(() -> helper.assertTrue(isFull(level, chunkX, chunkZ), "not loaded yet"))
        .thenExecute(() -> level.setBlock(pos, nickelIron().defaultBlockState(), Block.UPDATE_ALL))
        .thenIdle(3)
        .thenExecute(() -> {
          if (!nodes(network(helper)).containsKey(pos)) {
            setupError(helper, "M1", "battery was not registered");
          }
          level.setChunkForced(chunkX, chunkZ, false);
        })
        .thenWaitUntil(() -> {
          helper.assertFalse(isFull(level, chunkX, chunkZ), "still loaded");
          helper.assertFalse(hasHolder(level, chunkX, chunkZ), "still in memory");
        })
        .thenIdle(5)
        .thenExecute(() -> {
          var network = network(helper);
          var node = nodes(network).get(pos);
          Set<ChargeNetworkImpl.ChargeGrid> grids = getField(network, "grids");
          // Identity scan: ChargeGrid hashes by content, so Set#contains can miss it
          var gridTicking = node != null
              && grids.stream().anyMatch(grid -> grid == rawGrid(node));
          // Clean up without touching the unloaded chunk
          network.removeNode(pos);
          if (node != null) {
            reproduced(helper, "M1", "the chunk of " + pos + " is unloaded (no chunk holder left) "
                + "but the network still holds its node " + node + " on " + rawGrid(node)
                + " (grid in the ticking set: " + gridTicking + ")");
          }
        })
        .thenSucceed();
  }
}
