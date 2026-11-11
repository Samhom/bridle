package io.github.samhom.bridle.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Resource identity is supplied by trusted application wiring, never a request header. */
public final class ResourceId {
  private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}");
  private final String application;
  private final String environment;
  private final String alias;
  private final int hash;
  private final String text;

  public ResourceId(String application, String environment, String alias) {
    this.application = component(application);
    this.environment = component(environment);
    this.alias = component(alias);
    this.hash = Objects.hash(this.application, this.environment, this.alias);
    this.text = this.application + "/" + this.environment + "/" + this.alias;
  }

  private static String component(String value) {
    if (value == null || !COMPONENT.matcher(value).matches())
      throw new IllegalArgumentException("Invalid resource identity component");
    return value;
  }

  public String application() {
    return application;
  }

  public String environment() {
    return environment;
  }

  public String alias() {
    return alias;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) return true;
    if (!(other instanceof ResourceId)) return false;
    ResourceId that = (ResourceId) other;
    return hash == that.hash
        && application.equals(that.application)
        && environment.equals(that.environment)
        && alias.equals(that.alias);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return text;
  }
}
