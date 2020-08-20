/*
Copyright 2019-2020 vChain, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.codenotary.immudb4j;

import com.google.common.base.Charsets;

import java.util.LinkedList;
import java.util.List;

public class KVList {

    private List<KV> kvList;

    private KVList(KVListBuilder builder) {
        kvList = builder.kvList;
    }

    public List<KV> entries() {
        return this.kvList;
    }

    public static KVListBuilder newBuilder() {
        return new KVListBuilder();
    }

    public static class KVListBuilder {
        private List<KV> kvList;

        private KVListBuilder() {
            kvList = new LinkedList<>();
        }

        public KVList build() {
            return new KVList(this);
        }

        public KVListBuilder add(String key, byte[] value) {
            add(key.getBytes(Charsets.UTF_8), value);
            return this;
        }

        public KVListBuilder add(byte[] key, byte[] value) {
            add(new KVPair(key, value));
            return this;
        }

        public KVListBuilder add(KV kv) {
            this.kvList.add(kv);
            return this;
        }

        public KVListBuilder addAll(List<KV> kvs) {
            for (KV kv : kvs) {
                add(kv);
            }
            return this;
        }
    }

    private static final class KVPair implements KV {

        private byte[] key;
        private byte[] value;

        private KVPair(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public byte[] getKey() {
            return this.key;
        }

        @Override
        public byte[] getValue() {
            return this.value;
        }
    }
}