/*
 * Copyright 2014 Cask, Inc.
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

package co.cask.cdap.packs.etl.realtime.source;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.FlowManager;
import co.cask.cdap.test.RuntimeMetrics;
import co.cask.cdap.test.RuntimeStats;
import co.cask.cdap.test.StreamWriter;
import co.cask.cdap.test.TestBase;
import co.cask.cdap.packs.etl.etl.Constants;
import co.cask.cdap.packs.etl.dictionary.DictionaryDataSet;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 *
 */
public class MetadataSourceTest extends TestBase {

  @Test
  public void test() throws Exception {
    ApplicationManager appMngr = deployApplication(ETLWithMetadataSource.class);

    StreamWriter sw = appMngr.getStreamWriter("filesStream");
    sw.send(ImmutableMap.of("filename", "file1", "size", "1024"), "ignored_body");

    FlowManager flow = appMngr.startFlow("ETLFlow");
    RuntimeMetrics terminalMetrics =
      RuntimeStats.getFlowletMetrics(ETLWithMetadataSource.class.getSimpleName(), "ETLFlow", "ETLFlowlet");
    terminalMetrics.waitForinput(1, 5, TimeUnit.SECONDS);
    TimeUnit.SECONDS.sleep(1);

    // verify
    DataSetManager<DictionaryDataSet> dict = appMngr.getDataSet(Constants.DICTIONARY_DATASET);
    Assert.assertEquals("1024", Bytes.toString(dict.get().get("myDict", "file1".getBytes(Charsets.UTF_8), "size")));

    flow.stop();
  }
}
