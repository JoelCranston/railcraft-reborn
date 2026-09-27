package mods.railcraft.data.gametest.instances.review;

import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.reproduced;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setupError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import io.netty.buffer.Unpooled;
import mods.railcraft.api.carts.RollingStock;
import mods.railcraft.api.signal.SignalAspect;
import mods.railcraft.network.to_server.EditRoutingTableBookMessage;
import mods.railcraft.network.to_server.SetActionSignalBoxMessage;
import mods.railcraft.network.to_server.SetAnalogSignalControllerBoxMessage;
import mods.railcraft.network.to_server.SetEmbarkingTrackMessage;
import mods.railcraft.network.to_server.SetFluidManipulatorMessage;
import mods.railcraft.network.to_server.SetItemDetectorMessage;
import mods.railcraft.network.to_server.SetItemManipulatorMessage;
import mods.railcraft.network.to_server.SetLauncherTrackMessage;
import mods.railcraft.network.to_server.SetRoutingDetectorMessage;
import mods.railcraft.network.to_server.SetRoutingTrackMessage;
import mods.railcraft.network.to_server.SetSignalCapacitorBoxMessage;
import mods.railcraft.network.to_server.SetSignalControllerBoxMessage;
import mods.railcraft.network.to_server.SetSwitchTrackMotorMessage;
import mods.railcraft.network.to_server.SetSwitchTrackRouterMessage;
import mods.railcraft.network.to_server.SetTankDetectorMessage;
import mods.railcraft.network.to_server.SetTrainDetectorMessage;
import mods.railcraft.util.routing.RouterBlockEntity;
import mods.railcraft.util.routing.expression.condition.DestCondition;
import mods.railcraft.world.entity.RailcraftEntityTypes;
import mods.railcraft.world.item.RailcraftItems;
import mods.railcraft.world.level.block.RailcraftBlocks;
import mods.railcraft.world.level.block.entity.LockableSwitchTrackActuatorBlockEntity;
import mods.railcraft.world.level.block.entity.SwitchTrackRouterBlockEntity;
import mods.railcraft.world.level.block.entity.detector.ItemDetectorBlockEntity;
import mods.railcraft.world.level.block.entity.detector.TankDetectorBlockEntity;
import mods.railcraft.world.level.block.entity.detector.TrainDetectorBlockEntity;
import mods.railcraft.world.level.block.entity.manipulator.ManipulatorBlockEntity;
import mods.railcraft.world.level.block.entity.signal.LockableSignalBoxBlockEntity;
import mods.railcraft.world.level.block.entity.signal.SignalCapacitorBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Networking findings: C3, H6, H7, plus a control test for the packet spy.
 */
final class NetworkReviewTests {

  private NetworkReviewTests() {}

  static void register(ReviewTests.Registrar registrar) {
    registrar.add("control_spy_ignores_tracked_updates",
        NetworkReviewTests::controlSpyIgnoresTrackedUpdates);
    registrar.add("c3_handlers_load_far_chunks", NetworkReviewTests::c3HandlersLoadFarChunks);
    registrar.add("c3_remote_edit_without_menu", NetworkReviewTests::c3RemoteEditWithoutMenu);
    registrar.add("h6_book_too_many_pages", NetworkReviewTests::h6BookTooManyPages);
    registrar.add("h6_book_oversized_page", NetworkReviewTests::h6BookOversizedPage);
    registrar.add("h7_routing_regex_redos", NetworkReviewTests::h7RoutingRegexRedos);
    registrar.add("l9_unbounded_packet_fields", NetworkReviewTests::l9UnboundedPacketFields);
  }

  static boolean isFullChunkLoaded(GameTestHelper helper, BlockPos absolutePos) {
    var chunkPos = ChunkPos.containing(absolutePos);
    return helper.getLevel().getChunkSource().hasChunk(chunkPos.x(), chunkPos.z());
  }

