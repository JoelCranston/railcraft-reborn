package mods.railcraft.network.to_server;

import java.util.List;
import java.util.Optional;
import mods.railcraft.api.core.RailcraftConstants;
import mods.railcraft.world.item.RoutingTableBookItem;
import mods.railcraft.world.item.component.RailcraftDataComponents;
import mods.railcraft.world.item.component.RoutingTableBookContent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EditRoutingTableBookMessage(
    InteractionHand hand, List<String> pages,
    Optional<String> title) implements CustomPacketPayload {

  public static final Type<EditRoutingTableBookMessage> TYPE =
      new Type<>(RailcraftConstants.id("edit_routing_table_book"));

  public static final StreamCodec<FriendlyByteBuf, EditRoutingTableBookMessage> STREAM_CODEC =
      StreamCodec.composite(
          NeoForgeStreamCodecs.enumCodec(InteractionHand.class), EditRoutingTableBookMessage::hand,
          ByteBufCodecs.stringUtf8(RoutingTableBookContent.PAGE_EDIT_LENGTH)
              .apply(ByteBufCodecs.list(RoutingTableBookContent.MAX_PAGES)),
          EditRoutingTableBookMessage::pages,
          ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(RoutingTableBookContent.MAX_TITLE_LENGTH)),
          EditRoutingTableBookMessage::title,
          EditRoutingTableBookMessage::new);

  @Override
  public Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(EditRoutingTableBookMessage message, IPayloadContext context) {
    var player = context.player();
    var senderProfile = player.getGameProfile();
    var itemStack = player.getItemInHand(message.hand);
    if (!isValid(message)) {
      return;
    }
    if (itemStack.getItem() instanceof RoutingTableBookItem) {
      itemStack.set(RailcraftDataComponents.ROUTING_TABLE_BOOK,
          new RoutingTableBookContent(message.pages, senderProfile.name(), message.title));
    }
  }

  private static boolean isValid(EditRoutingTableBookMessage message) {
    return message.pages.size() <= RoutingTableBookContent.MAX_PAGES
        && message.pages.stream()
            .allMatch(page -> page.length() <= RoutingTableBookContent.PAGE_EDIT_LENGTH)
        && message.title.map(title -> title.length() <= RoutingTableBookContent.MAX_TITLE_LENGTH)
            .orElse(true);
  }
}
