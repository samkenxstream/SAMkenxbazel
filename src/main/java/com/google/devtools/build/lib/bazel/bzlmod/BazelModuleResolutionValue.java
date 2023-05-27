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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * The result of the selection process, containing both the pruned and the un-pruned dependency
 * graphs.
 */
@AutoValue
abstract class BazelModuleResolutionValue implements SkyValue {
  /* TODO(andreisolo): Also load the modules overridden by {@code single_version_override} or
      NonRegistryOverride if we need to detect changes in the dependency graph caused by them.
  */

  @SerializationConstant
  public static final SkyKey KEY = () -> SkyFunctions.BAZEL_MODULE_RESOLUTION;

  /** Final dep graph sorted in BFS iteration order, with unused modules removed. */
  abstract ImmutableMap<ModuleKey, Module> getResolvedDepGraph();

  /**
   * Un-pruned dep graph, with updated dep keys, and additionally containing the unused modules
   * which were initially discovered (and their MODULE.bazel files loaded). Does not contain modules
   * overridden by {@code single_version_override} or {@link NonRegistryOverride}, only by {@code
   * multiple_version_override}.
   */
  abstract ImmutableMap<ModuleKey, InterimModule> getUnprunedDepGraph();

  static BazelModuleResolutionValue create(
      ImmutableMap<ModuleKey, Module> resolvedDepGraph,
      ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph) {
    return new AutoValue_BazelModuleResolutionValue(resolvedDepGraph, unprunedDepGraph);
  }
}
