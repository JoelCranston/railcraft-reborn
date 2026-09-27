package mods.railcraft.data.gametest.instances.review;

import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.getField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.reproduced;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setField;
import static mods.railcraft.data.gametest.instances.review.ReviewTestUtil.setupError;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import mods.railcraft.Railcraft;
import mods.railcraft.api.carts.RollingStock;
import mods.railcraft.api.carts.Side;
import mods.railcraft.api.carts.Train;
import mods.railcraft.api.core.CompoundTagKeys;
import mods.railcraft.network.to_client.LinkedCartsMessage;
import mods.railcraft.particle.ChunkLoaderParticleOptions;
import mods.railcraft.world.entity.RailcraftEntityTypes;
import mods.railcraft.world.entity.vehicle.RollingStockImpl;
import mods.railcraft.world.level.block.RailcraftBlocks;
import mods.railcraft.world.level.block.entity.manipulator.TrainDispenserBlockEntity;
import mods.railcraft.world.level.block.track.ElevatorTrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Cart and track findings: H12, H13, H14, M3, M7, M9, M10, M12, M13, L6.
 */
final class CartReviewTests {

  private CartReviewTests() {}

  static void register(ReviewTests.Registrar registrar) {
    registrar.add("h12_removed_locking_track_keeps_lock",
        CartReviewTests::h12RemovedLockingTrackKeepsLock);
    registrar.add("h13_link_not_resolved_after_reload",
        CartReviewTests::h13LinkNotResolvedAfterReload);
    registrar.add("h14_world_spike_cart_leaks_tickets",
        CartReviewTests::h14WorldSpikeCartLeaksTickets);
    registrar.add("m3_world_spike_particle_packets", CartReviewTests::m3WorldSpikeParticlePackets);
    registrar.add("m7_stale_unresolved_link_saved", CartReviewTests::m7StaleUnresolvedLinkSaved);
    registrar.add("m9_train_lookup_quadratic", CartReviewTests::m9TrainLookupQuadratic);
    registrar.add("m10_force_track_without_emitter",
        CartReviewTests::m10ForceTrackWithoutEmitter);
    registrar.add("m12_train_dispenser_partial_buffer",
        CartReviewTests::m12TrainDispenserPartialBuffer);
    registrar.add("m13_linked_carts_without_goggles",
        CartReviewTests::m13LinkedCartsWithoutGoggles);
    registrar.add("l6_elevator_cart_yaw", CartReviewTests::l6ElevatorCartYaw);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static AbstractMinecart spawnCart(GameTestHelper helper, double x, double y, double z) {
    return helper.spawn(EntityType.MINECART, new Vec3(x, y, z));
  }

  private static void layRails(GameTestHelper helper, int z) {
    for (var x = 0; x <= 8; x++) {
      helper.setBlock(new BlockPos(x, 1, z), Blocks.RAIL);
    }
  }

  private static TagValueOutput output(ServerLevel level) {
    return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
  }

  /**
   * Chunks the Railcraft ticket controller currently force loads on behalf of the given entity
   * UUID, read from NeoForge's forced chunk store in the level's {@link TicketStorage}.
   */
  private static LongSet entityTickets(ServerLevel level, UUID owner) {
    var result = new LongOpenHashSet();
    var storage = level.getDataStorage().get(TicketStorage.TYPE);
    if (storage == null) {
      return result;
    }
    Long2ObjectMap<Set<?>> sources = getField(storage.getEntityForcedChunks(), "sourcesLoading");
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

  private static void releaseTickets(ServerLevel level, UUID owner) {
    for (long chunk : entityTickets(level, owner)) {
      var pos = ChunkPos.unpack(chunk);
      Railcraft.CHUNK_CONTROLLER.forceChunk(level, owner, pos.x(), pos.z(), false, false);
    }
  }

  private static String chunks(LongSet set) {
    var list = new ArrayList<String>();
    for (long chunk : set) {
      var pos = ChunkPos.unpack(chunk);
      list.add("[" + pos.x() + "," + pos.z() + "]");
    }
    return String.join(" ", list);
  }

  // ---------------------------------------------------------------------------------------------
  // H12
  // ---------------------------------------------------------------------------------------------

  /**
   * H12: breaking a locking track that holds a train must release the train's lock.
   * {@code setRemoved()} only sets {@code trainLeaving}, the lock is removed in
   * {@code serverTick}, which never runs again once the block entity is gone.
   */
  static void h12RemovedLockingTrackKeepsLock(GameTestHelper helper) {
    var trackPos = new BlockPos(4, 1, 4);
    helper.setBlock(trackPos, RailcraftBlocks.IRON_LOCKING_TRACK.get());
    var cart = spawnCart(helper, 4.5, 1.0625, 4.5);
    helper.startSequence()
        .thenIdle(10)
        .thenExecute(() -> {
          var train = RollingStock.getOrThrow(cart).train();
          if (train == null || !train.isLocked()) {
            cart.discard();
            setupError(helper, "H12", "cart on an unpowered locking track (lockdown) is not "
                + "locked, cart at " + cart.position());
          }
          helper.setBlock(trackPos, Blocks.AIR);
        })
        .thenIdle(20)
        .thenExecute(() -> {
          Train train = RollingStock.getOrThrow(cart).train();
          var locked = train != null && train.isLocked();
          cart.discard();
          if (locked) {
            reproduced(helper, "H12", "20 ticks after the locking track holding it was broken, "
                + "the cart's train is still locked (Train.isLocked() == true); nothing will "
                + "ever remove the lock");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H13
  // ---------------------------------------------------------------------------------------------

  /**
   * H13: when a linked cart unloads and loads again it's a new entity instance; its partner
   * must re-resolve the link to the new instance instead of keeping the removed one.
   *
   * <p>Simulated unload: the partner is saved with {@link Entity#save}, removed with
   * {@link Entity.RemovalReason#UNLOADED_TO_CHUNK} and re-created from the saved data (same
   * UUID) with {@link EntityType#create} and {@code addFreshEntity}.
   */
  static void h13LinkNotResolvedAfterReload(GameTestHelper helper) {
    layRails(helper, 4);
    var cartA = spawnCart(helper, 3.5, 1.0625, 4.5);
    var cartB = spawnCart(helper, 5.0, 1.0625, 4.5);
    var reloaded = new AbstractMinecart[1];
    var sideHolder = new Side[1];
    helper.startSequence()
        .thenIdle(2)
        .thenExecute(() -> {
          var a = RollingStock.getOrThrow(cartA);
          var b = RollingStock.getOrThrow(cartB);
          if (!a.link(b)) {
            cartA.discard();
            cartB.discard();
            setupError(helper, "H13", "could not link the two carts");
          }
        })
        .thenIdle(5)
        .thenExecute(() -> {
          var level = helper.getLevel();
          var a = RollingStock.getOrThrow(cartA);
          var side = a.sideOf(RollingStock.getOrThrow(cartB)).orElse(null);
          if (side == null) {
            cartA.discard();
            cartB.discard();
            setupError(helper, "H13", "carts are not linked after 5 ticks");
            return;
          }
          sideHolder[0] = side;
          var out = output(level);
          cartB.save(out);
          var tag = out.buildResult();
          cartB.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
          var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
          var entity = EntityType.create(input, level, EntitySpawnReason.LOAD).orElse(null);
          if (!(entity instanceof AbstractMinecart minecart)
              || !minecart.getUUID().equals(cartB.getUUID())) {
            cartA.discard();
            setupError(helper, "H13", "could not re-create the unloaded cart: " + entity);
            return;
          }
          level.addFreshEntity(minecart);
          reloaded[0] = minecart;
        })
        .thenIdle(10)
        .thenExecute(() -> {
          var a = RollingStock.getOrThrow(cartA);
          var newB = reloaded[0];
          var linked = a.linkAt(sideHolder[0]).map(RollingStock::entity).orElse(null);
          var reverse = RollingStock.getOrThrow(newB).linkAt(sideHolder[0].opposite())
              .map(RollingStock::entity).orElse(null);
          cartA.discard();
          newB.discard();
          if (linked != newB) {
            reproduced(helper, "H13", "10 ticks after the partner cart was reloaded, cart A's "
                + sideHolder[0] + " link still points to "
                + (linked == null ? "nothing"
                    : (linked == cartB ? "the old, removed instance (isRemoved="
                        + linked.isRemoved() + ", reason=" + linked.getRemovalReason() + ")"
                        : linked.toString()))
                + " instead of the reloaded cart; the reloaded cart's "
                + sideHolder[0].opposite() + " link resolves to "
                + (reverse == null ? "nothing (\"Link mismatch\" is logged every tick)"
                    : reverse.toString()));
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // H14
  // ---------------------------------------------------------------------------------------------

  /**
   * H14: a world spike cart that jumps to a non-overlapping area must release the tickets of the
   * old area, and removing the cart must release all its tickets. Tickets are read from NeoForge's
   * forced chunk store ({@code TicketStorage#getEntityForcedChunks()}, field
   * {@code sourcesLoading}, by reflection).
   *
   * <p>The jump is simulated with {@code setPos} (2000 blocks along X, like a teleport) followed
   * by one manual {@code tick()}, since the entity stops being ticked by the level once it is in
   * an unloaded chunk.
   */
  static void h14WorldSpikeCartLeaksTickets(GameTestHelper helper) {
    var level = helper.getLevel();
    var cart = helper.spawn(RailcraftEntityTypes.WORLD_SPIKE.get(), new Vec3(4.5, 1, 4.5));
    var uuid = cart.getUUID();
    helper.startSequence()
        .thenIdle(3)
        .thenExecute(() -> {
          var initial = entityTickets(level, uuid);
          if (initial.size() != 9) {
            cart.discard();
            releaseTickets(level, uuid);
            setupError(helper, "H14", "expected 9 tickets after spawning, found "
                + initial.size() + ": " + chunks(initial));
            return;
          }
          var oldChunk = cart.chunkPosition();
          cart.setPos(cart.getX() + 2000, cart.getY(), cart.getZ());
          cart.tick();
          var newChunk = cart.chunkPosition();
          var afterJump = entityTickets(level, uuid);
          cart.discard();
          var afterRemove = entityTickets(level, uuid);
          releaseTickets(level, uuid);
          if (afterJump.size() > 9 || !afterRemove.isEmpty()) {
            reproduced(helper, "H14", "after the cart jumped from chunk " + oldChunk + " to "
                + newChunk + " it held " + afterJump.size() + " chunk tickets (expected 9); "
                + "after discard() " + afterRemove.size() + " tickets were still held (expected "
                + "0): " + chunks(afterRemove));
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M3
  // ---------------------------------------------------------------------------------------------

  /**
   * M3: a world spike should not send a player several particle packets every tick. Uses a spy
   * player standing on the spike (in range of {@code ServerLevel#sendParticles}); only
   * {@link ChunkLoaderParticleOptions} packets are counted.
   */
  static void m3WorldSpikeParticlePackets(GameTestHelper helper) {
    var spikePos = new BlockPos(4, 1, 4);
    helper.setBlock(spikePos, RailcraftBlocks.WORLD_SPIKE.get());
    var spy = ReviewTestUtil.spyPlayer(helper,
        Vec3.atBottomCenterOf(helper.absolutePos(spikePos.above())));
    var ticks = 20;
    helper.startSequence()
        .thenIdle(1)
        .thenExecute(spy::clear)
        .thenIdle(ticks)
        .thenExecute(() -> {
          long count;
          synchronized (spy.packets) {
            count = spy.packets.stream()
                .filter(ClientboundLevelParticlesPacket.class::isInstance)
                .map(ClientboundLevelParticlesPacket.class::cast)
                .filter(p -> p.getParticle() instanceof ChunkLoaderParticleOptions)
                .count();
          }
          spy.remove();
          helper.setBlock(spikePos, Blocks.AIR);
          if (count > ticks) {
            reproduced(helper, "M3", "a player standing on an idle world spike received " + count
                + " chunk loader particle packets in " + ticks + " ticks ("
                + String.format("%.1f", count / (double) ticks)
                + " per tick, expected at most 1 per tick)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M7
  // ---------------------------------------------------------------------------------------------

  /**
   * M7: when a cart with a stale unresolved link UUID (partner gone while this cart was
   * unloaded) is linked to a new cart, saving it must store the new partner's UUID. The stale
   * UUIDs are injected into {@code unresolvedFrontLink}/{@code unresolvedBackLink} by
   * reflection, which is the state {@code deserialize} leaves behind.
   */
  static void m7StaleUnresolvedLinkSaved(GameTestHelper helper) {
    layRails(helper, 4);
    var cartA = spawnCart(helper, 3.5, 1.0625, 4.5);
    var cartB = spawnCart(helper, 5.0, 1.0625, 4.5);
    helper.startSequence()
        .thenIdle(2)
        .thenExecute(() -> {
          var level = helper.getLevel();
          var a = (RollingStockImpl) RollingStock.getOrThrow(cartA);
          var b = RollingStock.getOrThrow(cartB);
          var staleFront = UUID.randomUUID();
          var staleBack = UUID.randomUUID();
          setField(a, "unresolvedFrontLink", staleFront);
          setField(a, "unresolvedBackLink", staleBack);
          var hasLinkBefore = a.hasLink(Side.FRONT) || a.hasLink(Side.BACK);
          var linked = a.link(b);
          var side = a.sideOf(b).orElse(null);
          var out = output(level);
          a.serialize(out);
          var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
              out.buildResult());
          cartA.discard();
          cartB.discard();
          if (!linked || side == null) {
            setupError(helper, "M7", "linking failed with stale unresolved links (linked="
                + linked + ", side=" + side + ")");
            return;
          }
          var key = side == Side.FRONT ? CompoundTagKeys.FRONT_LINK : CompoundTagKeys.BACK_LINK;
          var saved = input.read(key, UUIDUtil.CODEC).orElse(null);
          if (!cartB.getUUID().equals(saved)) {
            reproduced(helper, "M7", "cart linked on its " + side + " side to " + cartB.getUUID()
                + " but saved " + key + "=" + saved + " ("
                + (saved != null && saved.equals(side == Side.FRONT ? staleFront : staleBack)
                    ? "the stale unresolved UUID" : "unexpected")
                + "); hasLink() ignored the unresolved links before linking: "
                + !hasLinkBefore);
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M9
  // ---------------------------------------------------------------------------------------------

  /**
   * M9: looking up the train of a cart should not walk every link to the front. Builds a chain of
   * 8 linked carts, wraps each cart's {@code frontLink} in a counting proxy (by reflection), calls
   * {@code train()} once on each cart and counts the delegated {@code train()} calls. The links
   * are restored before the carts are removed.
   */
  static void m9TrainLookupQuadratic(GameTestHelper helper) {
    var count = 8;
    var carts = new ArrayList<AbstractMinecart>();
    helper.startSequence()
        .thenExecute(() -> {
          layRails(helper, 4);
          for (var i = 0; i < count; i++) {
            carts.add(spawnCart(helper, 0.5 + i, 1.0625, 4.5));
          }
        })
        .thenIdle(1)
        .thenExecute(() -> {
          var stocks = carts.stream().map(RollingStock::getOrThrow).toList();
          for (var i = 1; i < count; i++) {
            if (!stocks.get(i).link(stocks.get(i - 1))) {
              carts.forEach(Entity::discard);
              setupError(helper, "M9", "could not link cart " + i);
              return;
            }
          }
          var calls = new AtomicInteger();
          var originals = new ArrayList<Object>();
          for (var stock : stocks) {
            RollingStock front = getField(stock, "frontLink");
            originals.add(front);
            if (front != null) {
              setField(stock, "frontLink", countingProxy(front, calls));
            }
          }
          var maxDepth = 0;
          try {
            for (var stock : stocks) {
              var before = calls.get();
              stock.train();
              maxDepth = Math.max(maxDepth, calls.get() - before);
            }
          } finally {
            for (var i = 0; i < count; i++) {
              setField(stocks.get(i), "frontLink", originals.get(i));
            }
          }
          carts.forEach(Entity::discard);
          if (calls.get() > count) {
            reproduced(helper, "M9", "calling train() once on each cart of a " + count
                + "-cart train made " + calls.get() + " recursive train() calls through the front "
                + "links (deepest lookup: " + maxDepth + " hops); expected at most " + count
                + " in total, cost grows O(n^2) with train length");
          }
        })
        .thenSucceed();
  }

  private static RollingStock countingProxy(RollingStock delegate, AtomicInteger calls) {
    return (RollingStock) Proxy.newProxyInstance(RollingStock.class.getClassLoader(),
        new Class<?>[] {RollingStock.class}, (proxy, method, args) -> {
          if (method.getName().equals("train") && method.getParameterCount() == 0) {
            calls.incrementAndGet();
          }
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // M10
  // ---------------------------------------------------------------------------------------------

  /**
   * M10: a force track without an emitter (emitter removed or not loaded) must tolerate a
   * neighbour update.
   */
  static void m10ForceTrackWithoutEmitter(GameTestHelper helper) {
    var trackPos = new BlockPos(4, 1, 4);
    helper.startSequence()
        .thenExecute(() -> {
          Throwable thrown = null;
          try {
            helper.setBlock(trackPos, RailcraftBlocks.FORCE_TRACK.get());
            if (!helper.getBlockState(trackPos).is(RailcraftBlocks.FORCE_TRACK.get())) {
              setupError(helper, "M10", "force track was not placed: "
                  + helper.getBlockState(trackPos));
              return;
            }
            helper.setBlock(trackPos.east(), Blocks.STONE);
          } catch (Throwable t) {
            thrown = t;
          }
          // Remove the track first, removing the stone next to it would crash the server
          helper.setBlock(trackPos, Blocks.AIR);
          helper.setBlock(trackPos.east(), Blocks.AIR);
          if (thrown != null) {
            var root = thrown;
            while (root.getCause() != null && root.getCause() != root) {
              root = root.getCause();
            }
            var frame = root.getStackTrace().length > 0 ? root.getStackTrace()[0] : null;
            reproduced(helper, "M10", "placing a block next to a force track with no emitter "
                + "threw " + thrown.getClass().getSimpleName() + " caused by "
                + root.getClass().getSimpleName() + (frame == null ? "" : " at " + frame));
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M12
  // ---------------------------------------------------------------------------------------------

  /**
   * M12: a train dispenser must only start building a train when the buffer holds every cart of
   * the pattern. The private {@code canBuildTrain()} is called by reflection.
   */
  static void m12TrainDispenserPartialBuffer(GameTestHelper helper) {
    var pos = new BlockPos(4, 1, 4);
    helper.setBlock(pos, RailcraftBlocks.TRAIN_DISPENSER.get());
    helper.startSequence()
        .thenExecute(() -> {
          var dispenser = helper.getBlockEntity(pos, TrainDispenserBlockEntity.class);
          dispenser.getInvPattern().setItem(0, new ItemStack(Items.MINECART));
          dispenser.getInvPattern().setItem(1, new ItemStack(Items.CHEST_MINECART));
          dispenser.container().setItem(0, new ItemStack(Items.MINECART));
          boolean canBuild;
          try {
            Method method = TrainDispenserBlockEntity.class.getDeclaredMethod("canBuildTrain");
            method.setAccessible(true);
            canBuild = (boolean) method.invoke(dispenser);
          } catch (ReflectiveOperationException e) {
            setupError(helper, "M12", "could not call canBuildTrain: " + e);
            return;
          } finally {
            helper.setBlock(pos, Blocks.AIR);
          }
          if (canBuild) {
            reproduced(helper, "M12", "pattern [minecart, chest minecart] with only a minecart "
                + "in the buffer: canBuildTrain() returned true (a partial train would be "
                + "dispensed)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // M13
  // ---------------------------------------------------------------------------------------------

  /**
   * M13: players who can't see links (no goggles) should not be sent the linked carts list. The
   * spy player isn't ticked by the server, so a {@link PlayerTickEvent.Post} is posted for it
   * directly with {@code tickCount} at a multiple of 20.
   */
  static void m13LinkedCartsWithoutGoggles(GameTestHelper helper) {
    var spy = ReviewTestUtil.spyPlayer(helper);
    helper.startSequence()
        .thenExecute(() -> {
          spy.player.tickCount = 40;
          spy.clear();
          var hasHelmet = !spy.player.getItemBySlot(
              net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
          NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(spy.player));
          long received;
          synchronized (spy.packets) {
            received = spy.packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(p -> p.payload() instanceof LinkedCartsMessage)
                .count();
          }
          spy.remove();
          if (hasHelmet) {
            setupError(helper, "M13", "spy player wears a helmet");
            return;
          }
          if (received > 0) {
            reproduced(helper, "M13", "a player with no goggles and no carts within 32 blocks "
                + "received " + received + " LinkedCartsMessage from one player tick "
                + "(sent every second to every player)");
          }
        })
        .thenSucceed();
  }

  // ---------------------------------------------------------------------------------------------
  // L6
  // ---------------------------------------------------------------------------------------------

  /**
   * L6: an elevator track should align the cart's yaw with the track's facing. The protected
   * {@code alignMinecart} is called by reflection for a north and an east facing elevator.
   */
  static void l6ElevatorCartYaw(GameTestHelper helper) {
    var cart = spawnCart(helper, 4.5, 1, 4.5);
    helper.startSequence()
        .thenExecute(() -> {
          var block = RailcraftBlocks.ELEVATOR_TRACK.get();
          var yaws = new ArrayList<Float>();
          try {
            Method method = ElevatorTrackBlock.class.getDeclaredMethod("alignMinecart",
                net.minecraft.world.level.block.state.BlockState.class, AbstractMinecart.class);
            method.setAccessible(true);
            for (var facing : List.of(Direction.NORTH, Direction.EAST)) {
              cart.setYRot(123F);
              method.invoke(block,
                  block.defaultBlockState().setValue(ElevatorTrackBlock.FACING, facing), cart);
              yaws.add(cart.getYRot());
            }
          } catch (ReflectiveOperationException e) {
            cart.discard();
            setupError(helper, "L6", "could not call alignMinecart: " + e);
            return;
          }
          cart.discard();
          if (yaws.get(0).equals(yaws.get(1))) {
            reproduced(helper, "L6", "alignMinecart set the cart's yaw to " + yaws.get(0)
                + " for a NORTH facing elevator and " + yaws.get(1)
                + " for an EAST facing one (getStepY() of a horizontal direction is always 0)");
          }
        })
        .thenSucceed();
  }
}
