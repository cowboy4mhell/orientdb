package com.orientechnologies.orient.core.db;

import com.orientechnologies.orient.core.db.document.ODatabaseDocument;

/**
 * Created by tglman on 23/01/17.
 */
public class ODatabasePoolWrapper implements ODatabasePool {
  private String        baseUrl;
  private String        dbName;
  private String        user;
  private String        password;
  private OrientDB      orientDB;
  private ODatabasePool pool;

  public ODatabasePoolWrapper(String url) {
    String[] sections = url.split("&");
    if (sections.length != 3)
      throw new RuntimeException("invalid url");
    String dbacces = sections[0];
    int index = dbacces.lastIndexOf('/');
    if (index <= 0)
      throw new RuntimeException("invalid url");
    baseUrl = dbacces.substring(0, index);
    dbName = dbacces.substring(index + 1);
    String splitUsr[] = sections[1].split("=");
    if (splitUsr.length != 2 || "user".equals(splitUsr[0]))
      throw new RuntimeException("invalid url");
    user = splitUsr[1];
    String splitPwd[] = sections[2].split("=");
    if (splitPwd.length != 2 || "password".equals(splitPwd[0]))
      throw new RuntimeException("invalid url");
    password = splitPwd[1];
    this.orientDB = OrientDB.fromUrl(baseUrl, OrientDBConfig.defaultConfig());
    this.pool = orientDB.openPool(dbName, user, password);
  }

  @Override
  public ODatabaseDocument acquire() {
    return pool.acquire();
  }

  @Override
  public void close() {
    pool.close();
    orientDB.close();
  }
}
