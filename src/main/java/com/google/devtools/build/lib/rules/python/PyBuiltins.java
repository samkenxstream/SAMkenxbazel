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
package com.google.devtools.build.lib.rules.python;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RepoMappingManifestAction;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.SingleRunfilesSupplier;
import com.google.devtools.build.lib.analysis.SourceManifestAction;
import com.google.devtools.build.lib.analysis.SourceManifestAction.ManifestType;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** Bridge to allow builtins bzl code to call Java code. */
@StarlarkBuiltin(name = "py_builtins", documented = false)
public abstract class PyBuiltins implements StarlarkValue {
  public static final String NAME = "py_builtins";

  private final Runfiles.EmptyFilesSupplier emptyFilesSupplier;

  protected PyBuiltins(Runfiles.EmptyFilesSupplier emptyFilesSupplier) {
    this.emptyFilesSupplier = emptyFilesSupplier;
  }

  @StarlarkMethod(
      name = "is_bzlmod_enabled",
      doc = "Tells if bzlmod is enabled",
      parameters = {
        @Param(name = "ctx", positional = true, named = true, defaultValue = "unbound")
      })
  public boolean isBzlmodEnabled(StarlarkRuleContext starlarkCtx) {
    return starlarkCtx
        .getRuleContext()
        .getAnalysisEnvironment()
        .getStarlarkSemantics()
        .getBool(BuildLanguageOptions.ENABLE_BZLMOD);
  }

  @StarlarkMethod(
      name = "is_singleton_depset",
      doc = "Efficiently checks if the depset is a singleton.",
      parameters = {
        @Param(
            name = "value",
            positional = true,
            named = false,
            defaultValue = "unbound",
            doc = "depset to check for being a singleton")
      })
  public boolean isSingletonDepset(Depset depset) {
    return depset.getSet().isSingleton();
  }

  @StarlarkMethod(
      name = "regex_match",
      doc = "Return true if subject matches pattern; pattern is implicitly anchored with ^ and $",
      parameters = {
        @Param(name = "subject", positional = true, named = false, defaultValue = "unbound"),
        @Param(name = "pattern", positional = true, named = false, defaultValue = "unbound"),
      })
  public boolean regexMatch(String subject, String pattern) {
    return subject.matches(pattern);
  }

  @StarlarkMethod(
      name = "get_legacy_external_runfiles",
      doc = "Get the --legacy_external_runfiles flag value",
      parameters = {
        @Param(name = "ctx", positional = true, named = true, defaultValue = "unbound")
      })
  public boolean getLegacyExternalRunfiles(StarlarkRuleContext starlarkCtx) throws EvalException {
    return starlarkCtx.getConfiguration().legacyExternalRunfiles();
  }

  @StarlarkMethod(
      name = "get_rule_name",
      doc = "Get the name of the rule for the given ctx",
      parameters = {
        @Param(name = "ctx", positional = true, named = true, defaultValue = "unbound")
      })
  public String getRuleName(StarlarkRuleContext starlarkCtx) throws EvalException {
    return starlarkCtx.getRuleContext().getRule().getRuleClass();
  }

  @StarlarkMethod(
      name = "get_current_os_name",
      doc = "Get the name of the OS Bazel itself is running on.",
      parameters = {})
  public String getCurrentOsName() {
    return OS.getCurrent().getCanonicalName();
  }

