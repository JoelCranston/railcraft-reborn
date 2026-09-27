/**
 * GameTests that try to reproduce the findings of the server performance and bug review of
 * 2026-09-26. Every test asserts the <em>correct</em> behaviour, so a failing test means the
 * finding reproduces. They are registered as optional tests so that they don't fail the build
 * until the findings are fixed.
 */
@NullMarked
package mods.railcraft.data.gametest.instances.review;

import org.jspecify.annotations.NullMarked;
