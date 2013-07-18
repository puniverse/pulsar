/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.pulsar.galaxy;

import clojure.lang.BigInt;
import clojure.lang.IPersistentList;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.PersistentQueue;
import clojure.lang.PersistentTreeMap;
import clojure.lang.PersistentTreeSet;
import clojure.lang.PersistentVector;
import co.paralleluniverse.io.serialization.KryoSerializer;
import co.paralleluniverse.remote.galaxy.RemoteInit;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author pron
 */
public class ClojureKryoSerializers {
    static {
        RemoteInit.init();

        KryoSerializer.register(Keyword.class, new KeywordSerializer());
        KryoSerializer.register(BigInt.class, new BigIntSerializer());
        KryoSerializer.register(IPersistentVector.class, new VectorSerializer());
        KryoSerializer.register(IPersistentList.class, new ListSerializer());
        KryoSerializer.register(IPersistentList.class, new ListSerializer());
        KryoSerializer.register(PersistentQueue.class, new PersistentQueueSerializer());
        KryoSerializer.register(PersistentHashSet.class, new HashSetSerializer());
        KryoSerializer.register(PersistentTreeSet.class, new TreeSetSerializer());
        KryoSerializer.register(PersistentArrayMap.class, new ArrayMapSerializer());
        KryoSerializer.register(PersistentHashMap.class, new HashMapSerializer());
        KryoSerializer.register(PersistentTreeMap.class, new TreeMapSerializer());
    }
    
    private static abstract class ClojureSerializer<T> extends Serializer<T> {
        public ClojureSerializer() {
            super(false, // accepts null
                    true); // immutable
        }
    }

    private static class KeywordSerializer extends ClojureSerializer<Keyword> {
        @Override
        public void write(Kryo kryo, Output output, Keyword k) {
            output.writeString(k.getNamespace());
            output.writeString(k.getName());
        }

        @Override
        public Keyword read(Kryo kryo, Input input, Class<Keyword> type) {
            String ns = input.readString();
            String name = input.readString();
            if (ns != null)
                return Keyword.intern(ns, name);
            else
                return Keyword.intern(name);
        }
    }

    private static class BigIntSerializer extends ClojureSerializer<BigInt> {
        @Override
        public void write(Kryo kryo, Output output, BigInt k) {
            kryo.writeObjectOrNull(output, k.toBigInteger(), BigInteger.class);
        }

        @Override
        public BigInt read(Kryo kryo, Input input, Class<BigInt> type) {
            final BigInteger bi = kryo.readObjectOrNull(input, BigInteger.class);
            if(bi == null)
                return null;
            return BigInt.fromBigInteger(bi);
        }
    }

    private static abstract class CollSerializer<T> extends ClojureSerializer<T> {
        @Override
        public void write(Kryo kryo, Output output, T obj) {
            final Collection coll = (Collection) obj;
            output.writeInt(coll.size());
            for (Object e : (Collection) coll)
                kryo.writeClassAndObject(output, e);
        }
    }

    private static class VectorSerializer extends CollSerializer<IPersistentVector> {
        @Override
        public IPersistentVector read(Kryo kryo, Input input, Class<IPersistentVector> type) {
            final int size = input.readInt();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++)
                array[i] = kryo.readClassAndObject(input);
            return PersistentVector.create(array);
        }
    }

    private static class ListSerializer extends CollSerializer<IPersistentList> {
        @Override
        public IPersistentList read(Kryo kryo, Input input, Class<IPersistentList> type) {
            final int size = input.readInt();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++)
                array[i] = kryo.readClassAndObject(input);
            return PersistentList.create(Arrays.asList(array));
        }
    }

    private static class PersistentQueueSerializer extends CollSerializer<PersistentQueue> {
        @Override
        public PersistentQueue read(Kryo kryo, Input input, Class<PersistentQueue> type) {
            final int size = input.readInt();
            PersistentQueue q = PersistentQueue.EMPTY;
            for (int i = 0; i < size; i++)
                q.cons(kryo.readClassAndObject(input));
            return q;
        }
    }

    private static class HashSetSerializer extends CollSerializer<IPersistentSet> {
        @Override
        public IPersistentSet read(Kryo kryo, Input input, Class<IPersistentSet> type) {
            final int size = input.readInt();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++)
                array[i] = kryo.readClassAndObject(input);
            return PersistentHashSet.create(array);
        }
    }

    private static class TreeSetSerializer extends CollSerializer<IPersistentSet> {
        @Override
        public IPersistentSet read(Kryo kryo, Input input, Class<IPersistentSet> type) {
            final int size = input.readInt();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++)
                array[i] = kryo.readClassAndObject(input);
            return PersistentTreeSet.create(PersistentList.create(Arrays.asList(array)).seq());
        }
    }

    private static abstract class MapSerializer extends ClojureSerializer<IPersistentMap> {
        @Override
        public void write(Kryo kryo, Output output, IPersistentMap m1) {
            Map m = (Map) m1;

            output.writeInt(m.size());

            for (Map.Entry e : (Set<Map.Entry>) m.entrySet()) {
                kryo.writeClassAndObject(output, e.getKey());
                kryo.writeClassAndObject(output, e.getValue());
            }
        }
    }

    private static class ArrayMapSerializer extends MapSerializer {
        @Override
        public IPersistentMap read(Kryo kryo, Input input, Class<IPersistentMap> type) {
            final int size = input.readInt();
            Object[] array = new Object[size * 2];
            for (int i = 0; i < array.length; i++)
                array[i] = kryo.readClassAndObject(input);
            return new PersistentArrayMap(array);
        }
    }

    private static class HashMapSerializer extends MapSerializer {
        @Override
        public IPersistentMap read(Kryo kryo, Input input, Class<IPersistentMap> type) {
            final int size = input.readInt();
            HashMap map = new HashMap(size);
            for (int i = 0; i < size; i++)
                map.put(kryo.readClassAndObject(input), kryo.readClassAndObject(input));

            return PersistentHashMap.create(map);
        }
    }

    private static class TreeMapSerializer extends MapSerializer {
        @Override
        public IPersistentMap read(Kryo kryo, Input input, Class<IPersistentMap> type) {
            final int size = input.readInt();
            HashMap map = new HashMap(size);
            for (int i = 0; i < size; i++)
                map.put(kryo.readClassAndObject(input), kryo.readClassAndObject(input));

            return PersistentTreeMap.create(map);
        }
    }
}