  /**
   * Control: vanilla's own block update path only reaches tracking players, so the spy player
   * receives nothing. Shows the packet counts in the other tests are real leaks.
   */
  static void controlSpyIgnoresTrackedUpdates(GameTestHelper helper) {
    var pos = new BlockPos(4, 1, 4);
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          helper.setBlock(pos, Blocks.CHEST);
          var absolutePos = helper.absolutePos(pos);
          var state = helper.getLevel().getBlockState(absolutePos);
          spy.clear();
          helper.getLevel().sendBlockUpdated(absolutePos, state, state, 3);
        })
        .thenIdle(2)
        .thenExecute(() -> {
          spy.remove();
          var absolutePos = helper.absolutePos(pos);
          var blockUpdates = 0L;
          synchronized (spy.packets) {
            blockUpdates = spy.packets.stream()
                .filter(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket.class
                    ::isInstance)
                .map(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket.class::cast)
                .filter(p -> p.getPos().equals(absolutePos))
                .count();
          }
          var blockEntityPackets = spy.blockEntityPackets(absolutePos);
          if (spy.isTracking(absolutePos) || blockEntityPackets > 0 || blockUpdates > 0) {
            helper.fail(net.minecraft.network.chat.Component.literal("Spy player received "
                + blockEntityPackets + " block entity and " + blockUpdates
                + " block update packets for the chest from vanilla's tracked update path"));
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // C3
  // ---------------------------------------------------------------------------------------------

  private record Handler(String name, Function<BlockPos, Object> message,
      BiConsumer<Object, IPayloadContext> handle) {}

  @SuppressWarnings("unchecked")
  private static <T> Handler handler(String name, Function<BlockPos, T> message,
      BiConsumer<T, IPayloadContext> handle) {
    return new Handler(name, (Function<BlockPos, Object>) message,
        (BiConsumer<Object, IPayloadContext>) handle);
  }

  private static List<Handler> blockPosHandlers() {
    return List.of(
        handler("SetActionSignalBox", pos -> new SetActionSignalBoxMessage(pos,
            EnumSet.noneOf(SignalAspect.class), LockableSignalBoxBlockEntity.Lock.values()[0]),
            SetActionSignalBoxMessage::handle),
        handler("SetAnalogSignalControllerBox",
            pos -> new SetAnalogSignalControllerBoxMessage(pos, new HashMap<>()),
            SetAnalogSignalControllerBoxMessage::handle),
        handler("SetEmbarkingTrack", pos -> new SetEmbarkingTrackMessage(pos, 1),
            SetEmbarkingTrackMessage::handle),
        handler("SetFluidManipulator", pos -> new SetFluidManipulatorMessage(pos,
            ManipulatorBlockEntity.RedstoneMode.values()[0]), SetFluidManipulatorMessage::handle),
        handler("SetItemDetector", pos -> new SetItemDetectorMessage(pos,
            ItemDetectorBlockEntity.PrimaryMode.values()[0],
            ItemDetectorBlockEntity.FilterMode.values()[0]), SetItemDetectorMessage::handle),
        handler("SetItemManipulator", pos -> new SetItemManipulatorMessage(pos,
            ManipulatorBlockEntity.RedstoneMode.values()[0],
            ManipulatorBlockEntity.TransferMode.values()[0]), SetItemManipulatorMessage::handle),
        handler("SetLauncherTrack", pos -> new SetLauncherTrackMessage(pos, (byte) 5),
            SetLauncherTrackMessage::handle),
        handler("SetRoutingDetector", pos -> new SetRoutingDetectorMessage(pos,
            RouterBlockEntity.Railway.values()[0], SwitchTrackRouterBlockEntity.Lock.values()[0]),
            SetRoutingDetectorMessage::handle),
        handler("SetRoutingTrack", pos -> new SetRoutingTrackMessage(pos,
            LockableSwitchTrackActuatorBlockEntity.Lock.values()[0]),
            SetRoutingTrackMessage::handle),
        handler("SetSignalCapacitorBox", pos -> new SetSignalCapacitorBoxMessage(pos, (short) 10,
            SignalCapacitorBoxBlockEntity.Mode.values()[0]), SetSignalCapacitorBoxMessage::handle),
        handler("SetSignalControllerBox", pos -> new SetSignalControllerBoxMessage(pos,
            SignalAspect.GREEN, SignalAspect.RED), SetSignalControllerBoxMessage::handle),
        handler("SetSwitchTrackMotor", pos -> new SetSwitchTrackMotorMessage(pos,
            EnumSet.noneOf(SignalAspect.class), false,
            LockableSwitchTrackActuatorBlockEntity.Lock.values()[0]),
            SetSwitchTrackMotorMessage::handle),
        handler("SetSwitchTrackRouter", pos -> new SetSwitchTrackRouterMessage(pos,
            RouterBlockEntity.Railway.values()[0], SwitchTrackRouterBlockEntity.Lock.values()[0]),
            SetSwitchTrackRouterMessage::handle),
        handler("SetTankDetector", pos -> new SetTankDetectorMessage(pos,
            TankDetectorBlockEntity.Mode.values()[0]), SetTankDetectorMessage::handle),
        handler("SetTrainDetector", pos -> new SetTrainDetectorMessage(pos, 5),
            SetTrainDetectorMessage::handle));
  }

  /**
   * C3: a serverbound packet naming a position in an unloaded chunk must not load (or generate)
   * that chunk. Each handler gets its own far away chunk.
   */
  static void c3HandlersLoadFarChunks(GameTestHelper helper) {
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          var context = ReviewTestUtil.payloadContext(spy.player);
          var base = helper.absolutePos(BlockPos.ZERO).offset(60_000, 0, 60_000);
          var loaded = new ArrayList<String>();
          var skipped = 0;
          var start = System.nanoTime();
          var handlers = blockPosHandlers();
          for (var i = 0; i < handlers.size(); i++) {
            var handler = handlers.get(i);
            var pos = base.offset(i * 64, 0, 0);
            if (isFullChunkLoaded(helper, pos)) {
              skipped++;
              continue;
            }
            try {
              handler.handle().accept(handler.message().apply(pos), context);
            } catch (RuntimeException e) {
              // Only interested in the chunk side effect
            }
            if (isFullChunkLoaded(helper, pos)) {
              loaded.add(handler.name());
            }
          }
          var millis = (System.nanoTime() - start) / 1_000_000;
          spy.remove();
          if (skipped == handlers.size()) {
            setupError(helper, "C3", "all target chunks were already loaded");
          }
          if (!loaded.isEmpty()) {
            reproduced(helper, "C3", loaded.size() + " of " + (handlers.size() - skipped)
                + " serverbound handlers loaded a chunk ~85000 blocks from the sender on the "
                + "server thread (" + millis + " ms total): " + String.join(", ", loaded));
          }
        })
        .thenSucceed();
  }

  /**
   * C3: a player without the machine's menu open, far away, must not be able to change it.
   */
  static void c3RemoteEditWithoutMenu(GameTestHelper helper) {
    var pos = new BlockPos(4, 1, 4);
    helper.setBlock(pos, RailcraftBlocks.TRAIN_DETECTOR.get());
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          var detector = helper.getBlockEntity(pos, TrainDetectorBlockEntity.class);
          var before = detector.getTrainSize();
          var absolutePos = helper.absolutePos(pos);
          SetTrainDetectorMessage.handle(new SetTrainDetectorMessage(absolutePos, before + 4),
              ReviewTestUtil.payloadContext(spy.player));
          var distance = (int) spy.player.position()
              .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(absolutePos));
          var menu = spy.player.containerMenu.getClass().getSimpleName();
          spy.remove();
          if (detector.getTrainSize() != before) {
            reproduced(helper, "C3", "a player " + distance + " blocks away with only its "
                + menu + " open changed the train detector's size from " + before + " to "
                + detector.getTrainSize(), pos);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H6
  // ---------------------------------------------------------------------------------------------

  private static List<String> pages(int count, int length) {
    return new ArrayList<>(Collections.nCopies(count, "a".repeat(length)));
  }

  /**
   * H6: a routing book edit with more pages than a book can hold should be rejected gracefully,
   * not throw on the server thread (which disconnects the sender).
   */
  static void h6BookTooManyPages(GameTestHelper helper) {
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          spy.player.setItemInHand(InteractionHand.MAIN_HAND,
              new ItemStack(RailcraftItems.ROUTING_TABLE_BOOK.get()));
          Throwable thrown = null;
          try {
            EditRoutingTableBookMessage.handle(new EditRoutingTableBookMessage(
                InteractionHand.MAIN_HAND, pages(51, 10), Optional.empty()),
                ReviewTestUtil.payloadContext(spy.player));
          } catch (Throwable t) {
            thrown = t;
          }
          spy.remove();
          if (thrown != null) {
            reproduced(helper, "H6", "a 51 page routing book edit, which the packet codec "
                + "accepts, threw " + thrown + " in the handler");
          }
        })
        .thenSucceed();
  }

  /**
   * H6: a routing book page longer than the component allows must not end up on the item, as the
   * item can then no longer be synced or saved.
   */
  static void h6BookOversizedPage(GameTestHelper helper) {
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          var level = helper.getLevel();
          spy.player.setItemInHand(InteractionHand.MAIN_HAND,
              new ItemStack(RailcraftItems.ROUTING_TABLE_BOOK.get()));
          var message = new EditRoutingTableBookMessage(InteractionHand.MAIN_HAND,
              pages(1, 2000), Optional.empty());
          // If the wire format rejects the page the packet never reaches the handler, a modified
          // client can still build one though, so the handler must reject it as well.
          var toHandle = message;
          try {
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            EditRoutingTableBookMessage.STREAM_CODEC.encode(wire, message);
            toHandle = EditRoutingTableBookMessage.STREAM_CODEC.decode(wire);
          } catch (RuntimeException e) {
            // Rejected by the codec
          }
          EditRoutingTableBookMessage.handle(toHandle, ReviewTestUtil.payloadContext(spy.player));
          var stack = spy.player.getItemInHand(InteractionHand.MAIN_HAND);
          spy.remove();

          String syncError = null;
          try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(
                new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()), stack);
          } catch (Exception e) {
            syncError = e.toString();
          }
          var saveResult = ItemStack.CODEC.encodeStart(
              level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
          var saveError = saveResult.error().map(e -> e.message()).orElse(null);
          if (syncError != null || saveError != null) {
            reproduced(helper, "H6", "a 2000 character page was stored on the book; syncing the "
                + "item fails with " + syncError + "; saving fails with " + saveError);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H7
  // ---------------------------------------------------------------------------------------------

  /**
   * H7: evaluating a player written routing regex against a player chosen destination must not
   * be able to hang the server thread. Evaluated off-thread with a time limit so a hang doesn't
   * take the test server down. The review's example, {@code (a+)+$}, is memoized by the JDK's
   * regex engine (JDK 9+), so a bounded repetition pattern is tried as well.
   */
  static void h7RoutingRegexRedos(GameTestHelper helper) {
    var locomotive = helper.spawn(RailcraftEntityTypes.CREATIVE_LOCOMOTIVE.get(),
        new BlockPos(4, 1, 4));
    var patterns = List.of("(a+)+$", "(.*a){20}$");
    var finishedNanos = new AtomicLong[] {new AtomicLong(-1), new AtomicLong(-1)};
    var startNanos = new long[1];
    var destination = "a".repeat(30) + "!";
    var threads = new ArrayList<Thread>();
    helper.startSequence()
        .thenExecute(() -> {
          locomotive.setDestination(destination);
          var rollingStock = RollingStock.getOrThrow(locomotive);
          startNanos[0] = System.nanoTime();
          for (var i = 0; i < patterns.size(); i++) {
            mods.railcraft.util.routing.expression.Expression expression;
            try {
              expression = DestCondition.parse("Dest?=" + patterns.get(i));
            } catch (mods.railcraft.util.routing.RoutingLogicException e) {
              throw new IllegalStateException(e);
            }
            var finished = finishedNanos[i];
            var thread = new Thread(() -> {
              expression.evaluate(null, rollingStock);
              finished.set(System.nanoTime());
            }, "review-h7-regex-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
          }
          // Give both up to 3 seconds of wall clock time in total
          for (var thread : threads) {
            var remaining = 3000 - (System.nanoTime() - startNanos[0]) / 1_000_000;
            try {
              thread.join(Math.max(1, remaining));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        })
        .thenExecute(() -> {
          locomotive.discard();
          var results = new ArrayList<String>();
          var slow = false;
          for (var i = 0; i < patterns.size(); i++) {
            var finished = finishedNanos[i].get();
            if (finished < 0) {
              slow = true;
              results.add("'Dest?=" + patterns.get(i) + "' still running after "
                  + (System.nanoTime() - startNanos[0]) / 1_000_000 + " ms");
            } else {
              var millis = (finished - startNanos[0]) / 1_000_000;
              slow |= millis > 100;
              results.add("'Dest?=" + patterns.get(i) + "' took " + millis + " ms");
            }
          }
          if (slow) {
            reproduced(helper, "H7", "routing conditions evaluated against the 31 character "
                + "destination '" + destination + "': " + String.join("; ", results));
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L9
  // ---------------------------------------------------------------------------------------------

  /**
   * L9: serverbound values should be bounded on the server: the train detector's size and the
   * analog signal controller's trigger bit sets.
   */
  static void l9UnboundedPacketFields(GameTestHelper helper) {
    var detectorPos = new BlockPos(2, 1, 2);
    var analogPos = new BlockPos(6, 1, 6);
    helper.setBlock(detectorPos, RailcraftBlocks.TRAIN_DETECTOR.get());
    helper.setBlock(analogPos, RailcraftBlocks.ANALOG_SIGNAL_CONTROLLER_BOX.get());
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          var context = ReviewTestUtil.payloadContext(spy.player);
          SetTrainDetectorMessage.handle(
              new SetTrainDetectorMessage(helper.absolutePos(detectorPos), -1_000_000), context);
          var bits = new java.util.BitSet();
          bits.set(1_000_000);
          var map = new java.util.EnumMap<SignalAspect, java.util.BitSet>(SignalAspect.class);
          map.put(SignalAspect.GREEN, bits);
          SetAnalogSignalControllerBoxMessage.handle(
              new SetAnalogSignalControllerBoxMessage(helper.absolutePos(analogPos), map),
              context);
          spy.remove();
          var trainSize = helper.getBlockEntity(detectorPos, TrainDetectorBlockEntity.class)
              .getTrainSize();
          var analog = helper.getBlockEntity(analogPos,
              mods.railcraft.world.level.block.entity.signal
                  .AnalogSignalControllerBoxBlockEntity.class);
          var stored = analog.getSignalAspectTriggerSignals().get(SignalAspect.GREEN);
          var problems = new ArrayList<String>();
          if (trainSize < 1) {
            problems.add("train detector accepted a train size of " + trainSize);
          }
          if (stored != null && stored.length() > 16) {
            problems.add("analog controller stored a trigger bit set of length "
                + stored.length() + " (redstone has 16 levels)");
          }
          if (!problems.isEmpty()) {
            reproduced(helper, "L9", String.join("; ", problems));
          }
        })
        .thenSucceed();
  }
}
