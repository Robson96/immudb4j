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
package io.codenotary.immudb;

import com.google.common.base.Charsets;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.codenotary.immudb.crypto.CryptoUtils;
import io.codenotary.immudb.crypto.Root;
import io.codenotary.immudb.crypto.VerificationException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * immudb client using grpc.
 *
 * @author Jeronimo Irazabal
 */
public class ImmuClient {

  private static final String AUTH_HEADER = "authorization";

  private static final Logger LOG = LoggerFactory.getLogger(ImmuClient.class);

  private ImmuServiceGrpc.ImmuServiceBlockingStub stub;

  private boolean withAuthToken;
  private String authToken;

  private RootHolder rootHolder;

  public ImmuClient(ImmuClientBuilder builder) throws NoSuchAlgorithmException {
    this.stub = createStubFrom(builder);
    this.withAuthToken = builder.isWithAuthToken();
    this.rootHolder = builder.getRootHolder();
  }

  private ImmuServiceGrpc.ImmuServiceBlockingStub createStubFrom(ImmuClientBuilder builder) {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(builder.getServerUrl(), builder.getServerPort())
            .usePlaintext()
            .build();
    return ImmuServiceGrpc.newBlockingStub(channel);
  }

  private ImmuServiceGrpc.ImmuServiceBlockingStub getStub() {
    if (!withAuthToken || authToken == null) {
      return stub;
    }

    Metadata metadata = new Metadata();
    metadata.put(
        Metadata.Key.of(AUTH_HEADER, Metadata.ASCII_STRING_MARSHALLER), "Bearer " + authToken);

    return MetadataUtils.attachHeaders(stub, metadata);
  }

  public static class ImmuClientBuilder {

    private String serverUrl;

    private int serverPort;

    private boolean withAuthToken;

    private RootHolder rootHolder;

    public static ImmuClientBuilder newBuilder(String serverUrl, int serverPort) {
      return new ImmuClientBuilder(serverUrl, serverPort);
    }

    private ImmuClientBuilder(String serverUrl, int serverPort) {
      this.serverUrl = serverUrl;
      this.serverPort = serverPort;
      this.rootHolder = new TransientRootHolder();
      this.withAuthToken = true;
    }

    public ImmuClient build() {
      try {
        return new ImmuClient(this);
      } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
      }
    }

    public String getServerUrl() {
      return this.serverUrl;
    }

    public int getServerPort() {
      return serverPort;
    }

    public ImmuClientBuilder setWithAuthToken(boolean withAuthToken) {
      this.withAuthToken = withAuthToken;
      return this;
    }

    public boolean isWithAuthToken() {
      return withAuthToken;
    }

    public ImmuClientBuilder setRootHolder(RootHolder rootHolder) {
      this.rootHolder = rootHolder;
      return this;
    }

    public RootHolder getRootHolder() {
      return rootHolder;
    }
  }

  public void login(String username, String password) {
    ImmudbProto.LoginRequest loginRequest =
        ImmudbProto.LoginRequest.newBuilder()
            .setUser(ByteString.copyFrom(username, Charsets.UTF_8))
            .setPassword(ByteString.copyFrom(password, Charsets.UTF_8))
            .build();

    ImmudbProto.LoginResponse loginResponse = getStub().login(loginRequest);
    authToken = loginResponse.getToken();
  }

  public void logout() {
    getStub().logout(com.google.protobuf.Empty.getDefaultInstance());
    authToken = null;
  }

  public Root root() {
    if (rootHolder.getRoot() == null) {
      Empty empty = com.google.protobuf.Empty.getDefaultInstance();
      ImmudbProto.Root r = getStub().currentRoot(empty);
      Root root = new Root(r.getIndex(), r.getRoot().toByteArray());
      rootHolder.SetRoot(root);
    }
    return rootHolder.getRoot();
  }

  public void set(String key, byte[] value) {
    set(key.getBytes(Charsets.UTF_8), value);
  }

  public void set(byte[] key, byte[] value) {
    ImmudbProto.KeyValue kv =
        ImmudbProto.KeyValue.newBuilder()
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

    getStub().set(kv);
  }

  public byte[] get(String key) {
    return get(key.getBytes(Charsets.UTF_8));
  }

  public byte[] get(byte[] key) {
    ImmudbProto.Key k = ImmudbProto.Key.newBuilder().setKey(ByteString.copyFrom(key)).build();

    ImmudbProto.Item item = getStub().get(k);
    return item.getValue().toByteArray();
  }

  public byte[] safeGet(String key) throws VerificationException {
    return safeGet(key.getBytes(Charsets.UTF_8));
  }

  public byte[] safeGet(byte[] key) throws VerificationException {
    return safeGet(key, this.root());
  }

  public byte[] safeGet(byte[] key, Root root) throws VerificationException {
    ImmudbProto.Index index = ImmudbProto.Index.newBuilder().setIndex(root.getIndex()).build();

    ImmudbProto.SafeGetOptions sOpts =
        ImmudbProto.SafeGetOptions.newBuilder()
            .setKey(ByteString.copyFrom(key))
            .setRootIndex(index)
            .build();

    ImmudbProto.SafeItem safeItem = getStub().safeGet(sOpts);

    ImmudbProto.Proof proof = safeItem.getProof();

    CryptoUtils.verify(proof, safeItem.getItem(), root);

    rootHolder.SetRoot(new Root(proof.getAt(), proof.getRoot().toByteArray()));

    return safeItem.getItem().getValue().toByteArray();
  }

  public void safeSet(String key, byte[] value) throws VerificationException {
    safeSet(key.getBytes(Charsets.UTF_8), value);
  }

  public void safeSet(byte[] key, byte[] value) throws VerificationException {
    safeSet(key, value, this.root());
  }

  public void safeSet(byte[] key, byte[] value, Root root) throws VerificationException {
    ImmudbProto.KeyValue kv =
        ImmudbProto.KeyValue.newBuilder()
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

    ImmudbProto.SafeSetOptions sOpts =
        ImmudbProto.SafeSetOptions.newBuilder()
            .setKv(kv)
            .setRootIndex(ImmudbProto.Index.newBuilder().setIndex(root.getIndex()).build())
            .build();

    ImmudbProto.Proof proof = getStub().safeSet(sOpts);

    ImmudbProto.Item item =
        ImmudbProto.Item.newBuilder()
            .setIndex(proof.getIndex())
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

    CryptoUtils.verify(proof, item, root);

    rootHolder.SetRoot(new Root(proof.getAt(), proof.getRoot().toByteArray()));
  }
}
