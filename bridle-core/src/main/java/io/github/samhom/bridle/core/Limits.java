package io.github.samhom.bridle.core;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Limits {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");

  private Limits() {}

  public static Duration timeout(Duration value) {
    Objects.requireNonNull(value, "timeout");
    if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Timeout out of bounds");
    return value;
  }

  public static int count(int value, int maximum) {
    if (value < 1 || value > maximum)
      throw new IllegalArgumentException("Resource count out of bounds");
    return value;
  }

  public static String name(String value) {
    if (value == null || !NAME.matcher(value).matches())
      throw new IllegalArgumentException("Invalid resource name");
    return value;
  }

  public static void bytes(byte[] value, int maximum) {
    if (value == null || value.length > maximum)
      throw new IllegalArgumentException("Payload is missing or oversized");
  }

  /** Rejects a missing string or one whose UTF-8 encoding is longer than {@code maximum}. */
  public static void utf8(String value, int maximum) {
    if (value == null || exceedsUtf8(value, maximum))
      throw new IllegalArgumentException("Payload is missing or oversized");
  }

  /**
   * True when the UTF-8 encoding is longer than {@code maximum}. Scanning stops at the first byte
   * past that limit. The width matches {@code String.getBytes(UTF_8)}, including unpaired
   * surrogates replaced by one byte, matching the charset encoder.
   */
  public static boolean exceedsUtf8(String value, int maximum) {
    if (value == null || maximum < 0) return true;
    long bytes = 0;
    for (int i = 0; i < value.length(); ) {
      int width = utf8Width(value, i);
      if (bytes > maximum - width) return true;
      bytes += width;
      i += width == 4 ? 2 : 1;
    }
    return false;
  }

  /** Exact UTF-8 size. Use {@link #exceedsUtf8} when a limit can stop the scan early. */
  public static long utf8Bytes(String value) {
    java.util.Objects.requireNonNull(value, "value");
    long bytes = 0;
    for (int i = 0; i < value.length(); ) {
      int width = utf8Width(value, i);
      bytes += width;
      i += width == 4 ? 2 : 1;
    }
    return bytes;
  }

  private static int utf8Width(String value, int index) {
    char current = value.charAt(index);
    if (current <= 0x7F) return 1;
    if (current <= 0x7FF) return 2;
    if (Character.isHighSurrogate(current)) {
      if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) return 4;
      return 1;
    }
    if (Character.isLowSurrogate(current)) return 1;
    return 3;
  }

  public static void messageKey(String value) {
    if (value == null || value.isEmpty())
      throw new IllegalArgumentException("Invalid message business key");
    long bytes = 0;
    for (int i = 0; i < value.length(); ) {
      char current = value.charAt(i);
      if (Character.isISOControl(current) || Character.isWhitespace(current))
        throw new IllegalArgumentException("Invalid message business key");
      int width = utf8Width(value, i);
      if (width == 4) {
        char low = value.charAt(i + 1);
        if (Character.isISOControl(low) || Character.isWhitespace(low))
          throw new IllegalArgumentException("Invalid message business key");
      }
      if (bytes > 512L - width) throw new IllegalArgumentException("Invalid message business key");
      bytes += width;
      i += width == 4 ? 2 : 1;
    }
  }
}
