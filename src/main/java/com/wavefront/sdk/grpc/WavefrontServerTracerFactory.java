package com.wavefront.sdk.grpc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.WavefrontGrpcReporter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtractAdapter;
import io.opentracing.tag.Tags;

import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;
import static com.wavefront.sdk.common.Constants.WAVEFRONT_PROVIDED_SOURCE;
import static com.wavefront.sdk.grpc.Constants.GRPC_METHOD_TYPE_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVICE_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_STATUS_KEY;

/**
 * A gRPC server tracer factory that listens to stream events on server to generate stats and
 * sends them to Wavefront. Create only one instance of {@link WavefrontServerTracerFactory) per
 * one service and use it to trace all server.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class WavefrontServerTracerFactory extends ServerStreamTracer.Factory {
  private static final String REQUEST_PREFIX = "server.request.";
  private static final String RESPONSE_PREFIX = "server.response.";
  private final Map<MetricName, AtomicInteger> gauges = new ConcurrentHashMap<>();
  private final WavefrontGrpcReporter wfGrpcReporter;
  @Nullable
  private final Tracer tracer;
  private final ApplicationTags applicationTags;
  private final boolean recordStreamingStats;

  public static class Builder {
    private WavefrontGrpcReporter wfGrpcReporter;
    @Nullable
    private Tracer tracer;
    private ApplicationTags applicationTags;
    boolean recordStreamingStats = false;

    public Builder(WavefrontGrpcReporter wfGrpcReporter, ApplicationTags applicationTags) {
      this.wfGrpcReporter = Preconditions.checkNotNull(wfGrpcReporter, "invalid reporter");
      this.applicationTags = Preconditions.checkNotNull(applicationTags, "invalid app tags");
    }

    public Builder recordStreamingStats() {
      this.recordStreamingStats = true;
      return this;
    }

    public Builder tracer(Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    public WavefrontServerTracerFactory build() {
      return new WavefrontServerTracerFactory(wfGrpcReporter, tracer, applicationTags,
          recordStreamingStats);
    }
  }

  private WavefrontServerTracerFactory(WavefrontGrpcReporter wfGrpcReporter, Tracer tracer,
                                      ApplicationTags applicationTags,
                                      boolean recordStreamingStats) {
    this.wfGrpcReporter = wfGrpcReporter;
    this.tracer = tracer;
    this.applicationTags = applicationTags;
    this.recordStreamingStats = recordStreamingStats;
    wfGrpcReporter.registerServerHeartBeat();
  }

  @Override
  public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
    String methodName = Utils.getFriendlyMethodName(fullMethodName);
    return new ServerTracer(Utils.getServiceName(fullMethodName), methodName,
        createServerSpan(headers, methodName));
  }

  private Span createServerSpan(Metadata headers, String methodName) {
    if (tracer == null) {
      return null;
    }
    Map<String, String> headerMap = new HashMap<String, String>();
    for (String key : headers.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        headerMap.put(key, value);
      }
    }
    Span span;
    try {
      SpanContext parentSpanCtx = tracer.extract(Format.Builtin.HTTP_HEADERS,
          new TextMapExtractAdapter(headerMap));
      if (parentSpanCtx == null) {
        span = tracer.buildSpan(methodName).start();
      } else {
        span = tracer.buildSpan(methodName).asChildOf(parentSpanCtx).start();
      }
    } catch (IllegalArgumentException iae) {
      span = tracer.buildSpan(methodName)
          .withTag("Error", "Extract failed and an IllegalArgumentException was thrown")
          .start();
    }
    return span;
  }

  private class ServerTracer extends ServerStreamTracer {
    private final String grpcService;
    private final String methodName;
    @Nullable
    private final Span span;
    private AtomicBoolean streamingMethod = new AtomicBoolean(false);
    private final AtomicBoolean streamClosed = new AtomicBoolean(false);
    @Nullable
    private final AtomicLong requestMessageCount;
    @Nullable
    private final AtomicLong responseMessageCount;
    private final AtomicLong requestBytes = new AtomicLong(0);
    private final AtomicLong responseBytes = new AtomicLong(0);
    private final long startTime;
    private final Map<String, String> allTags;
    private final Map<String, String> overallAggregatedPerSourceTags;

    ServerTracer(String grpcService, String methodName, Span span) {
      // TODO: consider using a stopwatch or nano time.
      this.startTime = System.currentTimeMillis();
      this.grpcService = grpcService;
      this.methodName = methodName;
      this.span = span;
      this.requestMessageCount = new AtomicLong(0);
      this.responseMessageCount = new AtomicLong(0);
      this.allTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
      }};
      this.overallAggregatedPerSourceTags = ImmutableMap.<String, String>builder().
          put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
              applicationTags.getCluster()).
          put(SERVICE_TAG_KEY, applicationTags.getService()).
          put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
              applicationTags.getShard()).
          build();
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          incrementAndGet();
      getGaugeValue(new MetricName("server.total_requests.inflight",
          overallAggregatedPerSourceTags)).incrementAndGet();
    }

    @Override
    public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
      streamingMethod.set(Utils.isStreamingMethod(callInfo.getMethodDescriptor().getType()));
      if (span != null) {
        span.setTag(GRPC_METHOD_TYPE_KEY, callInfo.getMethodDescriptor().getType().toString());
      }
    }

    @Override
    public void outboundWireSize(long bytes) {
      responseBytes.addAndGet(bytes);
    }

    @Override
    public void inboundWireSize(long bytes) {
      requestBytes.addAndGet(bytes);
    }

    @Override
    public void inboundMessageRead(int seqNo, long optionalWireSize,
                                   long optionalUncompressedSize) {
      if (shouldRecordStreamingStats()) {
        requestMessageCount.incrementAndGet();
        wfGrpcReporter.incrementCounter(
            new MetricName(REQUEST_PREFIX + methodName + ".streaming.messages.count", allTags));
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(REQUEST_PREFIX + methodName + ".streaming.message_bytes", allTags),
              optionalWireSize);
        }
      }
    }

    @Override
    public void outboundMessageSent(int seqNo, long optionalWireSize,
                                    long optionalUncompressedSize) {
      if (shouldRecordStreamingStats()) {
        responseMessageCount.incrementAndGet();
        wfGrpcReporter.incrementCounter(
            new MetricName(RESPONSE_PREFIX + methodName + ".streaming.messages.count", allTags));
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(RESPONSE_PREFIX + methodName + ".streaming.message_bytes", allTags),
              optionalWireSize);
        }
      }
    }

    @Override
    public void streamClosed(Status status) {
      if (streamClosed.getAndSet(true)) {
        return;
      }
      long rpcLatency = System.currentTimeMillis() - startTime;
      finishServerSpan(status);
      String methodWithStatus = methodName + "." + status.getCode().toString();
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          decrementAndGet();
      getGaugeValue(new MetricName("server.total_requests.inflight",
          overallAggregatedPerSourceTags)).decrementAndGet();
      // rpc latency
      wfGrpcReporter.updateHistogram(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".latency", allTags), rpcLatency);
      // request, response size
      wfGrpcReporter.updateHistogram(
          new MetricName(REQUEST_PREFIX + methodName + ".bytes", allTags), requestBytes.get());
      wfGrpcReporter.updateHistogram(
          new MetricName(RESPONSE_PREFIX + methodName + ".bytes", allTags), responseBytes.get());
      // streaming stats
      if (shouldRecordStreamingStats()) {
        wfGrpcReporter.updateHistogram(
            new MetricName(REQUEST_PREFIX + methodName + ".streaming.messages_per_rpc", allTags),
            requestMessageCount.get());
        wfGrpcReporter.updateHistogram(
            new MetricName(RESPONSE_PREFIX + methodName + ".streaming.messages_per_rpc", allTags),
            responseMessageCount.get());
        wfGrpcReporter.incrementCounter(
            new MetricName(REQUEST_PREFIX + methodName + ".streaming.messages", allTags),
            requestMessageCount.get());
        wfGrpcReporter.incrementCounter(
            new MetricName(RESPONSE_PREFIX + methodName + ".streaming.messages", allTags),
            responseMessageCount.get());
      }
      Map<String, String> aggregatedPerShardTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggregatedPerServiceTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggregatedPerClutserTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggergatedPerApplicationTags = new HashMap<String, String>() {{
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      // overall RPC related metrics
      Map<String, String> overallAggregatedPerShardTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerServiceTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerClusterTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerApplicationTags = new HashMap<String, String>() {{
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      // granular RPC stats
      wfGrpcReporter.incrementCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".cumulative", allTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_shard",
              aggregatedPerShardTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_service",
              aggregatedPerServiceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_cluster",
              aggregatedPerClutserTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_application",
              aggergatedPerApplicationTags));
      // overall RPC stats
      wfGrpcReporter.incrementCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_source",
              overallAggregatedPerSourceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_shard",
              overallAggregatedPerShardTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_service",
              overallAggregatedPerServiceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_cluster",
              overallAggregatedPerClusterTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_application",
              overallAggregatedPerApplicationTags));
      // overall error stats
      if (status.getCode() != Status.Code.OK) {
        wfGrpcReporter.incrementCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_source",
                overallAggregatedPerSourceTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_shard",
                overallAggregatedPerShardTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_service",
                overallAggregatedPerServiceTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_cluster",
                overallAggregatedPerClusterTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_application",
                overallAggregatedPerApplicationTags));
      }
    }

    private void finishServerSpan(Status status) {
      if (span != null) {
        span.setTag(GRPC_STATUS_KEY, status.getCode().toString());
        if (status.getCode() != Status.Code.OK) {
          Tags.ERROR.set(span, true);
        }
        span.finish();
      }
    }

    private boolean shouldRecordStreamingStats() {
      return recordStreamingStats && streamingMethod.get();
    }
  }

  private AtomicInteger getGaugeValue(MetricName metricName) {
    return gauges.computeIfAbsent(metricName, key -> {
      final AtomicInteger toReturn = new AtomicInteger();
      wfGrpcReporter.registerGauge(key, () -> (double) toReturn.get());
      return toReturn;
    });
  }
}
