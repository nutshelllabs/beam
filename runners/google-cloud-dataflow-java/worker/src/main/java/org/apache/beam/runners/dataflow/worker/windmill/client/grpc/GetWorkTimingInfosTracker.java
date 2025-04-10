/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.worker.windmill.client.grpc;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.beam.runners.dataflow.worker.windmill.Windmill.GetWorkStreamTimingInfo;
import org.apache.beam.runners.dataflow.worker.windmill.Windmill.GetWorkStreamTimingInfo.Event;
import org.apache.beam.runners.dataflow.worker.windmill.Windmill.LatencyAttribution;
import org.apache.beam.runners.dataflow.worker.windmill.Windmill.LatencyAttribution.State;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.joda.time.DateTimeUtils.MillisProvider;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
final class GetWorkTimingInfosTracker {
  private static final Logger LOG = LoggerFactory.getLogger(GetWorkTimingInfosTracker.class);

  private final Map<State, SumAndMaxDurations> aggregatedGetWorkStreamLatencies;
  private final MillisProvider clock;
  private Instant workItemCreationEndTime;
  private Instant workItemLastChunkReceivedByWorkerTime;
  private @Nullable LatencyAttribution workItemCreationLatency;

  GetWorkTimingInfosTracker(MillisProvider clock) {
    this.aggregatedGetWorkStreamLatencies = new EnumMap<>(State.class);
    this.clock = clock;
    this.workItemCreationEndTime = Instant.EPOCH;
    this.workItemLastChunkReceivedByWorkerTime = Instant.EPOCH;
    this.workItemCreationLatency = null;
  }

  void addTimingInfo(Collection<GetWorkStreamTimingInfo> infos) {
    // We want to record duration for each stage and also be reflective on total work item
    // processing time. It can be tricky because timings of different
    // StreamingGetWorkResponseChunks can be interleaved. Current strategy is to record the
    // sum duration in each transmission stage across different chunks, then divide the total
    // duration (start from the chunk creation end in the windmill worker to the end of last chunk
    // reception by the user worker) proportionally according the sum duration values across the
    // many stages, the final latency is also capped by the corresponding stage maximum latency
    // seen across multiple chunks. This should allow us to identify the slow stage meanwhile
    // avoid confusions for comparing the stage duration to the total processing elapsed wall
    // time.
    Map<Event, Instant> getWorkStreamTimings = new HashMap<>();
    for (GetWorkStreamTimingInfo info : infos) {
      getWorkStreamTimings.putIfAbsent(
          info.getEvent(), Instant.ofEpochMilli(info.getTimestampUsec() / 1000));
    }

    // Record the difference between starting to get work and the first chunk being sent as the
    // work creation time.
    @Nullable
    Instant workItemCreationStart = getWorkStreamTimings.get(Event.GET_WORK_CREATION_START);
    @Nullable Instant workItemCreationEnd = getWorkStreamTimings.get(Event.GET_WORK_CREATION_END);
    if (workItemCreationStart != null
        && workItemCreationEnd != null
        && workItemCreationLatency == null) {
      workItemCreationLatency =
          LatencyAttribution.newBuilder()
              .setState(State.GET_WORK_IN_WINDMILL_WORKER)
              .setTotalDurationMillis(
                  new Duration(workItemCreationStart, workItemCreationEnd).getMillis())
              .build();
    }
    // Record the work item creation end time as the start of transmission stages.
    if (workItemCreationEnd != null && workItemCreationEnd.isAfter(workItemCreationEndTime)) {
      workItemCreationEndTime = workItemCreationEnd;
    }

    // Record the latency of each chunk between send on worker and arrival on dispatcher.
    Instant receivedByDispatcherTiming =
        getWorkStreamTimings.get(Event.GET_WORK_RECEIVED_BY_DISPATCHER);
    if (workItemCreationEnd != null && receivedByDispatcherTiming != null) {
      trackTimeInState(
          State.GET_WORK_IN_TRANSIT_TO_DISPATCHER,
          new Duration(workItemCreationEnd, receivedByDispatcherTiming));
    }

    // Record the latency of each chunk between send on dispatcher or windmill worker and arrival on
    // the user worker.
    @Nullable
    Instant forwardedByDispatcherTiming =
        getWorkStreamTimings.get(Event.GET_WORK_FORWARDED_BY_DISPATCHER);
    Instant now = Instant.ofEpochMilli(clock.getMillis());
    if (forwardedByDispatcherTiming != null && now.isAfter(forwardedByDispatcherTiming)) {
      trackTimeInState(
          State.GET_WORK_IN_TRANSIT_TO_USER_WORKER, new Duration(forwardedByDispatcherTiming, now));
    } else if (workItemCreationEnd != null && now.isAfter(workItemCreationEnd)) {
      trackTimeInState(
          State.GET_WORK_IN_TRANSIT_TO_USER_WORKER, new Duration(workItemCreationEnd, now));
    }

    workItemLastChunkReceivedByWorkerTime = now;
  }

