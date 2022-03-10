/**
 * Copyright (c) 2012 - 2015 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.yahoo.ycsb.db;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import com.yahoo.ycsb.ByteArrayByteIterator;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.StringByteIterator;

import oracle.jdbc.OracleConnection;
import oracle.soda.OracleCollection;
import oracle.soda.OracleCursor;
import oracle.soda.OracleDatabase;
import oracle.soda.OracleDocument;
import oracle.soda.OracleException;
import oracle.soda.OracleOperationBuilder;
import oracle.soda.rdbms.OracleRDBMSClient;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonGenerator;
import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;
import oracle.sql.json.OracleJsonValue.OracleJsonType;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import site.ycsb.Client;

/**
 * SODA adapter for YCSB. 
 */
public class SodaClient extends DB {

  private static volatile boolean fInitialized = false;

  private OracleJsonFactory factory;
  
  private final static Object pdsLock = new Object();

  private static PoolDataSource PDS;

  private static boolean poolCreated = false;

  boolean validateConn = true;

  int stmtCacheSize = 30;
  
  /** Reusable output buffer. */
  private ByteArrayOutputStream baos;

  private static OracleRDBMSClient CLIENT;

  public SodaClient() {

  }

  @Override
  public void init() throws DBException {
    factory = new OracleJsonFactory();
    baos = new ByteArrayOutputStream();
    Properties props = getProperties();
    int poolSize = Integer.parseInt(props.getProperty("soda.cpoolsz"));
    String url = props.getProperty("db.url");
    if (url == null) {
      throw new IllegalStateException("db.url not specified");
    }
    String usr = props.getProperty("db.user");
    String pwd = props.getProperty("db.password");
    int batchSize = Integer.parseInt(props.getProperty("batchsize", "1"));
    if (batchSize != 1) {
      throw new UnsupportedOperationException(); // todo
    }

    try {
      synchronized (pdsLock) {
        if (!poolCreated) {
          PDS = PoolDataSourceFactory.getPoolDataSource();
          PDS.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
          PDS.setURL(url);
          if (usr != null) {
            PDS.setUser(usr);
          }
          if (pwd != null) {
            PDS.setPassword(pwd);
          }
          PDS.setInitialPoolSize(poolSize);
          PDS.setMinPoolSize(poolSize);
          PDS.setMaxPoolSize(poolSize);
          PDS.setMaxStatements(stmtCacheSize);
          PDS.setValidateConnectionOnBorrow(validateConn);
          int reuse = 5000;
          int idleTime = 1;
          PDS.setSecondsToTrustIdleConnection(idleTime);
          PDS.setMaxConnectionReuseCount(reuse);
          PDS.setMaxConnectionReuseTime(900);
          System.out.println("Connection Reuse:" + reuse + " Idle:" + idleTime);
          System.out.println("Validate Connections:" + validateConn);
          Properties connProps = new Properties();
          connProps.put(OracleConnection.CONNECTION_PROPERTY_AUTOCOMMIT, "true");
          PDS.setConnectionProperties(connProps);
          poolCreated = true;

          Properties cprops = new Properties();
          cprops.put("oracle.soda.sharedMetadataCache", "true");
          cprops.put("oracle.soda.localMetadataCache", "true");
          CLIENT = new OracleRDBMSClient(cprops);
        } else {
          System.out.println("Pool already exists");
        }
      }

      String table = props.getProperty("table");
      boolean fLoad = !Boolean.parseBoolean(props.getProperty(Client.DO_TRANSACTIONS_PROPERTY));
      try(OracleConnection con = (OracleConnection) this.getConnection()) {
        OracleDatabase db = CLIENT.getDatabase(con);
        if (!fInitialized && fLoad) {
          OracleCollection col = db.openCollection(table);
          if (col != null) {
            col.admin().drop();
          }
          fInitialized = true;
        }
        ensureCollection(db, table);
      }
    } catch (OracleException | SQLException e) {
      throw new DBException(e);
    }
  }

