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

import com.oracle.coherence.jsondb.OracleJsonValueSerializer;
import com.yahoo.ycsb.ByteArrayByteIterator;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.StringByteIterator;

import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;
import oracle.sql.json.OracleJsonValue.OracleJsonType;

import site.ycsb.Client;

import com.tangosol.net.Coherence;
import com.tangosol.net.NamedCache;
import com.tangosol.net.Session;

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
    private static final Object OBJECT = new Object();
    private static final OracleJsonFactory JSON_FACTORY = new OracleJsonFactory();

    private static volatile boolean fInitialized = false;

    private Session session;

    public CoherenceClient()
        {

        }

    @Override
    public void init()
        {
        Properties props;
        synchronized (OBJECT)
            {
            Coherence coherence = Coherence.getInstance();
            if (coherence == null)
                {
                Coherence.clusterMember().start().join();
                }
            session = Coherence.getInstance().getSession();
            props = getProperties();
            int batchSize = Integer.parseInt(props.getProperty("batchsize", "1"));
            if (batchSize != 1)
                {
                throw new UnsupportedOperationException(); // todo
                }
            }

        String table = props.getProperty("table");
        boolean fLoad = !Boolean.parseBoolean(props.getProperty(Client.DO_TRANSACTIONS_PROPERTY));
        if (!fInitialized && fLoad)
            {
            clearCoherenceCache(table);
            fInitialized = true;
            }
        }

    private void clearCoherenceCache(String sName)
        {
        NamedCache<String, OracleJsonValue> cache = session.getCache(sName);
        if (cache != null)
            {
            cache.truncate();
            }
        }

    @Override
    public void cleanup() throws DBException
        {
        try
            {
            // TODO: the last client thread should close session
            //session.close();
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
            HashMap<String, byte[]> updates = new HashMap<>();
            for (Map.Entry<String, ByteIterator> updateEntry : values.entrySet())
                {
                updates.put(updateEntry.getKey(), updateEntry.getValue().toArray());
                }

            NamedCache<String, OracleJsonValue> cache = session.getCache(table);
            cache.invoke(key, (InvocableMap.EntryProcessor<String, OracleJsonValue, Object>) entry ->
                {
                OracleJsonObject value = entry.getValue().asJsonObject();

                OracleJsonFactory factory = OracleJsonValueSerializer.FACTORY;
                OracleJsonObject newValue = factory.createObject(value);

                for (Map.Entry<String, byte[]> update : updates.entrySet())
                    {
                    newValue.put(update.getKey(), update.getValue());
                    }

                entry.setValue(newValue);
                return null;
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
            OracleJsonValue jsonValue = mapToJsonValue(values);
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
        OracleJsonObject jsonObject = JSON_FACTORY.createObject();
        for (Map.Entry<String, ByteIterator> entry : values.entrySet())
            {
            jsonObject.put(entry.getKey(), entry.getValue().toArray());
            }
        return jsonObject;
        }
    }
