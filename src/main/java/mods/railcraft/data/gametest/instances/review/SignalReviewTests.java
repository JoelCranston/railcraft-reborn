package mods.railcraft.data.gametest.instances.review;

import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.getField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.reproduced;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setupError;

import java.util.Collection;
import mods.railcraft.api.track.TrackScanUtil;
import mods.railcraft.world.item.SignalBlockSurveyorItem;
import mods.railcraft.world.level.block.RailcraftBlocks;
import mods.railcraft.world.level.block.entity.signal.BlockSignalRelayBoxBlockEntity;
import mods.railcraft.world.level.block.entity.signal.SignalCapacitorBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Signal findings: H4, H5, M5, M6.
 */
final class SignalReviewTests {

  private SignalReviewTests() {}

  static void register(ReviewTests.Registrar registrar) {
    registrar.add("h4_signal_peer_loads_chunk", SignalReviewTests::h4SignalPeerLoadsChunk);
    registrar.add("h5_relay_box_cycle_overflows", SignalReviewTests::h5RelayBoxCycleOverflows);
    registrar.add("m5_track_scan_loads_chunk", SignalReviewTests::m5TrackScanLoadsChunk);
    registrar.add("m6_capacitor_wraps_after_idle", SignalReviewTests::m6CapacitorWrapsAfterIdle);
    registrar.add("m4_token_ring_forgets_unloaded_cart",
        SignalReviewTests::m4TokenRingForgetsUnloadedCart);
  }

  private static void rails(GameTestHelper helper, int z) {
    for (var x = 0; x <= 8; x++) {
      helper.setBlock(new BlockPos(x, 1, z), Blocks.RAIL);
    }
  }

  private static BlockSignalRelayBoxBlockEntity relayBox(GameTestHelper helper, BlockPos pos) {
    return helper.getBlockEntity(pos, BlockSignalRelayBoxBlockEntity.class);
  }

  // ---------------------------------------------------------------------------------------------
  // H4
  // ---------------------------------------------------------------------------------------------

