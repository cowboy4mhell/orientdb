package com.orientechnologies.orient.core.db;

import com.orientechnologies.orient.core.db.document.ODatabaseDocument;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Created by tglman on 23/01/17.
 */
public class OrientDBExecutorImpl implements OrientDBExecutor {

  private final ExecutorService executor;
  private       ODatabasePool   pool;

  public OrientDBExecutorImpl(ODatabasePool pool, ExecutorService executor) {
    this.pool = pool;
    this.executor = executor;
  }

  public <T> Future<T> submit(ODatabaseCallable<T> callable) {
    return executor.submit(() -> {
      try (ODatabaseDocument database = pool.acquire()) {
        return callable.call(database);
      }
    });
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      // TODO:use a configuration
      executor.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.interrupted();
    }
    pool.close();
  }
}
