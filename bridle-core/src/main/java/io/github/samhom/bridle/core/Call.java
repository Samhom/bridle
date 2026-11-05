package io.github.samhom.bridle.core;

@FunctionalInterface
public interface Call<C, T> {
  T execute(C client, CallContext context) throws Exception;
}
