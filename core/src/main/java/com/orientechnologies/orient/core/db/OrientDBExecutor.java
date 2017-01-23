package com.orientechnologies.orient.core.db;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by tglman on 23/01/17.
 */
public interface OrientDBExecutor extends Closeable {

  static OrientDBExecutor databaseExecutor(String url) {
    return new OrientDBExecutorImpl(ODatabasePool.pool(url),
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
  }

  static OrientDBExecutor databaseExecutor(ODatabasePool pool, ExecutorService executor) {
    return new OrientDBExecutorImpl(pool, executor);
  }

  <T> Future<T> submit(ODatabaseCallable<T> callable);

  void close();
}