  private void trackTimeInState(LatencyAttribution.State state, Duration newDuration) {
    aggregatedGetWorkStreamLatencies.compute(
        state,
        (stateKey, duration) -> {
          if (duration == null) {
            return new SumAndMaxDurations(newDuration, newDuration);
          }
          duration.max = newDuration.isLongerThan(duration.max) ? newDuration : duration.max;
          duration.sum = duration.sum.plus(newDuration);
          return duration;
        });
  }

  ImmutableList<LatencyAttribution> getLatencyAttributions() {
    if (workItemCreationLatency == null && aggregatedGetWorkStreamLatencies.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<LatencyAttribution> latencyAttributions =
        ImmutableList.builderWithExpectedSize(aggregatedGetWorkStreamLatencies.size() + 1);
    if (workItemCreationLatency != null) {
      latencyAttributions.add(workItemCreationLatency);
    }
    long totalSumDurationTimeMills = 0;
    for (SumAndMaxDurations duration : aggregatedGetWorkStreamLatencies.values()) {
      totalSumDurationTimeMills += duration.sum.getMillis();
    }
    final long finalTotalSumDurationTimeMills = totalSumDurationTimeMills;
    long totalTransmissionDurationElapsedTime;
    if (workItemCreationEndTime.isAfter(workItemLastChunkReceivedByWorkerTime)) {
      LOG.debug(
          "Work item creation time {} is after the work received time {}, "
              + "one or more GetWorkStream timing infos are missing. Using raw times without scaling.",
          workItemCreationEndTime,
          workItemLastChunkReceivedByWorkerTime);
      totalTransmissionDurationElapsedTime = finalTotalSumDurationTimeMills;
    } else {
      totalTransmissionDurationElapsedTime =
          new Duration(workItemCreationEndTime, workItemLastChunkReceivedByWorkerTime).getMillis();
    }
    aggregatedGetWorkStreamLatencies.forEach(
        (state, duration) -> {
          long scaledDuration =
              (long)
                  (((double) duration.sum.getMillis() / finalTotalSumDurationTimeMills)
                      * totalTransmissionDurationElapsedTime);
          // Cap final duration by the max state duration across different chunks. This ensures
          // the sum of final durations does not exceed the total elapsed time and the duration
          // for each stage does not exceed the stage maximum.
          long durationMills = Math.min(duration.max.getMillis(), scaledDuration);
          latencyAttributions.add(
              LatencyAttribution.newBuilder()
                  .setState(state)
                  .setTotalDurationMillis(durationMills)
                  .build());
        });
    return latencyAttributions.build();
  }

  void reset() {
    this.aggregatedGetWorkStreamLatencies.clear();
    this.workItemCreationEndTime = Instant.EPOCH;
    this.workItemLastChunkReceivedByWorkerTime = Instant.EPOCH;
    this.workItemCreationLatency = null;
  }

  private static class SumAndMaxDurations {
    private Duration sum;
    private Duration max;

    private SumAndMaxDurations(Duration sum, Duration max) {
      this.sum = sum;
      this.max = max;
    }
  }
}
