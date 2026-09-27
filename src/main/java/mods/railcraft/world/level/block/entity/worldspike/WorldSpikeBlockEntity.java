package mods.railcraft.world.level.block.entity.worldspike;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import mods.railcraft.Railcraft;
import mods.railcraft.particle.ChunkLoaderParticleOptions;
import mods.railcraft.world.level.block.entity.RailcraftBlockEntity;
import mods.railcraft.world.level.block.entity.RailcraftBlockEntityTypes;
import mods.railcraft.world.level.block.worldspike.WorldSpikeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

public class WorldSpikeBlockEntity extends RailcraftBlockEntity {

  private static final Logger LOGGER = LogUtils.getLogger();

  public WorldSpikeBlockEntity(BlockPos blockPos, BlockState blockState) {
    super(RailcraftBlockEntityTypes.WORLD_SPIKE.get(), blockPos, blockState);
  }

  protected WorldSpikeBlockEntity(BlockEntityType<?> type, BlockPos blockPos,
      BlockState blockState) {
    super(type, blockPos, blockState);
  }

  @Override
  public void preRemoveSideEffects(BlockPos pos, BlockState state) {
    super.preRemoveSideEffects(pos, state);
    if (this.level instanceof ServerLevel serverLevel) {
      ((WorldSpikeBlock) state.getBlock()).removeChunks(serverLevel, pos);
    }
  }

  private static final int PARTICLE_INTERVAL_TICKS = 10;

  public static void serverTick(Level level, BlockPos blockPos, BlockState blockState,
      WorldSpikeBlockEntity blockEntity) {
    spawnParticle((ServerLevel) level, blockPos);
  }

  public static void spawnParticle(ServerLevel level, BlockPos blockPos) {
    // Every particle is a packet to every player nearby, spikes tick forever
    if (level.getGameTime() % PARTICLE_INTERVAL_TICKS != 0) {
      return;
    }
    var random = level.getRandom();
    var chunkPos = ChunkPos.containing(blockPos);
    for (int x = chunkPos.x() - 1; x <= chunkPos.x() + 1; x++) {
      for (int z = chunkPos.z() - 1; z <= chunkPos.z() + 1; z++) {
        int xCorner = x * 16;
        int zCorner = z * 16;
        double yCorner = blockPos.getY() - 8;

        if (random.nextBoolean()) {
          double xParticle = xCorner + random.nextFloat() * 16;
          double yParticle = yCorner + random.nextFloat() * 16;
          double zParticle = zCorner + random.nextFloat() * 16;

          var dest = new Vec3(blockPos.getX() + 0.1, blockPos.getY(), blockPos.getZ() + 0.1);
          level.sendParticles(new ChunkLoaderParticleOptions(dest),
              xParticle, yParticle, zParticle, 1, 0, 0, 0, 0);
        }
      }
    }
  }

  public static class RailcraftValidationTicket implements LoadingValidationCallback {

    /**
     * Ticks after the server started until entity tickets are checked, enough for the entities of
     * the reinstated chunks to load.
     */
    private static final int ENTITY_TICKET_CHECK_DELAY_TICKS = 200;

    private static final Map<ResourceKey<Level>, Map<UUID, LongSet>> PENDING_ENTITY_TICKETS =
        new ConcurrentHashMap<>();

    @Override
    public void validateTickets(ServerLevel level, TicketHelper ticketHelper) {
      for (var entry : ticketHelper.getBlockTickets().entrySet()) {
        var key = entry.getKey();
        var value = entry.getValue();
        int ticketCount = value.normal().size();
        int tickingTicketCount = value.naturalSpawning().size();
        var be = level.getBlockEntity(key);
        if (be instanceof WorldSpikeBlockEntity) {
          LOGGER.info("Allowing {} chunk tickets and {} ticking chunk tickets to be reinstated for position: {}.", ticketCount, tickingTicketCount, key);
        } else {
          ticketHelper.removeAllTickets(key);
          LOGGER.info("Removing {} chunk tickets and {} ticking chunk tickets for no longer valid position: {}.", ticketCount, tickingTicketCount, key);
        }
      }
      for (var entry : ticketHelper.getEntityTickets().entrySet()) {
        var key = entry.getKey();
        var value = entry.getValue();
        int ticketCount = value.normal().size();
        int tickingTicketCount = value.naturalSpawning().size();
        LOGGER.info("Allowing {} chunk tickets and {} ticking chunk tickets to be reinstated for entity: {}.", ticketCount, tickingTicketCount, key);
        // Entities aren't loaded yet, check that the cart still exists once they are
        var chunks = new LongOpenHashSet(value.normal());
        chunks.addAll(value.naturalSpawning());
        PENDING_ENTITY_TICKETS.computeIfAbsent(level.dimension(), __ -> new HashMap<>())
            .put(key, chunks);
      }
    }

    /**
     * Invoked every level tick. Once the entities of the reinstated chunks had time to load,
     * releases the tickets of entities which no longer exist, e.g. a world spike cart that was
     * destroyed without releasing its chunks.
     */
    public static void tick(ServerLevel level) {
      if (PENDING_ENTITY_TICKETS.containsKey(level.dimension())
          && level.getServer().getTickCount() >= ENTITY_TICKET_CHECK_DELAY_TICKS) {
        releaseOrphanedEntityTickets(level, PENDING_ENTITY_TICKETS.remove(level.dimension()));
      }
    }

    public static void releaseOrphanedEntityTickets(ServerLevel level,
        Map<UUID, LongSet> tickets) {
      tickets.forEach((owner, chunks) -> {
        if (level.getEntity(owner) != null) {
          return;
        }
        LOGGER.info("Removing {} chunk tickets of entity {}, which no longer exists.",
            chunks.size(), owner);
        for (long chunk : chunks) {
          var chunkPos = ChunkPos.unpack(chunk);
          Railcraft.CHUNK_CONTROLLER.forceChunk(level, owner, chunkPos.x(), chunkPos.z(), false,
              false);
          Railcraft.CHUNK_CONTROLLER.forceChunk(level, owner, chunkPos.x(), chunkPos.z(), false,
              true);
        }
      });
    }
  }
}
