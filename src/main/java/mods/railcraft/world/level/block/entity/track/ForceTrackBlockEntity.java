package mods.railcraft.world.level.block.entity.track;

import org.jspecify.annotations.Nullable;
import mods.railcraft.api.core.CompoundTagKeys;
import mods.railcraft.world.level.block.ForceTrackEmitterBlock;
import mods.railcraft.world.level.block.entity.ForceTrackEmitterBlockEntity;
import mods.railcraft.world.level.block.entity.RailcraftBlockEntity;
import mods.railcraft.world.level.block.entity.RailcraftBlockEntityTypes;
import mods.railcraft.world.level.block.track.ForceTrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class ForceTrackBlockEntity extends RailcraftBlockEntity {

  @Nullable
  private ForceTrackEmitterBlockEntity emitter;
  @Nullable
  private BlockPos emitterPos;

  public ForceTrackBlockEntity(BlockPos blockPos, BlockState blockState) {
    super(RailcraftBlockEntityTypes.FORCE_TRACK.get(), blockPos, blockState);
  }

  public void neighborChanged() {
    var emitter = this.getEmitter();
    if (emitter != null) {
      emitter.notifyTrackChange();
    }
  }

  /**
   * The emitter, looked up when needed. Never loads the emitter's chunk.
   */
  @Nullable
  private ForceTrackEmitterBlockEntity getEmitter() {
    if ((this.emitter == null || this.emitter.isRemoved()) && this.emitterPos != null
        && this.level != null && this.level.isLoaded(this.emitterPos)) {
      this.emitter = this.level
          .getBlockEntity(this.emitterPos, RailcraftBlockEntityTypes.FORCE_TRACK_EMITTER.get())
          .orElse(null);
    }
    return this.emitter;
  }

  public void setEmitter(ForceTrackEmitterBlockEntity emitter) {
    this.emitter = emitter;
    this.emitterPos = emitter.getBlockPos();
    this.setOwner(emitter.getOwner().orElse(null));
    this.level.setBlockAndUpdate(this.getBlockPos(),
        this.getBlockState().setValue(ForceTrackBlock.COLOR,
            emitter.getBlockState().getValue(ForceTrackEmitterBlock.COLOR)));
  }

  @Override
  public void onLoad() {
    super.onLoad();
    this.emitter = null;
    this.getEmitter();
  }

  @Override
  public void preRemoveSideEffects(BlockPos pos, BlockState state) {
    super.preRemoveSideEffects(pos, state);
    var emitter = this.getEmitter();
    if (emitter != null) {
      emitter.clearTracks(this.getBlockPos());
    }

    if (this.level instanceof ServerLevel serverLevel) {
      var block = (ForceTrackBlock) state.getBlock();
      if (block.getTrackType().isElectric()) {
        block.deregisterNode(serverLevel, pos);
      }
    }
  }

  @Override
  protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    output.storeNullable(CompoundTagKeys.EMITTER_POS, BlockPos.CODEC, this.emitterPos);
  }

  @Override
  protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);
    this.emitterPos = input.read(CompoundTagKeys.EMITTER_POS, BlockPos.CODEC).orElse(null);
  }
}
