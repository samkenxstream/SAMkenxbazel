// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.cpp;

import com.google.devtools.build.docgen.annot.DocCategory;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * Interface for FDO context
 *
 * <p>It is not expected for this to be used externally at this time. This API is experimental and
 * subject to change, and its usage should be restricted to internal packages.
 *
 * <p>See javadoc for {@link com.google.devtools.build.lib.rules.cpp.CcModule}.
 */
@StarlarkBuiltin(name = "CcFdoContext", category = DocCategory.TOP_LEVEL_MODULE, documented = false)
public interface FdoContextApi<BranchFdoProfileT extends BranchFdoProfileApi>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "branch_fdo_profile",
      allowReturnNones = true,
      documented = false,
      useStarlarkThread = true)
  @Nullable
  BranchFdoProfileT getBranchFdoProfileForStarlark(StarlarkThread thread) throws EvalException;
}