  /**
   * H4: a signal whose peer is in an unloaded chunk must not load that chunk from its tick. The
   * pairing is injected into the saved peer list, which is what a pairing across a long line
   * looks like once the far end unloads.
   */
  static void h4SignalPeerLoadsChunk(GameTestHelper helper) {
    rails(helper, 1);
    var boxPos = new BlockPos(0, 1, 0);
    helper.setBlock(boxPos, RailcraftBlocks.SIGNAL_BLOCK_RELAY_BOX.get());
    var farPeer = new BlockPos[1];
    helper.startSequence()
        .thenIdle(1)
        .thenExecute(() -> {
          farPeer[0] = helper.absolutePos(boxPos).offset(70_000, 0, 70_000);
          if (NetworkReviewTests.isFullChunkLoaded(helper, farPeer[0])) {
            setupError(helper, "H4", "far chunk already loaded");
          }
          var network = relayBox(helper, boxPos).signalNetwork();
          Collection<BlockPos> peers = getField(network, "peers");
          peers.add(farPeer[0]);
        })
        // Aspect updates run every 4 ticks
        .thenIdle(10)
        .thenExecute(() -> {
          var network = relayBox(helper, boxPos).signalNetwork();
          var loaded = NetworkReviewTests.isFullChunkLoaded(helper, farPeer[0]);
          var kept = network.peers().contains(farPeer[0]);
          Collection<BlockPos> peers = getField(network, "peers");
          peers.remove(farPeer[0]);
          if (loaded) {
            reproduced(helper, "H4", "a relay box paired with a signal ~99000 blocks away "
                + "loaded the peer's chunk from its server tick (peer still listed: " + kept
                + ")", boxPos);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H5
  // ---------------------------------------------------------------------------------------------

  /**
   * H5: three relay boxes paired into a loop must not recurse forever. Pairing, the aspect
   * calculation and the clean up happen in one step so that the boxes' own ticks never see the
   * loop (a StackOverflowError in a block entity tick would crash the test server).
   */
  static void h5RelayBoxCycleOverflows(GameTestHelper helper) {
    rails(helper, 1);
    var posA = new BlockPos(0, 1, 0);
    var posB = new BlockPos(4, 1, 0);
    var posC = new BlockPos(8, 1, 0);
    helper.setBlock(posA, RailcraftBlocks.SIGNAL_BLOCK_RELAY_BOX.get());
    helper.setBlock(posB, RailcraftBlocks.SIGNAL_BLOCK_RELAY_BOX.get());
    helper.setBlock(posC, RailcraftBlocks.SIGNAL_BLOCK_RELAY_BOX.get());
    helper.startSequence()
        .thenIdle(1)
        .thenExecute(() -> {
          var a = relayBox(helper, posA);
          var b = relayBox(helper, posB);
          var c = relayBox(helper, posC);
          Throwable thrown = null;
          boolean ab = false;
          boolean bc = false;
          boolean ca = false;
          try {
            ab = SignalBlockSurveyorItem.tryLinking(a, b);
            bc = SignalBlockSurveyorItem.tryLinking(b, c);
            ca = SignalBlockSurveyorItem.tryLinking(c, a);
            if (ab && bc && ca) {
              a.signalNetwork().aspectExcluding(BlockPos.ZERO);
            }
          } catch (Throwable t) {
            thrown = t;
          } finally {
            for (var box : new BlockSignalRelayBoxBlockEntity[] {a, b, c}) {
              Collection<BlockPos> peers = getField(box.signalNetwork(), "peers");
              peers.clear();
            }
          }
          if (!(ab && bc && ca) && thrown == null) {
            setupError(helper, "H5", "could not pair the loop: A-B " + ab + ", B-C " + bc
                + ", C-A " + ca);
          }
          if (thrown != null) {
            reproduced(helper, "H5", "the surveyor pairing accepted A-B, B-C and C-A ("
                + ab + ", " + bc + ", " + ca + ") and the next aspect calculation threw "
                + thrown.getClass().getSimpleName(), posA);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M5
  // ---------------------------------------------------------------------------------------------

  /**
   * M5: scanning a straight track section that runs into an unloaded chunk should report
   * UNKNOWN without loading it.
   */
  static void m5TrackScanLoadsChunk(GameTestHelper helper) {
    helper.startSequence()
        .thenExecute(() -> {
          var start = helper.absolutePos(new BlockPos(0, 1, 0)).offset(80_000, 0, 80_000);
          var end = start.offset(32, 0, 0);
          if (NetworkReviewTests.isFullChunkLoaded(helper, start)) {
            setupError(helper, "M5", "far chunk already loaded");
          }
          var result = TrackScanUtil.scanStraightTrackSection(helper.getLevel(), start, end);
          var loaded = NetworkReviewTests.isFullChunkLoaded(helper, start);
          if (loaded || !result.status().unknown()) {
            reproduced(helper, "M5", "scanning a section in an unloaded chunk returned "
                + result.status() + " and left the chunk loaded: " + loaded);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M6
  // ---------------------------------------------------------------------------------------------

  /**
   * M6: an idle signal capacitor box must stay unpowered. The short counter keeps decrementing
   * while idle; setting it to Short.MIN_VALUE is the state after 32768 idle ticks (~27 minutes).
   */
  static void m6CapacitorWrapsAfterIdle(GameTestHelper helper) {
    var pos = new BlockPos(4, 1, 4);
    helper.setBlock(pos, RailcraftBlocks.SIGNAL_CAPACITOR_BOX.get());
    var idleValue = new short[1];
    helper.startSequence()
        .thenIdle(10)
        .thenExecute(() -> {
          var box = helper.getBlockEntity(pos, SignalCapacitorBoxBlockEntity.class);
          idleValue[0] = getField(box, "ticksPowered");
          setField(box, "ticksPowered", Short.MIN_VALUE);
        })
        .thenIdle(2)
        .thenExecute(() -> {
          var box = helper.getBlockEntity(pos, SignalCapacitorBoxBlockEntity.class);
          short ticksPowered = getField(box, "ticksPowered");
          var signal = box.getRedstoneSignal(Direction.NORTH);
          if (idleValue[0] < 0 || signal > 0) {
            reproduced(helper, "M6", "idle capacitor counter was " + idleValue[0]
                + " after 10 idle ticks; after 32768 idle ticks it wrapped to " + ticksPowered
                + " and outputs redstone " + signal, pos);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M4
  // ---------------------------------------------------------------------------------------------

  /**
   * M4: a token ring must keep counting a cart inside its section when the cart's chunk unloads.
   * The cart is removed with UNLOADED_TO_CHUNK, which is what the entity search sees when the
   * chunk it is in unloads.
   */
  static void m4TokenRingForgetsUnloadedCart(GameTestHelper helper) {
    var signalPos = new BlockPos(4, 1, 4);
    helper.setBlock(signalPos, RailcraftBlocks.TOKEN_SIGNAL_BOX.get());
    var cart = helper.spawn(net.minecraft.world.entity.EntityType.MINECART, new BlockPos(2, 1, 2));
    var cartId = cart.getUUID();
    helper.startSequence()
        .thenIdle(1)
        .thenExecute(() -> {
          var ring = helper.getBlockEntity(signalPos,
              mods.railcraft.world.level.block.entity.signal.TokenSignalBoxBlockEntity.class)
              .signalNetwork();
          ring.markCart(cart);
          if (!ring.getTrackedCarts().contains(cartId)) {
            setupError(helper, "M4", "cart was not tracked");
          }
          cart.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        })
        // The ring manager prunes every 32 ticks
        .thenIdle(40)
        .thenExecute(() -> {
          var ring = helper.getBlockEntity(signalPos,
              mods.railcraft.world.level.block.entity.signal.TokenSignalBoxBlockEntity.class)
              .signalNetwork();
          if (!ring.getTrackedCarts().contains(cartId)) {
            reproduced(helper, "M4", "the token ring dropped a cart that was in its section "
                + "when the cart's chunk unloaded; it is now tracking "
                + ring.getTrackedCarts().size() + " carts", signalPos);
          }
        })
        .thenSucceed();
  }
}
