package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.*;
import redis.clients.jedis.executors.ClusterCommandExecutor;
import redis.clients.jedis.executors.CommandExecutor;
import redis.clients.jedis.providers.*;

/** Integrates with Jedis 8.0.2's public provider and protected single-dispatch extension points. */
final class TopologyConnections {
  static Connection borrow(ConnectionPool pool, Duration timeout) {
    try {
      Connection connection =
          pool.borrowObject(Duration.ofMillis(TopologyCallBudget.millis(timeout)));
      // ConnectionPool.getResource normally installs this owner after borrowObject.
      connection.setHandlingPool(pool);
      return connection;
    } catch (java.util.NoSuchElementException exhausted) {
      TopologyCallBudget.millis(timeout);
      throw new ClientException(
          ClientException.Kind.OVERLOADED, "Topology connection pool is exhausted", exhausted);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ClientException(
          ClientException.Kind.CLOSED, "Topology connection borrow interrupted", interrupted);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new JedisConnectionException("Topology connection borrow failed", failure);
    }
  }

  static <T> T dispatch(Connection connection, CommandObject<T> command, Duration timeout) {
    if (command.getArguments().isBlocking())
      throw new ClientException(
          ClientException.Kind.INVALID_REQUEST,
          "Blocking commands require a dedicated native binding");
    int original = connection.getSoTimeout();
    try {
      connection.setSoTimeout(TopologyCallBudget.millis(timeout));
      return connection.executeCommand(command);
    } catch (JedisConnectionException failure) {
      connection.setBroken();
      // ClusterCommandExecutor immediately propagates this category instead of its socket retry
      // loop.
      throw new AfterDispatchFailure(failure);
    } finally {
      if (!connection.isBroken())
        try {
          connection.setSoTimeout(original);
        } catch (RuntimeException resetFailure) {
          connection.setBroken();
        }
    }
  }

  static final class AfterDispatchFailure extends JedisClusterOperationException {
    AfterDispatchFailure(JedisConnectionException cause) {
      super("Topology connection failed during command dispatch", cause);
    }
  }

  static final class Cluster extends ClusterConnectionProvider {
    private final Duration borrowTimeout, readTimeout;

    Cluster(
        Set<HostAndPort> hosts,
        JedisClientConfig config,
        GenericObjectPoolConfig<Connection> pools,
        Duration borrowTimeout,
        Duration readTimeout) {
      super(hosts, config, pools);
      this.borrowTimeout = borrowTimeout;
      this.readTimeout = readTimeout;
    }

    @Override
    public Connection getConnection(HostAndPort host) {
      if (host == null) return getConnection();
      return borrow(cache.setupNodeIfNotExist(host), borrowTimeout);
    }

    @Override
    public Connection getConnectionFromSlot(int slot) {
      ConnectionPool pool = cache.getSlotPool(slot);
      if (pool == null) {
        renewSlotCache();
        pool = cache.getSlotPool(slot);
      }
      return pool == null ? getConnection() : borrow(pool, borrowTimeout);
    }

    @Override
    public Connection getConnection() {
      List<ConnectionPool> pools = cache.getShuffledPrimaryNodesPool();
      if (pools.isEmpty()) throw new JedisClusterOperationException("No primary node is known");
      return borrow(pools.get(0), borrowTimeout);
    }

    @Override
    public void renewSlotCache() {
      // Native renewClusterSlots has a hidden fallback that borrows node pools without our budget.
      // Refresh exactly one bounded connection; a failed refresh ends this call instead.
      try (Connection connection = getConnection()) {
        renewSlotCache(connection);
      }
    }

    @Override
    public void renewSlotCache(Connection connection) {
      if (connection == null) {
        renewSlotCache();
        return;
      }
      int original = connection.getSoTimeout();
      try {
        connection.setSoTimeout(TopologyCallBudget.millis(readTimeout));
        cache.discoverClusterNodesAndSlots(connection);
      } finally {
        if (!connection.isBroken())
          try {
            connection.setSoTimeout(original);
          } catch (RuntimeException failure) {
            connection.setBroken();
          }
      }
    }
  }

  static final class Sentinel extends SentineledConnectionProvider {
    private final Duration borrowTimeout;

    Sentinel(
        String master,
        JedisClientConfig data,
        GenericObjectPoolConfig<Connection> pools,
        Set<HostAndPort> sentinels,
        JedisClientConfig sentinel,
        Duration borrowTimeout) {
      super(master, data, pools, sentinels, sentinel);
      this.borrowTimeout = borrowTimeout;
    }

    @Override
    public Connection getConnection() {
      Object pool = getPrimaryNodesConnectionMap().values().iterator().next();
      if (!(pool instanceof ConnectionPool connectionPool))
        throw new JedisConnectionException("Sentinel did not provide a data-node pool");
      Connection connection = borrow(connectionPool, borrowTimeout);
      if (!Objects.equals(connection.getHostAndPort(), getCurrentMaster())) {
        connection.close();
        throw new JedisConnectionException("Sentinel primary changed before dispatch");
      }
      return connection;
    }
  }

  static CommandExecutor clusterExecutor(
      Cluster provider, RedisTopologySettings settings, Runnable credentials) {
    return new ClusterCommandExecutor(provider, 3, settings.readTimeout()) {
      @Override
      protected <T> T execute(Connection connection, CommandObject<T> command) {
        credentials.run();
        return dispatch(connection, command, settings.readTimeout());
      }
    };
  }

  static CommandExecutor sentinelExecutor(
      Sentinel provider, RedisTopologySettings settings, Runnable credentials) {
    return new CommandExecutor() {
      public <T> T executeCommand(CommandObject<T> command) {
        credentials.run();
        try (Connection connection = provider.getConnection(command.getArguments())) {
          return dispatch(connection, command, settings.readTimeout());
        }
      }

      public void close() {
        provider.close();
      }
    };
  }
}