  @Override
  public void cleanup() throws DBException {
    /*
    try {
      con.close();
    } catch (SQLException e) {
      throw new DBException(e);
    }
    */
  }

@Override
public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
  OracleConnection con = null;
    try {
      con = (OracleConnection) this.getConnection();
      OracleDatabase db = CLIENT.getDatabase(con);

      OracleCollection collection = db.openCollection(table);
      OracleDocument res = collection.find().key(key).getOne();
      if (fields != null && !fields.isEmpty()) {
        throw new UnsupportedOperationException(); // todo
      }
      if (res == null) {
        throw new IllegalStateException("read returned no document:" + key);
      }
      else {
        documentToMap(res, result);
      }
      return Status.OK;
    } catch (OracleException e) {
      e.printStackTrace();
      return Status.ERROR;
    } finally {
      this.closeConnection(con);
    }
  }

  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    OracleConnection con = null;
    try {
      con = (OracleConnection) this.getConnection();
      OracleDatabase db = CLIENT.getDatabase(con);

      OracleCollection collection = db.openCollection(table);
      OracleOperationBuilder builder = collection.find();
      if (startkey != null) {
        builder.startKey(startkey, true, true);
      }
      if (fields != null) {
        throw new IllegalStateException("TODO needs verification this works as expected");
      }
      builder.limit(recordcount);

      try (OracleCursor cursor = builder.getCursor()) {
        while (cursor.hasNext()) {
          OracleDocument document = cursor.next();
          HashMap<String, ByteIterator> resultMap = new HashMap<String, ByteIterator>();
          documentToMap(document, resultMap);
          result.add(resultMap);
        }
      }

      if (result.size() == 0) {
        throw new IllegalStateException("scan() produced 0 results.");
      }
      return Status.OK;
    } catch (OracleException | IOException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
    finally {
      this.closeConnection(con);
    }
  }

  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    OracleConnection con = null;
    try {
      con = (OracleConnection) this.getConnection();
      OracleDatabase db = CLIENT.getDatabase(con);
      OracleCollection collection = db.openCollection(table);
      OracleDocument upd = mapToDocument(db, key, values, false);
      collection.find().key(key).mergeOne(upd);
      con.commit();
      return Status.OK;
    } catch (Throwable e) {
      e.printStackTrace();
      this.rollback(con);
      return Status.ERROR;
    } finally {
      this.closeConnection(con);
    }
  }
  
  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    OracleConnection con = null;
    try {
      con = (OracleConnection) this.getConnection();
      OracleDatabase db = CLIENT.getDatabase(con);
      OracleCollection collection = db.openCollection(table);
      OracleDocument doc = mapToDocument(db, key, values, true);
      if (doc == null) {
        throw new IllegalStateException("Inserting a null doc");
      }
      collection.insert(doc);
      con.commit();
      return Status.OK;
    } catch (Throwable e) {
      e.printStackTrace();
      this.rollback(con);
      return Status.ERROR;
    } finally {
      closeConnection(con);
    }
  }

  @Override
  public Status delete(String table, String key) {
    OracleConnection con = null;
    try {
      con = (OracleConnection) this.getConnection();
      OracleDatabase db = CLIENT.getDatabase(con);
      OracleCollection collection = db.openCollection(table);
      int count = collection.find().key(key).remove();
      if (count == 0) {
        System.out.println("Key to delete not found");
        return Status.NOT_FOUND;
      }
      con.commit();
      return Status.OK;
    } catch (Throwable e) {
      e.printStackTrace();
      this.rollback(con);
      return Status.ERROR;
    } finally {
      closeConnection(con);
    }
  }

  private static OracleCollection ensureCollection(OracleDatabase db, String name)
          throws OracleException
      {
      OracleCollection col = db.openCollection(name);
      if (col == null)
          {
          String metadata_string = "{\"keyColumn\" : {\"name\" : \"ID\",\"sqlType\" : \"VARCHAR2\",\"maxLength\" : 255,\"assignmentMethod\" : \"CLIENT\"}, \"contentColumn\" : {\"name\" : \"JSON_DOCUMENT\",\"sqlType\" : \"BLOB\"}, \"versionColumn\" : {\"name\" : \"VERSION\",\"method\" : \"UUID\"}, \"lastModifiedColumn\" : {\"name\" : \"LAST_MODIFIED\"},\"creationTimeColumn\" : {\"name\" : \"CREATED_ON\"},\"readOnly\" : false}";
          OracleDocument metadata = db.createDocumentFromString(metadata_string);
          System.out.println("Using Metadata:" + metadata.getContentAsString());
          col = db.admin().createCollection(name, metadata);
          }
      return col;
      }

  private void documentToMap(OracleDocument res, Map<String, ByteIterator> result) throws OracleException {
    OracleJsonObject obj = res.getContentAs(OracleJsonObject.class);
    for (String key : obj.keySet()) {
      OracleJsonValue v = obj.get(key);
      if (v.getOracleJsonType() == OracleJsonType.STRING) {
        String value = v.asJsonString().getString();
        result.put(key, new StringByteIterator(value));
      } else {
        byte[] value = v.asJsonBinary().getBytes();
        result.put(key,  new ByteArrayByteIterator(value));
      }
    }
  }

  private OracleDocument mapToDocument(OracleDatabase db, String key, Map<String, ByteIterator> values, boolean useBinary) throws OracleException {
    baos.reset();
    OracleJsonGenerator gen = useBinary ? 
        factory.createJsonBinaryGenerator(baos) :
        factory.createJsonTextGenerator(baos);
    gen.writeStartObject();
    for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
       gen.write(entry.getKey(), entry.getValue().toArray());
    }
    gen.writeEnd();
    gen.close();
    return useBinary ?
        db.createDocumentFrom(key, baos.toByteArray()) :
        db.createDocumentFromByteArray(key, baos.toByteArray());
  }

  private Connection getConnection()
    {
    try
      {
      Connection con = PDS.getConnection();
      con.setAutoCommit(false);
      return con;
      }
    catch (SQLException e)
      {
      throw new RuntimeException(e);
      }
    }

  private void closeConnection(Connection con)
    {
    try
      {
      if (con != null)
        {
        con.close();
        }
      }
    catch (SQLException e)
      {
      throw new RuntimeException(e);
      }
    }

  private void rollback(Connection con) {
    try {
      con.rollback();
    } catch (SQLException var3) {
    }
    }
}
