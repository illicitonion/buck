/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.network.BatchingLogger;
import com.facebook.buck.util.network.HiveRowFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;

/**
 * Listens to HttpArtifactCacheEvents and logs stats data in Hive row format.
 */
public class HttpArtifactCacheListener implements BuckEventListener {

  private final ObjectMapper jsonConverter;
  private final BatchingLogger logger;
  private final ImmutableMap<String, String> environmentInfo;

  public HttpArtifactCacheListener(
      BatchingLogger logger,
      ObjectMapper jsonConverter) {
    this(logger, jsonConverter, ImmutableMap.of());
  }

  public HttpArtifactCacheListener(
      BatchingLogger logger,
      ObjectMapper jsonConverter,
      ImmutableMap<String, String> environmentInfo) {
    this.jsonConverter = jsonConverter;
    this.logger = logger;
    this.environmentInfo = environmentInfo;
  }

  @Override
  public void outputTrace(final BuildId buildId) {
    logger.close();
  }

  @Subscribe
  public void onHttpArtifactCacheEvent(HttpArtifactCacheEvent.Finished event) {
    final String buildIdString = event.getBuildId().toString();
    ObjectNode jsonNode = jsonConverter.valueToTree(event);
    if (!environmentInfo.isEmpty()) {
      jsonNode.set("environment",  jsonConverter.valueToTree(environmentInfo));
    }

    String hiveRow = HiveRowFormatter.newFormatter()
        .appendString(jsonNode.toString())
        .appendString(buildIdString)
        .build();
    logger.log(hiveRow);
  }
}
