// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.profiler;


import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.profiler.Profiler.TaskData;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;

/** Writes the profile in Json Trace file format. */
class JsonTraceFileWriter implements Runnable {
  protected final BlockingQueue<TraceData> queue;
  protected final Thread thread;
  protected IOException savedException;

  private final OutputStream outStream;
  private final long profileStartTimeNanos;
  private final ThreadLocal<Boolean> metadataPosted = ThreadLocal.withInitial(() -> Boolean.FALSE);
  private final boolean slimProfile;
  private final UUID buildID;
  private final String outputBase;

  private static final long SLIM_PROFILE_EVENT_THRESHOLD = 10_000;
  private static final long SLIM_PROFILE_MAXIMAL_PAUSE_NS = Duration.ofMillis(100).toNanos();
  private static final long SLIM_PROFILE_MAXIMAL_DURATION_NS = Duration.ofMillis(250).toNanos();

  private static final TaskData POISON_PILL =
      new TaskData(/* startTimeNanos= */ 0, /* eventType= */ null, "poison pill");

  JsonTraceFileWriter(
      OutputStream outStream,
      long profileStartTimeNanos,
      boolean slimProfile,
      String outputBase,
      UUID buildID) {
    this.queue = new LinkedBlockingQueue<>();
    this.thread = new Thread(this, "profile-writer-thread");
    this.outStream = outStream;
    this.profileStartTimeNanos = profileStartTimeNanos;
    this.slimProfile = slimProfile;
    this.buildID = buildID;
    this.outputBase = outputBase;
  }

  public void shutdown() throws IOException {
    // Add poison pill to queue and then wait for writer thread to shut down.
    queue.add(POISON_PILL);
    try {
      thread.join();
    } catch (InterruptedException e) {
      thread.interrupt();
      Thread.currentThread().interrupt();
    }
    if (savedException != null) {
      throw savedException;
    }
  }

  public void start() {
    thread.start();
  }

  public void enqueue(TraceData data) {
    if (!metadataPosted.get()) {
      metadataPosted.set(Boolean.TRUE);
      queue.add(new ThreadMetadata());
    }
    queue.add(data);
  }

  private static final class MergedEvent {
    int count = 0;
    long startTimeNanos;
    long endTimeNanos;
    TaskData data;

    /*
     * Tries to merge an additional event, i.e. if the event is close enough to the already merged
     * event.
     *
     * Returns null, if merging was possible.
     * If not mergeable, returns the TaskData of the previously merged events and clears the
     * internal data structures.
     */
    @Nullable
    TaskData maybeMerge(TaskData data) {
      long startTimeNanos = data.startTimeNanos;
      long endTimeNanos = startTimeNanos + data.durationNanos;
      if (count > 0 && startTimeNanos >= this.startTimeNanos && endTimeNanos <= this.endTimeNanos) {
        // Skips child tasks.
        return null;
      }
      if (count == 0) {
        this.data = data;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        count++;
        return null;
      } else if (startTimeNanos <= this.endTimeNanos + SLIM_PROFILE_MAXIMAL_PAUSE_NS) {
        this.endTimeNanos = endTimeNanos;
        count++;
        return null;
      } else {
        TaskData ret = getAndReset();
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.data = data;
        count = 1;
        return ret;
      }
    }

    // Returns a TaskData object representing the merged data and clears internal data structures.
    TaskData getAndReset() {
      TaskData ret;
      if (data == null || count <= 1) {
        ret = data;
      } else {
        ret =
            new TaskData(
                data.threadId,
                this.startTimeNanos,
                this.endTimeNanos - this.startTimeNanos,
                "merged " + count + " events");
      }
      count = 0;
      data = null;
      return ret;
    }
  }

  private static boolean isCandidateForMerging(TaskData data) {
    return data.durationNanos > 0
        && data.durationNanos < SLIM_PROFILE_MAXIMAL_DURATION_NS
        && data.type != ProfilerTask.CRITICAL_PATH_COMPONENT;
  }

  /**
   * Saves all gathered information from taskQueue queue to the file. Method is invoked internally
   * by the Timer-based thread and at the end of profiling session.
   */
  @Override
  public void run() {
    try {
      boolean receivedPoisonPill = false;
      try (JsonWriter writer =
          new JsonWriter(
              // The buffer size of 262144 is chosen at random.
              new OutputStreamWriter(
                  new BufferedOutputStream(outStream, 262144), StandardCharsets.UTF_8))) {
        var finishDate = Instant.now();
        writer.beginObject();
        writer.name("otherData");
        writer.beginObject();
        writer.name("bazel_version").value(BlazeVersionInfo.instance().getReleaseName());
        writer.name("build_id").value(buildID.toString());
        writer.name("output_base").value(outputBase);
        writer.name("date").value(finishDate.toString());
        writer.name("profile_finish_ts").value(finishDate.getEpochSecond() * 1000);
        writer.endObject();
        writer.name("traceEvents");
        writer.beginArray();

        // Generate metadata event for the critical path as thread 0 in disguise.
        ThreadMetadata criticalPathMetadata =
            ThreadMetadata.createFakeThreadMetadataForCriticalPath();
        criticalPathMetadata.writeTraceData(writer, profileStartTimeNanos);

        HashMap<Long, MergedEvent> eventsPerThread = new HashMap<>();
        int eventCount = 0;
        TraceData data;
        while ((data = queue.take()) != POISON_PILL) {
          Preconditions.checkNotNull(data);
          eventCount++;

          if (slimProfile
              && eventCount > SLIM_PROFILE_EVENT_THRESHOLD
              && data instanceof TaskData
              && isCandidateForMerging((TaskData) data)) {
            TaskData taskData = (TaskData) data;
            eventsPerThread.putIfAbsent(taskData.threadId, new MergedEvent());
            TaskData mergedTaskData = eventsPerThread.get(taskData.threadId).maybeMerge(taskData);
            if (mergedTaskData != null) {
              mergedTaskData.writeTraceData(writer, profileStartTimeNanos);
            }
          } else {
            data.writeTraceData(writer, profileStartTimeNanos);
          }
        }
        for (JsonTraceFileWriter.MergedEvent value : eventsPerThread.values()) {
          TaskData taskData = value.getAndReset();
          if (taskData != null) {
            taskData.writeTraceData(writer, profileStartTimeNanos);
          }
        }
        receivedPoisonPill = true;
        writer.setIndent("  ");
        writer.endArray();
        writer.endObject();
      } catch (IOException e) {
        this.savedException = e;
        if (!receivedPoisonPill) {
          while (queue.take() != POISON_PILL) {
            // We keep emptying the queue, but we can't write anything.
          }
        }
      }
    } catch (InterruptedException e) {
      // Exit silently.
    }
  }
}
