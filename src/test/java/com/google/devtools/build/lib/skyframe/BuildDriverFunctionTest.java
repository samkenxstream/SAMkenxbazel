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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupKeyOrProxy;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.skyframe.BuildDriverFunction.ActionLookupValuesCollectionResult;
import com.google.devtools.build.lib.skyframe.BuildDriverFunction.TransitiveActionLookupValuesHelper;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for BuildDriverFunction. */
@RunWith(JUnit4.class)
public class BuildDriverFunctionTest {

  @Test
  public void checkActionConflicts_noConflict_conflictFreeKeysRegistered() throws Exception {
    Set<ActionLookupKeyOrProxy> globalSet = new HashSet<>();
    IncrementalArtifactConflictFinder dummyConflictFinder =
        IncrementalArtifactConflictFinder.createWithActionGraph(mock(MutableActionGraph.class));
    TransitiveActionLookupValuesHelper fakeHelper =
        new TransitiveActionLookupValuesHelper() {
          @Override
          public ActionLookupValuesCollectionResult collect(ActionLookupKeyOrProxy key) {
            return ActionLookupValuesCollectionResult.create(
                ImmutableSet.of(), ImmutableSet.of(key.toKey()));
          }

          @Override
          public void registerConflictFreeKeys(ImmutableSet<ActionLookupKey> keys) {
            globalSet.addAll(keys);
          }
        };
    ActionLookupKey dummyKey = mock(ActionLookupKey.class);
    when(dummyKey.toKey()).thenReturn(dummyKey);
    BuildDriverFunction function =
        new BuildDriverFunction(
            fakeHelper,
            () -> dummyConflictFinder,
            /* ruleContextConstraintSemantics= */ null,
            /* extraActionFilterSupplier= */ null,
            /* testTypeResolver= */ null);

    var unused = function.checkActionConflicts(dummyKey, /* strictConflictCheck= */ true);

    assertThat(globalSet).containsExactly(dummyKey);
  }
}
