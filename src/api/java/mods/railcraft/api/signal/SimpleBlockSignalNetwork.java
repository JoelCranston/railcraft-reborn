/*------------------------------------------------------------------------------
 Copyright (c) Railcraft Reborn, 2023+

 This work (the API) is licensed under the "MIT" License,
 see LICENSE.md for details.
 -----------------------------------------------------------------------------*/
package mods.railcraft.api.signal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import mods.railcraft.api.track.TrackScanUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

public class SimpleBlockSignalNetwork extends BlockEntitySignalNetwork<BlockSignalEntity>
    implements BlockSignal {

  private static final Logger LOGGER = LogUtils.getLogger();

  public static final int SIGNAL_VALIDATION_INTERVAL = 4 * SharedConstants.TICKS_PER_MINUTE;
  private final Set<BlockPos> signalsToRevalidate = new HashSet<>();

  private final Map<BlockPos, TrackScanUtil.Result> trackScans = new HashMap<>();
  /**
   * Last known track position of each peer, for peers whose chunk isn't loaded.
   */
  private final Map<BlockPos, BlockPos> peerTrackPositions = new HashMap<>();
  /**
   * Last known aspect of the signals beyond each peer, for peers whose chunk isn't loaded.
   */
  private final Map<BlockPos, SignalAspect> remoteAspects = new HashMap<>();
  private final TrackLocator trackLocator;

  private int aspectUpdateTimer = 0;
  private int signalValidationTimer = 0;

  private SignalAspect signalAspect = SignalAspect.BLINK_RED;
  @Nullable
  private final Consumer<SignalAspect> signalAspectListener;

  public SimpleBlockSignalNetwork(int maxPeers, Runnable syncListener,
      @Nullable Consumer<SignalAspect> signalAspectListener,
      BlockEntity blockEntity) {
    super(BlockSignalEntity.class, maxPeers, syncListener, blockEntity);
    this.trackLocator = new TrackLocator(blockEntity::getLevel, blockEntity.getBlockPos());
    this.signalAspectListener = signalAspectListener;
  }

  @Override
  public SignalAspect aspect() {
    return this.signalAspect;
  }

  @Override
  public TrackLocator trackLocator() {
    return this.trackLocator;
  }

  @Override
  public boolean addPeer(BlockSignalEntity peer) {
    var blockSignal = peer.signalNetwork();
    if (blockSignal == this) {
      return false;
    }

    var trackStatus = this.trackLocator.trackStatus();
    var otherTrackStatus = blockSignal.trackLocator().trackStatus();
    if (trackStatus.invalid() || otherTrackStatus.invalid()) {
      return false;
    }

    var trackPos = this.trackLocator.trackPos();
    var peerTrackPos = blockSignal.trackLocator().trackPos();

    assert trackPos != null;
    assert peerTrackPos != null;

    var result = TrackScanUtil.scanStraightTrackSection(this.getLevel(), trackPos, peerTrackPos);
    if (!result.status().valid()) {
      return false;
    }

    if (!super.addPeer(peer)) {
      return false;
    }

    this.trackScans.put(peerTrackPos, result);

    return true;
  }

  @Override
  public boolean removePeer(BlockPos peerPos) {
    if (super.removePeer(peerPos)) {
      var blockSignalProvider = this.getBlockEntity(peerPos);
      if (blockSignalProvider != null) {
        blockSignalProvider.signalNetwork().removePeer(this.blockPos());
      }
      return true;
    }
    return false;
  }

  @Override
  public void refresh() {
    super.refresh();
    if (this.signalAspectListener != null) {
      this.signalAspectListener.accept(this.signalAspect);
    }
  }

  @Override
  protected boolean refreshPeer(BlockSignalEntity peer) {
    return peer.signalNetwork().isPeer(this.blockPos());
  }

  @Override
  public SignalAspect aspectExcluding(BlockPos excludingPos) {
    return this.aspectExcluding(excludingPos, new HashSet<>());
  }

  /**
   * @param visited - signals already taking part in this calculation, a loop of signals (relay
   *        boxes) is only walked once
   */
  private SignalAspect aspectExcluding(BlockPos excludingPos, Set<BlockPos> visited) {
    if (!visited.add(this.blockPos())) {
      return SignalAspect.GREEN;
    }
    var aspect = SignalAspect.GREEN;
    for (var peerPos : this.peers) {
      if (!peerPos.equals(excludingPos)) {
        aspect = SignalAspect.mostRestrictive(aspect, this.peerAspect(peerPos, visited));
      }
    }
    return aspect;
  }

  private SignalAspect peerAspect(BlockPos peerPos, Set<BlockPos> visited) {
    if (!this.getLevel().isLoaded(peerPos)) {
      return this.unloadedPeerAspect(peerPos);
    }
    return this.peerAt(peerPos)
        .map(peer -> this.calculateSignalAspect(peer, visited))
        .orElse(SignalAspect.GREEN);
  }

  /**
   * The aspect towards a peer whose chunk isn't loaded, without loading it: carts on our end of
   * the section are still seen, the signals beyond the peer keep their last known aspect. A peer
   * which wasn't seen since the level loaded can't be checked at all.
   */
  private SignalAspect unloadedPeerAspect(BlockPos peerPos) {
    var trackPos = this.trackLocator.trackPos();
    if (trackPos == null) {
      return SignalAspect.RED;
    }
    var otherTrack = this.peerTrackPositions.get(peerPos);
    if (otherTrack == null) {
      return SignalAspect.YELLOW;
    }
    return SignalAspect.mostRestrictive(this.cartAspect(trackPos, otherTrack),
        this.remoteAspects.getOrDefault(peerPos, SignalAspect.GREEN));
  }

  private SignalAspect calculateSignalAspect(BlockSignalEntity peer, Set<BlockPos> visited) {
    var trackPos = this.trackLocator.trackPos();
    if (trackPos == null) {
      return SignalAspect.RED;
    }

    var otherTrack = peer.signalNetwork().trackLocator().trackPos();
    if (otherTrack == null) {
      return SignalAspect.YELLOW;
    }

    var peerPos = peer.asBlockEntity().getBlockPos();
    this.peerTrackPositions.put(peerPos, otherTrack);
    var aspect = this.cartAspect(trackPos, otherTrack);
    if (aspect == SignalAspect.RED) {
      return aspect;
    }

    var remoteAspect = peer.signalNetwork() instanceof SimpleBlockSignalNetwork network
        ? network.aspectExcluding(this.blockPos(), visited)
        : peer.signalNetwork().aspectExcluding(this.blockPos());
    this.remoteAspects.put(peerPos, remoteAspect);
    return SignalAspect.mostRestrictive(aspect, remoteAspect);
  }

  /**
   * The aspect given by the carts on the track section between our track and the other track.
   */
  private SignalAspect cartAspect(BlockPos trackPos, BlockPos otherTrack) {
    var scan = this.getOrCreateTrackScan(otherTrack);
    if (scan == null) {
      return SignalAspect.RED;
    }

    int y1 = scan.minY();
    int y2 = scan.maxY() + 1;

    int x1 = Math.min(trackPos.getX(), otherTrack.getX());
    int z1 = Math.min(trackPos.getZ(), otherTrack.getZ());
    int x2 = Math.max(trackPos.getX(), otherTrack.getX()) + 1;
    int z2 = Math.max(trackPos.getZ(), otherTrack.getZ()) + 1;

    var zAxis = Math.abs(trackPos.getX() - otherTrack.getX()) < Math
        .abs(trackPos.getZ() - otherTrack.getZ());
    int xOffset = otherTrack.getX() > trackPos.getX() ? -3 : 3;
    int zOffset = otherTrack.getZ() > trackPos.getZ() ? -3 : 3;

    var carts = this.getLevel().getEntitiesOfClass(AbstractMinecart.class,
        new AABB(x1, y1, z1, x2, y2, z2),
        EntitySelector.ENTITY_STILL_ALIVE);

    var newAspect = SignalAspect.GREEN;
    for (var cart : carts) {
      int cartX = Mth.floor(cart.getX());
      int cartZ = Mth.floor(cart.getZ());
      var motionX = cart.getDeltaMovement().x();
      var motionZ = cart.getDeltaMovement().z();
      if (Math.abs(motionX) < 0.08 && Math.abs(motionZ) < 0.08) {
        return SignalAspect.RED;
      }

      if (zAxis) {
        if (cartZ > trackPos.getZ() + zOffset && motionZ < 0) {
          return SignalAspect.RED;
        }

        if (cartZ < trackPos.getZ() + zOffset && motionZ > 0) {
          return SignalAspect.RED;
        }
      }

      if (cartX > trackPos.getX() + xOffset && motionX < 0) {
        return SignalAspect.RED;
      }

      if (cartX < trackPos.getX() + xOffset && motionX > 0) {
        return SignalAspect.RED;
      }

      newAspect = SignalAspect.YELLOW;
    }

    return newAspect;
  }

  private TrackScanUtil.@Nullable Result getOrCreateTrackScan(BlockPos otherTrack) {
    var result = this.trackScans.get(otherTrack);
    if (result == null) {
      var trackPos = this.trackLocator.trackPos();
      if (trackPos != null) {
        result = TrackScanUtil.scanStraightTrackSection(getLevel(), trackPos, otherTrack);
        this.trackScans.put(otherTrack, result);
      }
    }
    return result;
  }

  private TrackValidationResult validateSignal(BlockSignal blockSignal) {
    TrackLocator.Status trackStatus = this.trackLocator.trackStatus();
    if (trackStatus == TrackLocator.Status.INVALID)
      return new TrackValidationResult(false, "INVALID_MY_TRACK_NULL");
    TrackLocator.Status otherTrackStatus = blockSignal.trackLocator().trackStatus();
    if (otherTrackStatus == TrackLocator.Status.INVALID)
      return new TrackValidationResult(false, "INVALID_OTHER_TRACK_INVALID");
    BlockPos otherTrackPos = blockSignal.trackLocator().trackPos();
    if (otherTrackPos == null)
      return new TrackValidationResult(true, "UNVERIFIABLE_OTHER_TRACK_NULL");
    BlockPos trackPos = this.trackLocator.trackPos();
    if (trackPos == null)
      return new TrackValidationResult(true, "INVALID_MY_TRACK_NULL");
    TrackScanUtil.Result scanResult =
        TrackScanUtil.scanStraightTrackSection(this.getLevel(), trackPos, otherTrackPos);
    this.trackScans.put(otherTrackPos, scanResult);

    if (scanResult.status().valid()) {
      return new TrackValidationResult(true, "VALID");
    }

    if (scanResult.status().unknown()) {
      return new TrackValidationResult(true, "UNVERIFIABLE_UNLOADED_CHUNK");
    }

    return new TrackValidationResult(false,
        "INVALID_SCAN_FAIL: " + scanResult.status());
  }

  public void serverTick() {
    if (this.aspectUpdateTimer++ >= SignalUtil.aspectUpdateInterval) {
      this.aspectUpdateTimer = 0;
      SignalAspect lastAspect = this.signalAspect;
      if (this.hasPeers()) {
        this.signalAspect = SignalAspect.GREEN;
        var visited = new HashSet<BlockPos>();
        visited.add(this.blockPos());
        for (var peerPos : this.peers) {
          this.signalAspect = SignalAspect.mostRestrictive(this.signalAspect,
              this.peerAspect(peerPos, visited));
        }
      } else if (this.isLinking()) {
        this.signalAspect = SignalAspect.BLINK_YELLOW;
      } else {
        this.signalAspect = SignalAspect.BLINK_RED;
      }
      if (lastAspect != this.signalAspect && this.signalAspectListener != null) {
        this.signalAspectListener.accept(this.signalAspect);
      }
    }
    if (this.signalValidationTimer++ >= SIGNAL_VALIDATION_INTERVAL) {
      this.signalValidationTimer = 0;
      switch (this.trackLocator.trackStatus()) {
        case INVALID -> {
          for (var peerPos : List.copyOf(this.peers)) {
            this.removePeer(peerPos);
          }
          LOGGER.debug("Block signal dropped because no track was found near signal @ [{}]",
              this.blockPos());
        }
        case VALID -> {
          this.signalsToRevalidate.retainAll(this.peers());
          for (var peerPos : List.copyOf(this.signalsToRevalidate)) {
            if (!this.getLevel().isLoaded(peerPos)) {
              // Can't be verified without loading its chunk
              continue;
            }
            this.peerAt(peerPos).ifPresentOrElse(peer -> {
              var result = this.validateSignal(peer.signalNetwork());
              if (!result.valid) {
                this.removePeer(peerPos);
                LOGGER.debug(
                    "Block signal dropped because track between signals was invalid. source:[{}] target:[{}] reason:{}",
                    this.blockPos(), peerPos, result.message);
              }

            }, () -> this.removePeer(peerPos));
          }
          this.signalsToRevalidate.clear();

          for (var peerPos : List.copyOf(this.peers)) {
            if (!this.getLevel().isLoaded(peerPos)) {
              continue;
            }
            this.peerAt(peerPos).ifPresentOrElse(peer -> {
              var result = this.validateSignal(peer.signalNetwork());
              if (!result.valid) {
                this.signalsToRevalidate.add(peerPos);
              }
            }, () -> this.removePeer(peerPos));
          }
        }
        default -> {}
      }
    }
  }

  @Override
  public void writeToBuf(RegistryFriendlyByteBuf data) {
    super.writeToBuf(data);
    data.writeEnum(this.signalAspect);
  }

  @Override
  public void readFromBuf(RegistryFriendlyByteBuf data) {
    super.readFromBuf(data);
    this.signalAspect = data.readEnum(SignalAspect.class);
  }

  private record TrackValidationResult(boolean valid, String message) {}
}
