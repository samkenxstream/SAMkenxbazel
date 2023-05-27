// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.metrics;

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Helps to collect infomation about all process using ps command. Works for Linux and MacOS systems
 */
public class PsInfoCollector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  // Updates snapshots no more than once per interval. Running ps is somewhat slow and should not be
  // done too often.
  private static final Duration MIN_COLLECTION_INTERVAL = Duration.ofMillis(500);
  private static final Clock clock = BlazeClock.instance();
  private static final PsInfoCollector instance = new PsInfoCollector();

  public static PsInfoCollector instance() {
    return instance;
  }

  private PsSnapshot currentPsSnapshot;

  // prevent construction
  private PsInfoCollector() {}

  /**
   * If ps snapshot was outdated will update it, and then returns resource consumption snapshot of
   * processes subtrees based on collected ps snapshot.
   */
  public synchronized ResourceSnapshot collectResourceUsage(ImmutableSet<Long> processIds) {
    Instant now = clock.now();
    if (currentPsSnapshot == null
        || Duration.between(currentPsSnapshot.getCollectionTime(), now)
                .compareTo(MIN_COLLECTION_INTERVAL)
            > 0) {

      updatePsSnapshot();
    }

    ImmutableMap.Builder<Long, Integer> pidToMemoryInKb = ImmutableMap.builder();
    for (Long pid : processIds) {
      PsInfo psInfo = currentPsSnapshot.getPidToPsInfo().get(pid);
      if (psInfo == null) {
        continue;
      }
      pidToMemoryInKb.put(pid, collectMemoryUsageOfDescendants(psInfo, currentPsSnapshot));
    }

    return ResourceSnapshot.create(
        pidToMemoryInKb.buildOrThrow(), currentPsSnapshot.getCollectionTime());
  }

  /** Updates current snapshot of all processes state, using ps command. */
  private void updatePsSnapshot() {
    // TODO(b/279003887): add exception if we couldn't collect the metrics.
    ImmutableMap<Long, PsInfo> pidToPsInfo = collectDataFromPs();

    ImmutableSetMultimap<Long, PsInfo> pidToChildrenPsInfo =
        pidToPsInfo.values().stream()
            .collect(toImmutableSetMultimap(PsInfo::getParentPid, Function.identity()));

    currentPsSnapshot = PsSnapshot.create(pidToPsInfo, pidToChildrenPsInfo, clock.now());
  }

  /** Collects memory usage for every process. */
  @VisibleForTesting
  ImmutableMap<Long, PsInfo> collectDataFromPs() {
    try {
      Process psProcess = buildPsProcess();
      return collectDataFromPsProcess(psProcess);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error while executing command ps");
      return ImmutableMap.of();
    }
  }

  static ImmutableMap<Long, PsInfo> collectDataFromPsProcess(Process psProcess) {
    BufferedReader psOutput =
        new BufferedReader(new InputStreamReader(psProcess.getInputStream(), UTF_8));

    ImmutableMap.Builder<Long, PsInfo> psInfos = ImmutableMap.builder();

    try {
      // The output of the above ps command looks similar to this:
      // PID     PPID   RSS
      // 211706  1      222972
      // 2612333 211706 6180
      // We skip over the first line (the header) and then parse the PID and the resident memory
      // size in kilobytes.
      String output = null;
      boolean isFirst = true;
      while ((output = psOutput.readLine()) != null) {
        if (isFirst) {
          isFirst = false;
          continue;
        }
        List<String> line = Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(output);
        if (line.size() != 3) {
          logger.atWarning().log("Unexpected length of split line %s %d", output, line.size());
          continue;
        }

        long pid = Long.parseLong(line.get(0));
        long parentPid = Long.parseLong(line.get(1));
        int memoryInKb = Integer.parseInt(line.get(2));

        psInfos.put(pid, PsInfo.create(pid, parentPid, memoryInKb));
      }
    } catch (IllegalArgumentException | IOException e) {
      logger.atWarning().withCause(e).log("Error while parsing psOutput: %s", psOutput);
    }

    return psInfos.buildOrThrow();
  }

  private static Process buildPsProcess() throws IOException {
    return new ProcessBuilder("ps", "-e", "-o", "pid,ppid,rss").start();
  }

  /** Recursively collects total memory usage of all descendants of the process. */
  private static int collectMemoryUsageOfDescendants(PsInfo psInfo, PsSnapshot psSnapshot) {
    int currentMemoryInKb = psInfo.getMemoryInKb();
    for (PsInfoCollector.PsInfo childrenPsInfo :
        psSnapshot.getPidToChildrenPsInfo().get(psInfo.getPid())) {
      currentMemoryInKb += collectMemoryUsageOfDescendants(childrenPsInfo, psSnapshot);
    }

    return currentMemoryInKb;
  }

  /** Parsed information about process collected after ps command call. */
  @AutoValue
  abstract static class PsInfo {
    public abstract long getPid();

    public abstract long getParentPid();

    public abstract int getMemoryInKb();

    public static PsInfo create(long pid, long parentPid, int memoryinKb) {
      return new AutoValue_PsInfoCollector_PsInfo(pid, parentPid, memoryinKb);
    }
  }

  /** Contains structurized information from ps command. */
  @AutoValue
  abstract static class PsSnapshot {
    abstract ImmutableMap<Long, PsInfo> getPidToPsInfo();

    abstract ImmutableSetMultimap<Long, PsInfo> getPidToChildrenPsInfo();

    abstract Instant getCollectionTime();

    static PsSnapshot create(
        ImmutableMap<Long, PsInfo> pidToPsInfo,
        ImmutableSetMultimap<Long, PsInfo> pidToChildrenPsInfo,
        Instant collectionTime) {
      return new AutoValue_PsInfoCollector_PsSnapshot(
          pidToPsInfo, pidToChildrenPsInfo, collectionTime);
    }
  }

  /** Contains resource consumption info per process tree. */
  @AutoValue
  public abstract static class ResourceSnapshot {
    /** Overall memory consumption by all descendant processes including initial process. */
    public abstract ImmutableMap<Long, Integer> getPidToMemoryInKb();

    /** Time when this snapshot was collected. */
    public abstract Instant getCollectionTime();

    public static ResourceSnapshot create(
        ImmutableMap<Long, Integer> pidToMemoryInKb, Instant collectionTime) {
      return new AutoValue_PsInfoCollector_ResourceSnapshot(pidToMemoryInKb, collectionTime);
    }
  }
}
