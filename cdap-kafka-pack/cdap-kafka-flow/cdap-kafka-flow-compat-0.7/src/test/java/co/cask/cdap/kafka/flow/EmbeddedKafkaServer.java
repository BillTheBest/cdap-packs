/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.kafka.flow;

import com.google.common.util.concurrent.AbstractIdleService;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServerStartable;

import java.util.Properties;

/**
 * A {@link com.google.common.util.concurrent.Service} implementation for running an instance of Kafka server in
 * the same process.
 */
public final class EmbeddedKafkaServer extends AbstractIdleService {

  private final KafkaServerStartable server;

  public EmbeddedKafkaServer(Properties properties) {
    server = new KafkaServerStartable(new KafkaConfig(properties));
  }

  @Override
  protected void startUp() throws Exception {
    server.startup();
  }

  @Override
  protected void shutDown() throws Exception {
    server.shutdown();
    server.awaitShutdown();
  }
}
