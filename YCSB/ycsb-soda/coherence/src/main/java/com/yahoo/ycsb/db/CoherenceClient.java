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

import com.yahoo.ycsb.ByteArrayByteIterator;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.StringByteIterator;
import java.sql.SQLException;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.pool.OracleDataSource;
import oracle.soda.OracleCollection;
import oracle.soda.OracleDatabase;
import oracle.soda.OracleDocument;
import oracle.soda.OracleException;
import oracle.soda.rdbms.OracleRDBMSClient;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;
import oracle.sql.json.OracleJsonValue.OracleJsonType;
import site.ycsb.Client;

import com.tangosol.net.CoherenceSession;
import com.tangosol.net.NamedCache;

import com.tangosol.util.BinaryEntry;
import com.tangosol.util.InvocableMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * Coherence adapter for YCSB.
 */
public class CoherenceClient
        extends DB
    {
    private static volatile boolean fInitialized = false;

    private OracleJsonFactory factory;

    private CoherenceSession session;

    public CoherenceClient()
        {

        }

    @Override
    public void init() throws DBException
        {
        session = new CoherenceSession();
        factory = new OracleJsonFactory();
        Properties props = getProperties();
        int batchSize = Integer.parseInt(props.getProperty("batchsize", "1"));
        if (batchSize != 1)
            {
            throw new UnsupportedOperationException(); // todo
            }

        try
            {
            String usr = props.getProperty("db.user");
            String pwd = props.getProperty("db.password");
            String url = props.getProperty("db.url");
            if (url == null)
                {
                throw new IllegalStateException("db.url not specified");
                }
            OracleDataSource ds = new OracleDataSource();
            if (usr != null)
                {
                ds.setUser(usr);
                }
            if (pwd != null)
                {
                ds.setPassword(pwd);
                }
            ds.setURL(url);

            OracleConnection con = (OracleConnection) ds.getConnection();
            Properties cprops = new Properties();
            cprops.put("oracle.soda.sharedMetadataCache", "true");
            cprops.put("oracle.soda.localMetadataCache", "true");
            OracleRDBMSClient client = new OracleRDBMSClient(cprops);

            OracleDatabase db = client.getDatabase(con);

            String table = props.getProperty("table");
            boolean fLoad = !Boolean.parseBoolean(props.getProperty(Client.DO_TRANSACTIONS_PROPERTY));
            if (!fInitialized && fLoad)
                {
                clearTable(db, table);
                fInitialized = true;
                }
            ensureCollection(db, table);
            }
        catch (OracleException | SQLException e)
            {
            throw new DBException(e);
            }

        }

    private void clearTable(OracleDatabase db, String table)
            throws OracleException
        {
        OracleCollection col = db.openCollection(table);
        if (col != null)
            {
            clearCoherenceCache(table);
            col.admin().drop();
            }
        }

    private void clearCoherenceCache(String sName)
        {
        NamedCache<String, OracleJsonValue> cache = session.getCache(sName);
        if (cache != null)
            {
            // TODO: clearing cache is slow as it removes items from entry store one by one
            //cache.clear();
            cache.destroy();
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
            db.admin().createCollection(name, metadata);
            col = db.admin().createCollection(name, metadata);
            }
        return col;
        }

    @Override
    public void cleanup() throws DBException
        {
        try
            {
            session.close();
            }
        catch (Exception e)
            {
            throw new DBException(e);
            }
        }

    @Override
    public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result)
        {
        try
            {
            NamedCache<String, OracleJsonValue> cache = session.getCache(table);
            OracleJsonValue res = cache.get(key);

            if (fields != null && !fields.isEmpty())
                {
                throw new UnsupportedOperationException(); // todo
                }
            if (res == null)
                {
                throw new IllegalStateException("read returned no document:" + key);
                }
            else
                {
                jsonValueToMap(res, result);
                }
            return Status.OK;
            }
        catch (Exception e)
            {
            e.printStackTrace();
            return Status.ERROR;
            }
        }

    @Override
    public Status scan(String table, String startkey, int recordcount, Set<String> fields, Vector<HashMap<String, ByteIterator>> result)
        {
        return Status.NOT_IMPLEMENTED;
        }

    @Override
    public Status update(String table, String key, Map<String, ByteIterator> values)
        {
        try
            {
            HashMap<String, byte[]> arrays = new HashMap<>();
            for (Map.Entry<String, ByteIterator> updateEntry : values.entrySet())
                {
                byte[] varray = updateEntry.getValue().toArray();
                arrays.put(updateEntry.getKey(), varray);
                }

            NamedCache<String, OracleJsonValue> cache = session.getCache(table);
            cache.invoke(key, (InvocableMap.EntryProcessor<String, OracleJsonValue, Object>) entry ->
                {
                BinaryEntry<String, OracleJsonValue> binEntry = (BinaryEntry<String, OracleJsonValue>) entry;
                OracleJsonValue trade = binEntry.getValue();

                OracleJsonFactory factory = new OracleJsonFactory();
                OracleJsonObject obj = factory.createObject(trade.asJsonObject());
                for (Map.Entry<String, byte[]> updateEntry : arrays.entrySet())
                    {
                    obj.put(updateEntry.getKey(), updateEntry.getValue());
                    }

                binEntry.setValue(obj);
                });

            return Status.OK;
            }
        catch (Exception e)
            {
            e.printStackTrace();
            return Status.ERROR;
            }
        }

    @Override
    public Status insert(String table, String key, Map<String, ByteIterator> values)
        {
        try
            {
            NamedCache<String, OracleJsonValue> cache = session.getCache(table);
            cache.put(key, jsonValue);
            return Status.OK;
            }
        catch (Exception e)
            {
            e.printStackTrace();
            return Status.ERROR;
            }
        }

    @Override
    public Status delete(String table, String key)
        {
        try
            {
            NamedCache<String, OracleJsonValue> cache = session.getCache(table);
            OracleJsonValue oldVal = cache.remove(key);
            if (oldVal == null)
                {
                System.out.println("Key to delete not found");
                return Status.NOT_FOUND;
                }
            return Status.OK;
            }
        catch (Exception e)
            {
            e.printStackTrace();
            return Status.ERROR;
            }
        }

    private void jsonValueToMap(OracleJsonValue res, Map<String, ByteIterator> result)
        {
        OracleJsonObject obj = res.asJsonObject();
        for (String key : obj.keySet())
            {
            OracleJsonValue v = obj.get(key);
            if (v.getOracleJsonType() == OracleJsonType.STRING)
                {
                String value = v.asJsonString().getString();
                result.put(key, new StringByteIterator(value));
                }
            else
                {
                byte[] value = v.asJsonBinary().getBytes();
                result.put(key, new ByteArrayByteIterator(value));
                }
            }
        }

    private OracleJsonValue mapToJsonValue(Map<String, ByteIterator> values)
        {
        OracleJsonObject jsonObject = factory.createObject();
        for (Map.Entry<String, ByteIterator> entry : values.entrySet())
            {
            jsonObject.put(entry.getKey(), entry.getValue().toArray());
            }
        return jsonObject;
        }
    }
