package com.orientechnologies.orient.core.db;

import com.orientechnologies.orient.core.db.document.ODatabaseDocument;

/**
 * Created by tglman on 23/01/17.
 */
public interface ODatabaseCallable<T> {
  <T> T call(ODatabaseDocument database);
}
