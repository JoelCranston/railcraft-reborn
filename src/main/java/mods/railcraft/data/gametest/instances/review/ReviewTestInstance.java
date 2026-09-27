package mods.railcraft.data.gametest.instances.review;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A single test instance type for all review findings, the test to run is selected by name from
 * {@link ReviewTests}.
 */
public class ReviewTestInstance extends GameTestInstance {

  public static final MapCodec<ReviewTestInstance> CODEC =
      RecordCodecBuilder.mapCodec(instance -> instance.group(
          TestData.CODEC.forGetter(t -> t.info),
          Codec.STRING.fieldOf("test").forGetter(t -> t.test)
      ).apply(instance, ReviewTestInstance::new));

  private final String test;

  public ReviewTestInstance(TestData<Holder<TestEnvironmentDefinition<?>>> info, String test) {
    super(info);
    this.test = test;
  }

  @Override
  public void run(GameTestHelper helper) {
    Consumer<GameTestHelper> function = ReviewTests.get(this.test);
    if (function == null) {
      helper.fail(Component.literal("Unknown review test " + this.test));
      return;
    }
    function.accept(helper);
  }

  @Override
  public MapCodec<? extends GameTestInstance> codec() {
    return CODEC;
  }

  @Override
  protected MutableComponent typeDescription() {
    return Component.literal("Review finding " + this.test);
  }
}
