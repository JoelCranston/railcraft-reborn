package mods.railcraft.data.gametest.instances.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Registry of every review finding test by name.
 */
public final class ReviewTests {

  private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();

  /**
   * Tests of findings which have been fixed. These are required, so they guard against the
   * finding coming back.
   */
  private static final Set<String> FIXED = Set.of(
      "c1_member_evaluation_storm",
      "c2_global_neighbor_notify",
      "h1_member_keeps_unloaded_master",
      "h1_reloaded_master_reforms",
      "h2_idle_coke_oven_syncs",
      "h2_lone_brick_syncs",
      "h3_adjacent_valves_pump",
      "l1_broken_tank_retries_forever",
      "c3_handlers_load_far_chunks",
      "c3_remote_edit_without_menu",
      "c4_sync_to_untracking_player",
      "h6_book_too_many_pages",
      "h6_book_oversized_page",
      "h7_routing_regex_redos",
      "l9_unbounded_packet_fields",
      "m13_linked_carts_without_goggles",
      "h8_discovery_loads_chunks",
      "h9_equal_grids_not_merged",
      "h10_remove_charge_overdraws",
      "h11_split_leaves_null_grid",
      "l2_battery_record_always_dirty",
      "l3_replaced_node_keeps_stale_spec",
      "m1_unloaded_node_not_evicted",
      "h4_signal_peer_loads_chunk",
      "h5_relay_box_cycle_overflows",
      "m4_token_ring_forgets_unloaded_cart",
      "m5_track_scan_loads_chunk",
      "m6_capacitor_wraps_after_idle",
      "h12_removed_locking_track_keeps_lock",
      "h13_link_not_resolved_after_reload",
      "h14_world_spike_cart_leaks_tickets",
      "m3_world_spike_particle_packets",
      "m7_stale_unresolved_link_saved",
      "m9_train_lookup_quadratic",
      "m10_force_track_without_emitter",
      "m12_train_dispenser_partial_buffer",
      "l6_elevator_cart_yaw",
      "m8_steam_locomotive_pulls_while_full",
      "l5_advance_on_track_once_per_tick",
      "l7_parallel_slope_caps_speed",
      "h14_orphaned_entity_tickets_released");

  static {
    Registrar registrar = TESTS::put;
    MultiblockReviewTests.register(registrar);
    NetworkReviewTests.register(registrar);
    SignalReviewTests.register(registrar);
    ChargeReviewTests.register(registrar);
    CartReviewTests.register(registrar);
    FollowUpReviewTests.register(registrar);
  }

  private ReviewTests() {}

  @Nullable
  static Consumer<GameTestHelper> get(String name) {
    return TESTS.get(name);
  }

  public static boolean isRequired(String name) {
    return FIXED.contains(name);
  }

  public static Iterable<String> names() {
    return TESTS.keySet();
  }

  @FunctionalInterface
  interface Registrar {
    void add(String name, Consumer<GameTestHelper> test);
  }
}
