package mods.railcraft.util.routing;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import mods.railcraft.Translations;

public class RoutingStatementParser {

  public static final String REGEX_SYMBOL = "\\?";

  /**
   * Longest regular expression a routing statement may use.
   */
  public static final int MAX_REGEX_LENGTH = 128;

  /**
   * How many characters a regular expression may read while matching a single value. Player
   * written expressions run on the server thread, one which backtracks more than this gives up
   * and doesn't match.
   */
  private static final int MAX_REGEX_READS = 100_000;

  public static ParsedStatement parse(String keyword, boolean supportsRegex, String line)
      throws RoutingLogicException {
    String keywordMatch = keyword + REGEX_SYMBOL + "?=";
    if (!line.matches(keywordMatch + ".*")) {
      throw new RoutingLogicException(Translations.RoutingTable.UNRECOGNIZED_KEYWORD, line);
    }
    var isRegex = line.matches(keyword + REGEX_SYMBOL + "=.*");
    if (!supportsRegex && isRegex) {
      throw new RoutingLogicException(Translations.RoutingTable.ERROR_UNSUPPORTED_REGEX, line);
    }
    var value = line.replaceFirst(keywordMatch, "");
    Pattern pattern = null;
    if (isRegex) {
      if (value.length() > MAX_REGEX_LENGTH) {
        throw new RoutingLogicException(Translations.RoutingTable.ERROR_INVALID_REGEX, line);
      }
      try {
        pattern = Pattern.compile(value);
      } catch (PatternSyntaxException ex) {
        throw new RoutingLogicException(Translations.RoutingTable.ERROR_INVALID_REGEX, line);
      }
    }
    return new ParsedStatement(value, pattern);
  }

  /**
   * Matches a player written pattern against a value, giving up (and not matching) once the
   * pattern reads too many characters.
   */
  public static boolean matches(Pattern pattern, String value) {
    try {
      return pattern.matcher(new BoundedCharSequence(value, new int[] {MAX_REGEX_READS}))
          .matches();
    } catch (RegexTooComplexException e) {
      return false;
    }
  }

  /**
   * Same as {@link #matches(Pattern, String)}, but compiles the pattern first. An invalid or too
   * long pattern doesn't match anything.
   */
  public static boolean matches(String regex, String value) {
    if (regex.length() > MAX_REGEX_LENGTH) {
      return false;
    }
    try {
      return matches(Pattern.compile(regex), value);
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  private static final class RegexTooComplexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RegexTooComplexException() {
      super(null, null, false, false);
    }
  }

  /**
   * A {@link CharSequence} which throws once more than a set number of characters were read from
   * it (or any of its sub sequences).
   */
  private record BoundedCharSequence(String value, int[] readsLeft) implements CharSequence {

    @Override
    public char charAt(int index) {
      if (--this.readsLeft[0] < 0) {
        throw new RegexTooComplexException();
      }
      return this.value.charAt(index);
    }

    @Override
    public int length() {
      return this.value.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new BoundedCharSequence(this.value.substring(start, end), this.readsLeft);
    }

    @Override
    public String toString() {
      return this.value;
    }
  }

  public record ParsedStatement(String value, @Nullable Pattern pattern) {

    public boolean isRegex() {
      return this.pattern != null;
    }

    /**
     * Matches this statement's pattern against the value, see
     * {@link RoutingStatementParser#matches(Pattern, String)}.
     */
    public boolean matches(String value) {
      return this.pattern != null && RoutingStatementParser.matches(this.pattern, value);
    }
  }
}