  // TODO(b/69113360): Remove once par-generation is moved out of the py_binary rule itself.
  @StarlarkMethod(
      name = "new_runfiles_supplier",
      doc = "Create a RunfilesSupplier, which can be passed to ctx.actions.run.input_manifests.",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "runfiles_dir", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "runfiles", positional = false, named = true, defaultValue = "unbound"),
      })
  public Object addEnv(StarlarkRuleContext ruleContext, String runfilesStr, Runfiles runfiles)
      throws EvalException {
    return new SingleRunfilesSupplier(
        PathFragment.create(runfilesStr),
        runfiles,
        /* manifest= */ null,
        /* repoMappingManifest= */ null,
        ruleContext.getConfiguration().buildRunfileLinks(),
        ruleContext.getConfiguration().runfilesEnabled());
  }

  // TODO(rlevasseur): Remove once Starlark exposes this directly, see
  // https://github.com/bazelbuild/bazel/issues/15164
  @StarlarkMethod(
      name = "get_action_input_manifest_mappings",
      doc =
          "Get the set of runfiles passed to the action. These are the runfiles from "
              + "the `input_manifests`, `tools`, and `executable` args of ctx.actions.run."
              + "The return value is "
              + "dict[str runfiles_dir, dict[str runfiles_relative_path, optional File]], "
              + "which is a dict that maps the runfile directories to a dict of the path->File "
              + "entries within each runfiles directory. A File value will be None when the "
              + "path came from runfiles.empty_filesnames. If the passed in action doesn't "
              + "support fetching its runfiles mapping, None is returned.",
      parameters = {
        @Param(name = "action", positional = true, named = true, defaultValue = "unbound"),
      })
  public Object getActionRunfilesArtifacts(Object actionUnchecked) {
    if (!(actionUnchecked instanceof ActionExecutionMetadata)) {
      // There's many action implementations, and the Starlark caller can't check if they're
      // passing a valid one ahead of time. So return None instead of failing and crashing.
      return Starlark.NONE;
    }
    ActionExecutionMetadata action = (ActionExecutionMetadata) actionUnchecked;

    Dict.Builder<String, Dict<String, StarlarkValue>> inputManifest = Dict.builder();
    for (var outerEntry : action.getRunfilesSupplier().getMappings().entrySet()) {
      Dict.Builder<String, StarlarkValue> runfilesMap = Dict.builder();
      for (var innerEntry : outerEntry.getValue().entrySet()) {
        Artifact value = innerEntry.getValue();
        // NOTE: value may be null. This happens for Runfiles.empty_filenames entries.
        runfilesMap.put(innerEntry.getKey().getPathString(), value == null ? Starlark.NONE : value);
      }
      inputManifest.put(outerEntry.getKey().getPathString(), runfilesMap.buildImmutable());
    }
    return inputManifest.buildImmutable();
  }

  @StarlarkMethod(
      name = "get_label_repo_runfiles_path",
      doc = "Given a label, return a runfiles path that includes the repository directory",
      parameters = {
        @Param(name = "label", positional = true, named = true, defaultValue = "unbound"),
      })
  public String getLabelRepoRunfilesPath(Label label) {
    return label.getPackageIdentifier().getRunfilesPath().getPathString();
  }
  // TODO(bazel-team): Remove this once rules are switched to using execpath semanatics for the
  // $(location) function. See https://github.com/bazelbuild/bazel/issues/15294
  @StarlarkMethod(
      name = "expand_location_and_make_variables",
      doc =
          "Expands $(location) and makevar references. Note that $(location) performs "
              + "rootpath (runfiles-relative) expansion, not execpath expansion.",
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Rule context"),
        @Param(
            name = "attribute_name",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Name of attribute being expanded; only used for error reporting."),
        @Param(
            name = "expression",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "The expression to expand."),
        @Param(
            name = "targets",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "List of additional targets to allow in expansions"),
      })
  public Object expandLocationAndMakeVariables(
      StarlarkRuleContext ruleContext, String attributeName, String expression, Sequence<?> targets)
      throws EvalException {
    ImmutableMap.Builder<Label, ImmutableCollection<Artifact>> builder = ImmutableMap.builder();

    for (TransitiveInfoCollection current :
        Sequence.cast(targets, TransitiveInfoCollection.class, "targets")) {

      ImmutableList<Artifact> artifacts;
      // This logic is basically a copy of how LocationExpander.java treats the data attribute.
      var filesToRun = current.getProvider(FilesToRunProvider.class);
      if (filesToRun == null) {
        artifacts = current.getProvider(FileProvider.class).getFilesToBuild().toList();
      } else {
        Artifact executable = filesToRun == null ? null : filesToRun.getExecutable();
        if (executable == null) {
          artifacts = filesToRun.getFilesToRun().toList();
        } else {
          artifacts = ImmutableList.of(executable);
        }
      }
      builder.put(AliasProvider.getDependencyLabel(current), artifacts);
    }

    return ruleContext
        .getRuleContext()
        .getExpander(builder.buildOrThrow())
        .withDataLocations() // Enables $(location) expansion.
        .expand(attributeName, expression);
  }

  // TODO(b/232136319): Remove this once the --experimental_build_transitive_python_runfiles
  // flag is flipped and removed.
  @StarlarkMethod(
      name = "new_empty_runfiles_with_middleman",
      doc = "Create an empty runfiles object with the current ctx's middleman attached.",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(
            name = "runfiles_for_runfiles_support",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc =
                "Runfiles used to create RunfilesSupport; they are not added to the "
                    + "returned runfiles object."),
        @Param(
            name = "executable_for_runfiles_support",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc =
                "File; used to create RunfilesSupport; they are not added to the "
                    + "returned runfiles object."),
      })
  public Object newEmptyRunfilesWithMiddleman(
      StarlarkRuleContext starlarkCtx, Runfiles runfiles, Artifact executable)
      throws EvalException {
    // NOTE: The RunfilesSupport created here must exactly match the one done as part of Starlark
    // rule processing, otherwise action output conflicts occur. See
    // https://github.com/bazelbuild/bazel/blob/1940c5d68136ce2079efa8ff74d4e5fdf63ee3e6/src/main/java/com/google/devtools/build/lib/analysis/starlark/StarlarkRuleConfiguredTargetUtil.java#L642-L651
    RunfilesSupport runfilesSupport =
        RunfilesSupport.withExecutable(starlarkCtx.getRuleContext(), runfiles, executable);
    return new Runfiles.Builder(
            starlarkCtx.getWorkspaceName(), starlarkCtx.getConfiguration().legacyExternalRunfiles())
        .addLegacyExtraMiddleman(runfilesSupport.getRunfilesMiddleman())
        .build();
  }

  @StarlarkMethod(
      name = "create_repo_mapping_manifest",
      doc = "Write a repo_mapping file for the given runfiles",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "runfiles", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "output", positional = false, named = true, defaultValue = "unbound")
      })
  public void repoMappingAction(
      StarlarkRuleContext starlarkCtx, Runfiles runfiles, Artifact repoMappingManifest) {
    var ruleContext = starlarkCtx.getRuleContext();
    ruleContext
        .getAnalysisEnvironment()
        .registerAction(
            new RepoMappingManifestAction(
                ruleContext.getActionOwner(),
                repoMappingManifest,
                ruleContext.getTransitivePackagesForRunfileRepoMappingManifest(),
                runfiles.getAllArtifacts(),
                ruleContext.getWorkspaceName()));
  }

  @StarlarkMethod(
      name = "merge_runfiles_with_generated_inits_empty_files_supplier",
      doc =
          "Create a runfiles that generates missing __init__.py files using Java and "
              + "the internal EmptyFilesProvider interface",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(
            name = "runfiles",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Runfiles to merge into the result; must be non-empty"),
      })
  public Object mergeRunfilesWithGeneratedInitsEmptyFilesSupplier(
      StarlarkRuleContext starlarkCtx, Runfiles runfiles) throws EvalException {
    if (runfiles.isEmpty()) {
      // The Runfiles merge functions have an optimization to detect an empty runfiles and return a
      // singleton. Unfortunately, this optimization considers an empty runfiles with
      // a emptyFilesSupplier as empty, so then drops it when returning the singleton. To work
      // around this, require that there is *something* in the runfiles.
      throw Starlark.errorf("input runfiles cannot be empty");
    }
    return new Runfiles.Builder(
            starlarkCtx.getWorkspaceName(), starlarkCtx.getConfiguration().legacyExternalRunfiles())
        .setEmptyFilesSupplier(emptyFilesSupplier)
        .merge(runfiles)
        .build();
  }

  // TODO(https://github.com/bazelbuild/bazel/issues/17415): Remove this method one
  // --legacy_external_runfiles is defaulted to false
  @StarlarkMethod(
      name = "make_runfiles_respect_legacy_external_runfiles",
      doc =
          "Like ctx.runfiles().merge(), except the --legacy_external_runfiles flag "
              + "is respected, otherwise files in other repos don't have the legacy "
              + " external/ path show up; see https://github.com/bazelbuild/bazel/issues/17415",
      parameters = {
        @Param(name = "ctx", positional = true, named = true, defaultValue = "unbound"),
        @Param(
            name = "runfiles",
            positional = true,
            named = true,
            defaultValue = "unbound",
            doc = "Runfiles to include"),
      })
  public Object mergeAllRunfilesRespectExternalLegacyRunfiles(
      StarlarkRuleContext starlarkCtx, Runfiles runfiles) throws EvalException {
    return new Runfiles.Builder(
            starlarkCtx.getWorkspaceName(), starlarkCtx.getConfiguration().legacyExternalRunfiles())
        .merge(runfiles)
        .build();
  }

  @StarlarkMethod(
      name = "declare_constant_metadata_file",
      doc = "Declare a file that always reports it is unchanged.",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "name", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "root", positional = false, named = true, defaultValue = "unbound"),
      })
  public Object declareConstantMetadataFile(
      StarlarkRuleContext ctx, String name, Object rootUnchecked) {
    ArtifactRoot root = (ArtifactRoot) rootUnchecked;
    return ctx.getRuleContext()
        .getAnalysisEnvironment()
        .getConstantMetadataArtifact(
            ctx.getRuleContext().getPackageDirectory().getRelative(PathFragment.create(name)),
            root);
  }

  @StarlarkMethod(
      name = "create_sources_only_manifest",
      doc = "Create a manifest of the files in runfiles",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "runfiles", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "output", positional = false, named = true, defaultValue = "unbound")
      })
  public void createRunfilesManifest(
      StarlarkRuleContext starlarkCtx, Runfiles runfiles, Artifact output) {
    var ruleContext = starlarkCtx.getRuleContext();
    ruleContext
        .getAnalysisEnvironment()
        .registerAction(
            new SourceManifestAction(
                ManifestType.SOURCES_ONLY,
                ruleContext.getActionOwner(),
                output,
                runfiles,
                /* repoMappingManifest= */ null,
                ruleContext.getConfiguration().remotableSourceManifestActions()));
  }

  @StarlarkMethod(
      name = "copy_without_caching",
      doc = "Copy one file to another, but with action caching disabled.",
      parameters = {
        @Param(name = "ctx", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "read_from", positional = false, named = true, defaultValue = "unbound"),
        @Param(name = "write_to", positional = false, named = true, defaultValue = "unbound"),
      })
  public Object copyWithoutCaching(StarlarkRuleContext ctx, Artifact readFrom, Artifact writeTo)
      throws InterruptedException {
    var ruleContext = ctx.getRuleContext();
    ruleContext.registerAction(
        new CopyWithoutCachingAction(ruleContext.getActionOwner(), readFrom, writeTo));
    return Starlark.NONE;
  }

  @Immutable
  static final class CopyWithoutCachingAction extends AbstractFileWriteAction {
    private static final String GUID = "67513fa7-3824-493b-aeab-95a8b778ea07";

    CopyWithoutCachingAction(ActionOwner owner, Artifact readFrom, Artifact writeTo) {
      super(
          owner,
          NestedSetBuilder.create(Order.STABLE_ORDER, readFrom),
          writeTo,
          /* makeExecutable= */ false);
    }

    @Override
    public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
      return out -> ctx.getInputPath(getPrimaryInput()).getInputStream().transferTo(out);
    }

    @Override
    public boolean executeUnconditionally() {
      return true;
    }

    @Override
    public boolean isVolatile() {
      return true;
    }

    // NOTE: This method is effectively unused because executeUnconditionally=true.
    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable Artifact.ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString(GUID);
      fp.addPath(getPrimaryInput().getPath());
      fp.addPath(getPrimaryOutput().getPath());
    }

    @Override
    public String describeKey() {
      StringBuilder message = new StringBuilder();
      message
          .append("executeUnconditionally: ")
          .append(executeUnconditionally())
          .append("\nGUID: ")
          .append(GUID)
          .append("\nreadFrom: ")
          .append(getPrimaryInput().getExecPathString())
          .append("\nwriteTo: ")
          .append(getPrimaryOutput().getExecPathString())
          .append('\n');

      return message.toString();
    }

    @Override
    protected String getRawProgressMessage() {
      return String.format(
          "Copying %s to %s (uncachable action)",
          getPrimaryInput().getExecPathString(), getPrimaryOutput().getExecPathString());
    }

    @Override
    public String getMnemonic() {
      return "PyCopyWithoutCaching";
    }
  }

  // TODO(b/253059598): Remove support for this; https://github.com/bazelbuild/bazel/issues/16455
  @StarlarkMethod(
      name = "are_action_listeners_enabled",
      doc =
          "Tells if any action listeners are enabled. This is to prevent registering "
              + "extra actions unless necessary",
      parameters = {
        @Param(
            name = "ctx",
            positional = true,
            named = false,
            defaultValue = "unbound",
            doc = "Rule context"),
      })
  public boolean areActionListenersEnabled(StarlarkRuleContext starlarkCtx) {
    return !starlarkCtx.getRuleContext().getConfiguration().getActionListeners().isEmpty();
  }

  // TODO(b/253059598): Remove support for this; https://github.com/bazelbuild/bazel/issues/16455
  @StarlarkMethod(
      name = "add_py_extra_pseudo_action",
      doc = "Adds an extra pseudo action for (deprecated) extra actions support",
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Rule context"),
        @Param(
            name = "dependency_transitive_python_sources",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Depset of Artifacts from PyInfo.transitive_sources from the deps attribute"),
      })
  public void addPyExtraActionPseudoAction(
      StarlarkRuleContext starlarkCtx, Depset uncheckedDependencyTransitivePythonSources)
      throws EvalException {
    NestedSet<Artifact> dependencyTransitivePythonSources =
        Depset.cast(
            uncheckedDependencyTransitivePythonSources,
            Artifact.class,
            "dependency_transitive_python_sources");
    PyCommon.registerPyExtraActionPseudoAction(
        starlarkCtx.getRuleContext(), dependencyTransitivePythonSources);
  }

  private static final StarlarkProvider starlarkVisibleForTestingInfo =
      StarlarkProvider.builder(Location.BUILTIN)
          .setExported(
              new StarlarkProvider.Key(
                  Label.parseCanonicalUnchecked(
                      "//tools/build_defs/python/tests/base_rules:util.bzl"),
                  "VisibleForTestingInfo"))
          .build();

  @StarlarkMethod(name = "VisibleForTestingInfo", documented = false, structField = true)
  public StarlarkProvider visibleForTestingInfo() throws EvalException {
    return starlarkVisibleForTestingInfo;
  }
}
