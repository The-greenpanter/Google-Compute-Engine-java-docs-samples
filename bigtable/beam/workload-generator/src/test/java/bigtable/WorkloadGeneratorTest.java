/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bigtable;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertNotNull;

import bigtable.WorkloadGenerator.BigtableWorkloadOptions;
import bigtable.WorkloadGenerator.ReadFromTableFn;
import com.google.api.services.dataflow.model.Job;
import com.google.bigtable.repackaged.com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.bigtable.repackaged.com.google.cloud.monitoring.v3.MetricServiceClient.ListTimeSeriesPagedResponse;
import com.google.bigtable.repackaged.com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.bigtable.repackaged.com.google.monitoring.v3.Point;
import com.google.bigtable.repackaged.com.google.monitoring.v3.ProjectName;
import com.google.bigtable.repackaged.com.google.monitoring.v3.TimeInterval;
import com.google.bigtable.repackaged.com.google.monitoring.v3.TimeSeries;
import com.google.bigtable.repackaged.com.google.protobuf.util.Timestamps;
import com.google.cloud.bigtable.beam.CloudBigtableTableConfiguration;
import com.google.cloud.bigtable.hbase.BigtableConfiguration;
import com.google.dataflow.v1beta3.FlexTemplatesServiceClient;
import com.google.dataflow.v1beta3.LaunchFlexTemplateParameter;
import com.google.dataflow.v1beta3.LaunchFlexTemplateRequest;
import com.google.dataflow.v1beta3.LaunchFlexTemplateResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.UUID;
import org.apache.beam.runners.dataflow.DataflowClient;
import org.apache.beam.runners.dataflow.DataflowPipelineJob;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class WorkloadGeneratorTest {

  private static final String TABLE_ID =
      "mobile-time-series-" + UUID.randomUUID().toString().substring(0, 20);
  private static final String COLUMN_FAMILY_NAME = "stats_summary";

  private static String projectId;
  private static String instanceId;
  private static final String REGION_ID = "us-central1";

  private ByteArrayOutputStream bout;

  private static String requireEnv(String varName) {
    String value = System.getenv(varName);
    assertNotNull(
        String.format("Environment variable '%s' is required to perform these tests.", varName),
        value);
    return value;
  }

  @BeforeClass
  public static void beforeClass() {
    projectId = requireEnv("GOOGLE_CLOUD_PROJECT");
    instanceId = requireEnv("BIGTABLE_TESTING_INSTANCE");
    try (Connection connection = BigtableConfiguration.connect(projectId, instanceId)) {
      Admin admin = connection.getAdmin();
      HTableDescriptor descriptor = new HTableDescriptor(TableName.valueOf(TABLE_ID));
      descriptor.addFamily(new HColumnDescriptor(COLUMN_FAMILY_NAME));
      admin.createTable(descriptor);
    } catch (Exception e) {
      System.out.println("Error during beforeClass: \n" + e.toString());
    }
  }

  @Before
  public void setupStream() {
    bout = new ByteArrayOutputStream();
    System.setOut(new PrintStream(bout));
  }

  @AfterClass
  public static void afterClass() {
    try (Connection connection = BigtableConfiguration.connect(projectId, instanceId)) {
      Admin admin = connection.getAdmin();
      Table table = connection.getTable(TableName.valueOf(Bytes.toBytes(TABLE_ID)));
      admin.disableTable(table.getName());
      admin.deleteTable(table.getName());
    } catch (Exception e) {
      System.out.println("Error during afterClass: \n" + e.toString());
    }
  }

  @Test
  public void testGenerateWorkload() {
    BigtableWorkloadOptions options = PipelineOptionsFactory.create()
        .as(BigtableWorkloadOptions.class);
    options.setBigtableInstanceId(instanceId);
    options.setBigtableTableId(TABLE_ID);
    options.setRegion(REGION_ID);

    Pipeline p = Pipeline.create(options);

    CloudBigtableTableConfiguration bigtableTableConfig =
        new CloudBigtableTableConfiguration.Builder()
            .withProjectId(options.getProject())
            .withInstanceId(options.getBigtableInstanceId())
            .withTableId(options.getBigtableTableId())
            .build();

    // Initiates a new pipeline every second
    p.apply(Create.of(1L))
        .apply(ParDo.of(new ReadFromTableFn(bigtableTableConfig)));
    p.run().waitUntilFinish();

    String output = bout.toString();
    assertThat(output).contains("Connected to table");
  }

  // todo: Fix test flakiness
  @Ignore
  @Test
  public void testPipeline() throws IOException, InterruptedException {
    String workloadJobName = "bigtable-workload-generator-test-" + UUID.randomUUID();
    final int WORKLOAD_DURATION = 5;
    final int WAIT_DURATION = (WORKLOAD_DURATION) * 60 * 1000;
    final int METRIC_DELAY = 4 * 60 * 1000;
    int rate = 1000;

    BigtableWorkloadOptions options = PipelineOptionsFactory.create()
        .as(BigtableWorkloadOptions.class);
    options.setBigtableInstanceId(instanceId);
    options.setBigtableTableId(TABLE_ID);
    options.setWorkloadRate(rate);
    options.setRegion(REGION_ID);
    options.setWorkloadDurationMinutes(WORKLOAD_DURATION);
    options.setRunner(DataflowRunner.class);
    options.setJobName(workloadJobName);

    final PipelineResult pipelineResult = WorkloadGenerator.generateWorkload(options);

    // Check if job is finished running
    String jobId = ((DataflowPipelineJob) pipelineResult).getJobId();
    DataflowClient dataflowClient = DataflowClient.create(options);
    Job job = dataflowClient.getJob(jobId);

    // Wait until job actually starts because it can be queued if too many jobs are running.
    final int QUEUE_WAIT_MINS = 5;
    final int QUEUE_WAIT_INTERVAL = 10;
    for (int i = 0; i < QUEUE_WAIT_MINS * 60 / QUEUE_WAIT_INTERVAL; i++) {
      job = dataflowClient.getJob(jobId);
      if (job.getCurrentState().equals("JOB_STATE_RUNNING")) {
        break;
      }
      Thread.sleep(QUEUE_WAIT_INTERVAL * 1000);
    }

    assertWithMessage("Job took too long queueing up for test").that(job.getCurrentState())
        .isEqualTo("JOB_STATE_RUNNING");

    // Wait X minutes and then get metrics for the X minute period.
    long startMillis = System.currentTimeMillis();
    Thread.sleep(WAIT_DURATION + METRIC_DELAY);

    TimeInterval interval =
        TimeInterval.newBuilder()
            .setStartTime(Timestamps.fromMillis(startMillis))
            .setEndTime(Timestamps.fromMillis(System.currentTimeMillis() - METRIC_DELAY))
            .build();

    MetricServiceClient metricServiceClient = MetricServiceClient.create();
    ProjectName name = ProjectName.of(projectId);

    ListTimeSeriesRequest request =
        ListTimeSeriesRequest.newBuilder()
            .setName(name.toString())
            .setFilter("metric.type=\"bigtable.googleapis.com/server/request_count\" "
                + "metric.label.method=\"Bigtable.ReadRows\"")
            .setInterval(interval)
            .build();
    ListTimeSeriesPagedResponse response = metricServiceClient.listTimeSeries(request);

    TimeSeries readRowRequestCount = response.iterateAll().iterator().next();

    boolean passedRate = false;
    for (int i = 0; i < readRowRequestCount.getPointsList().size(); i++) {
      Point p = readRowRequestCount.getPoints(i);
      long count = p.getValue().getInt64Value();
      long duration =
          p.getInterval().getEndTime().getSeconds() - p.getInterval().getStartTime().getSeconds();

      // Ensure request is at above 90% of desired rate
      if (count > (.9 * rate * duration)) {
        passedRate = true;
        break;
      }
    }
    // Ensure at least one interval got above the rate.
    assertThat(passedRate).isTrue();

    // Ensure the job is stopped after duration. Needs a bit of a wait to guarantee cancellation
    // state is entered.
    Thread.sleep(2 * 60 * 1000);
    assertThat(job.getCurrentState()).matches("JOB_STATE_CANCELLED");
  }

  @Test
  @Ignore("TODO: remove after fixing https://github.com/GoogleCloudPlatform/java-docs-samples/issues/9243")
  public void testDeployedPipeline() throws IOException, InterruptedException {
    FlexTemplatesServiceClient flexTemplatesServiceClient =
        FlexTemplatesServiceClient.create();
    LaunchFlexTemplateRequest request =
        LaunchFlexTemplateRequest.newBuilder()
            .setProjectId(projectId)
            .setLaunchParameter(
                LaunchFlexTemplateParameter.newBuilder()
                    .setContainerSpecGcsPath(
                        "gs://cloud-bigtable-dataflow-templates/generate-workload.json")
                    .setJobName("generate-workload" + UUID.randomUUID().toString().substring(0, 20))
                    .putParameters("bigtableInstanceId", instanceId)
                    .putParameters("bigtableTableId", TABLE_ID)
                    .build())
            .build();

    LaunchFlexTemplateResponse response = flexTemplatesServiceClient.launchFlexTemplate(request);

    String jobId = response.getJob().getId();
    BigtableWorkloadOptions options = PipelineOptionsFactory.create()
        .as(BigtableWorkloadOptions.class);
    DataflowClient dataflowClient = DataflowClient.create(options);

    Thread.sleep(3 * 60 * 1000);
    Job job = dataflowClient.getJob(jobId);
    assertThat(job.getCurrentState()).matches("JOB_STATE_RUNNING");

    // Cancel job manually because test job never ends.
    job.setRequestedState("JOB_STATE_CANCELLED");
    dataflowClient.updateJob(jobId, job);
  }
}
