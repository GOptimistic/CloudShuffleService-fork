/*
 * Copyright 2022 Bytedance Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytedance.css.client.stream;

import com.bytedance.css.client.stream.disk.CssRemoteDiskEpochReader;
import com.bytedance.css.common.CssConf;
import com.bytedance.css.common.protocol.CommittedPartitionInfo;
import com.bytedance.css.common.protocol.ShuffleMode;
import com.bytedance.css.common.unsafe.Platform;
import com.bytedance.css.common.util.Utils;
import com.bytedance.css.network.TransportContext;
import com.bytedance.css.network.buffer.FileSegmentManagedBuffer;
import com.bytedance.css.network.buffer.ManagedBuffer;
import com.bytedance.css.network.client.RpcResponseCallback;
import com.bytedance.css.network.client.TransportClient;
import com.bytedance.css.network.client.TransportClientFactory;
import com.bytedance.css.network.protocol.shuffle.BlockTransferMessage;
import com.bytedance.css.network.protocol.shuffle.OpenStream;
import com.bytedance.css.network.protocol.shuffle.StreamHandle;
import com.bytedance.css.network.server.*;
import com.bytedance.css.network.util.TransportConf;
import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CssEpochReaderRetrySuite {

  private HashMap<String, CssFileInfo> map = new HashMap<>();
  private TransportContext transportContext = null;
  private TransportServer transportServer = null;
  CssConf cssConf = new CssConf();

  class MockIterator implements Iterator<ManagedBuffer> {
    private final File file;
    private final long[] offsets;
    private final int numChunks;
    private final TransportConf conf;

    private int shouldFailed;
    private int index;

    MockIterator(CssFileInfo fileInfo, TransportConf conf) throws IOException {
      this.file = fileInfo.file;
      this.conf = conf;
      this.numChunks = fileInfo.numChunks;
      this.offsets = new long[numChunks + 1];
      for (int i = 0; i <= numChunks; i++) {
        offsets[i] = fileInfo.chunkOffsets.get(i);
      }
      if (offsets[numChunks] != fileInfo.fileLength) {
        throw new IOException(
          String.format("The last chunk offset %d should be equals to file length %d!",
            offsets[numChunks], fileInfo.fileLength));
      }
    }

    public void setInitIndex(int index) {
      this.index = index;
    }

    public void setShouldFailed(int shouldFailed) {
      this.shouldFailed = shouldFailed;
    }

    @Override
    public boolean hasNext() {
      return index < numChunks;
    }

    @Override
    public ManagedBuffer next() {
      if (index == 10 && shouldFailed % CssConf.chunkFetchFailedRetryMaxTimes(cssConf) != 2) {
        throw new RuntimeException("Chunk Fetch Failed for Test");
      }
      final long offset = offsets[index];
      final long length = offsets[index + 1] - offset;
      index++;
      return new FileSegmentManagedBuffer(conf, file, offset, length);
    }
  }

  class MockRpcHandler extends RpcHandler {

    private final TransportConf conf;
    private final OneForOneStreamManager streamManager;
    private int shouldFailed = 0;

    MockRpcHandler(TransportConf conf) {
      this.conf = conf;
      streamManager = new OneForOneStreamManager();
    }

    @Override
    public void receive(TransportClient client, ByteBuffer message, RpcResponseCallback callback) {
      BlockTransferMessage msg = BlockTransferMessage.Decoder.fromByteBuffer(message);
      OpenStream openStream = (OpenStream) msg;
      String shuffleKey = openStream.shuffleKey;
      String fileName = openStream.filePath;
      int chunkIndex = openStream.initChunkIndex;
      CssFileInfo fileInfo = map.get(shuffleKey + "-" + fileName);

      if (shouldFailed == 1) {
        callback.onFailure(new Exception("Chunk offsets meta exception for test"));
      } else {
        try {
          MockIterator iterator = new MockIterator(fileInfo, conf);
          iterator.setShouldFailed(shouldFailed);
          iterator.setInitIndex(chunkIndex);
          long streamId = streamManager.registerStream(client.getClientId(), iterator, client.getChannel());
          streamManager.setStreamStateCurIndex(streamId, chunkIndex);

          StreamHandle streamHandle = new StreamHandle(streamId, fileInfo.numChunks);
          callback.onSuccess(streamHandle.toByteBuffer());
        } catch (IOException e) {
          callback.onFailure(new Exception("Chunk offsets meta exception ", e));
        }
      }
      shouldFailed++;
      if (shouldFailed == CssConf.chunkFetchFailedRetryMaxTimes(cssConf)) {
        shouldFailed = 0;
      }
    }

    @Override
    public StreamManager getStreamManager() {
      return streamManager;
    }
  }

  @Before
  public void startServer() throws Exception {
    map.clear();
    TransportConf transportConf = Utils.fromCssConf(cssConf, "RetryFetcherTest", 0);
    MockRpcHandler rpcHandler = new MockRpcHandler(transportConf);
    transportContext = new TransportContext(transportConf, rpcHandler, false);
    transportServer = transportContext.createServer(16789, new ArrayList<>());
  }

  @After
  public void stopServer() throws Exception {
    if (transportServer != null) {
      transportServer.close();
    }
    if (transportContext != null) {
      transportContext.close();
    }
  }

  @Test
  public void testEpochChunkFetcher() throws IOException {
    CssConf cssConf = new CssConf();
    cssConf.set("css.local.chunk.fetch.enabled", "false");
    cssConf.set("css.chunk.fetch.retry.wait.times", "5ms");

    String shuffleKey = "DontTouchEpochFetchClient";
    TransportConf transportConf = Utils.fromCssConf(cssConf, "TEST", 0);
    TransportContext transportContext = new TransportContext(transportConf, new NoOpRpcHandler(), false);
    TransportClientFactory clientFactory = transportContext.createClientFactory(new ArrayList<>());

    for (int i = 0; i < 20; i++) {
      String master = "EpochFetch-SHUFFLE-FILE-" + i + "MASTER";
      String slave = "EpochFetch-SHUFFLE-FILE-" + i + "SLAVE";

      File file = File.createTempFile("EpochFetch", "OUT.M");
      file.deleteOnExit();
      ArrayList<Long> chunkOffsets = new ArrayList<>();
      chunkOffsets.add(0L);
      long totalLength = 0L;
      FileOutputStream outputStream = new FileOutputStream(file);
      List<String> originList = new ArrayList<>();
      for (int j = 0; j < 100; j++) {
        String content = RandomStringUtils.randomAlphanumeric(1024, 2048);
        originList.add(content);
        byte[] bytes = new byte[4 + content.length()];
        Platform.putInt(bytes, Platform.BYTE_ARRAY_OFFSET, content.length());
        System.arraycopy(content.getBytes(StandardCharsets.UTF_8), 0, bytes, 4, content.length());
        outputStream.write(bytes);
        totalLength += bytes.length;
        chunkOffsets.add(totalLength);
      }
      outputStream.close();

      CssFileInfo fileInfo = new CssFileInfo(file, chunkOffsets, file.length());
      String key = i % 2 == 0 ? master : slave;
      map.put(String.format("%s-%s", shuffleKey, key), fileInfo);

      CommittedPartitionInfo[] partitions = new CommittedPartitionInfo[2];
      if (i % 2 == 0) {
        // two piece available
        partitions[0] = new CommittedPartitionInfo(0, 0, Utils.localHostName(), 16789,
          ShuffleMode.DISK, key, file.length());

        partitions[1] = new CommittedPartitionInfo(0, 0, Utils.localHostName(), 16789,
          ShuffleMode.DISK, key, file.length());
      } else {
        // invalid one
        partitions[0] = new CommittedPartitionInfo(0, 0, Utils.localHostName(), 54321,
          ShuffleMode.DISK, "NeverMind", 100000L);

        partitions[1] = new CommittedPartitionInfo(0, 0, Utils.localHostName(), 16789,
          ShuffleMode.DISK, key, file.length());
      }

      CssRemoteDiskEpochReader reader = new CssRemoteDiskEpochReader(cssConf, clientFactory, shuffleKey, partitions);

      byte[] sizeBuf = new byte[4];
      while (reader.hasNext()) {
        ByteBuf buffer = reader.next();
        buffer.readBytes(sizeBuf);
        int tmpLength = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET);
        byte[] tmpBuffer = new byte[tmpLength];
        buffer.readBytes(tmpBuffer);
        String content = new String(tmpBuffer);
        assertTrue(originList.contains(content));
        originList.remove(content);
      }
      assertEquals(originList.size(), 0);
      reader.close();
    }

    clientFactory.close();
    transportContext.close();
  }

  @Test
  public void testRetryChunkFetcher() throws IOException, InterruptedException {
    CssConf cssConf = new CssConf();
    cssConf.set("css.local.chunk.fetch.enabled", "false");
    cssConf.set("css.chunk.fetch.retry.wait.times", "5ms");

    String shuffleKey = "DontTouchRetryChunkFetcher";
    TransportConf transportConf = Utils.fromCssConf(cssConf, "TEST", 0);
    TransportContext transportContext = new TransportContext(transportConf, new NoOpRpcHandler(), false);
    TransportClientFactory clientFactory = transportContext.createClientFactory(new ArrayList<>());

    for (int i = 0; i < 20; i++) {
      String master = "RetryChunkFetcher-SHUFFLE-FILE-" + i + "MASTER";
      String slave = "RetryChunkFetcher-SHUFFLE-FILE-" + i + "SLAVE";

      File file = File.createTempFile("RetryChunkFetcher", "OUT.M");
      file.deleteOnExit();
      ArrayList<Long> chunkOffsets = new ArrayList<>();
      chunkOffsets.add(0L);
      long totalLength = 0L;
      FileOutputStream outputStream = new FileOutputStream(file);
      List<String> originList = new ArrayList<>();
      for (int j = 0; j < 100; j++) {
        String content = RandomStringUtils.randomAlphanumeric(1024, 2048);
        originList.add(content);
        byte[] bytes = new byte[4 + content.length()];
        Platform.putInt(bytes, Platform.BYTE_ARRAY_OFFSET, content.length());
        System.arraycopy(content.getBytes(StandardCharsets.UTF_8), 0, bytes, 4, content.length());
        outputStream.write(bytes);
        totalLength += bytes.length;
        chunkOffsets.add(totalLength);
      }
      outputStream.close();

      CssFileInfo fileInfo = new CssFileInfo(file, chunkOffsets, file.length());
      String key = i % 2 == 0 ? master : slave;
      map.put(String.format("%s-%s", shuffleKey, key), fileInfo);

      // one piece available
      CommittedPartitionInfo committedPartitionInfo = new CommittedPartitionInfo(
        0, 0, Utils.localHostName(), 16789, ShuffleMode.DISK, key, file.length());

      // test retry chunk fetch.
      CssRemoteDiskEpochReader chunkFetcher = new CssRemoteDiskEpochReader(
        cssConf,
        clientFactory,
        shuffleKey,
        new CommittedPartitionInfo[]{committedPartitionInfo}
      );

      byte[] sizeBuf = new byte[4];
      while (chunkFetcher.hasNext()) {
        ByteBuf buffer = chunkFetcher.next();
        buffer.readBytes(sizeBuf);
        int tmpLength = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET);
        byte[] tmpBuffer = new byte[tmpLength];
        buffer.readBytes(tmpBuffer);
        String content = new String(tmpBuffer);
        assertTrue(originList.contains(content));
        originList.remove(content);
      }
      assertEquals(originList.size(), 0);
      chunkFetcher.close();
    }

    clientFactory.close();
    transportContext.close();
  }
}
