// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.SourceCategory;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.CollidingProvidesException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.HeadersCheckingMode;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

/** Common parts of the implementation of cc rules. */
public final class CcCommon implements StarlarkValue {

  /** Name of the build variable for the sysroot path variable name. */
  public static final String SYSROOT_VARIABLE_NAME = "sysroot";

  /** Name of the build variable for the path to the input file being processed. */
  public static final String INPUT_FILE_VARIABLE_NAME = "input_file";

  /** Name of the build variable for the minimum_os_version being targeted. */
  public static final String MINIMUM_OS_VERSION_VARIABLE_NAME = "minimum_os_version";

  public static final String PIC_CONFIGURATION_ERROR =
      "PIC compilation is requested but the toolchain does not support it "
          + "(feature named 'supports_pic' is not enabled)";

  private static final String NO_COPTS_ATTRIBUTE = "nocopts";

  public static final ImmutableSet<String> ALL_COMPILE_ACTIONS =
      ImmutableSet.of(
          CppActionNames.C_COMPILE,
          CppActionNames.CPP_COMPILE,
          CppActionNames.CPP_HEADER_PARSING,
          CppActionNames.CPP_MODULE_COMPILE,
          CppActionNames.CPP_MODULE_CODEGEN,
          CppActionNames.ASSEMBLE,
          CppActionNames.PREPROCESS_ASSEMBLE,
          CppActionNames.CLIF_MATCH,
          CppActionNames.LINKSTAMP_COMPILE,
          CppActionNames.CC_FLAGS_MAKE_VARIABLE,
          CppActionNames.LTO_BACKEND,
          CppActionNames.CPP_HEADER_ANALYSIS);

  public static final ImmutableSet<String> ALL_LINK_ACTIONS =
      ImmutableSet.of(
          CppActionNames.LTO_INDEX_EXECUTABLE,
          CppActionNames.LTO_INDEX_DYNAMIC_LIBRARY,
          CppActionNames.LTO_INDEX_NODEPS_DYNAMIC_LIBRARY,
          LinkTargetType.EXECUTABLE.getActionName(),
          Link.LinkTargetType.DYNAMIC_LIBRARY.getActionName(),
          Link.LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName());

  public static final ImmutableSet<String> ALL_ARCHIVE_ACTIONS =
      ImmutableSet.of(Link.LinkTargetType.STATIC_LIBRARY.getActionName());

  public static final ImmutableSet<String> ALL_OTHER_ACTIONS =
      ImmutableSet.of(CppActionNames.STRIP);

  /** Action configs we request to enable. */
  public static final ImmutableSet<String> DEFAULT_ACTION_CONFIGS =
      ImmutableSet.<String>builder()
          .addAll(ALL_COMPILE_ACTIONS)
          .addAll(ALL_LINK_ACTIONS)
          .addAll(ALL_ARCHIVE_ACTIONS)
          .addAll(ALL_OTHER_ACTIONS)
          .build();

  public static final ImmutableSet<String> OBJC_ACTIONS =
      ImmutableSet.of(
          CppActionNames.OBJC_COMPILE,
          CppActionNames.OBJCPP_COMPILE,
          CppActionNames.OBJC_FULLY_LINK,
          CppActionNames.OBJC_EXECUTABLE);

  /** An enum for the list of supported languages. */
  public enum Language {
    CPP("c++"),
    OBJC("objc"),
    OBJCPP("objc++");

    private final String representation;

    Language(String representation) {
      this.representation = representation;
    }

    public String getRepresentation() {
      return representation;
    }
  }

  private static final String SYSROOT_FLAG = "--sysroot=";

  private final RuleContext ruleContext;

  private final CcToolchainProvider ccToolchain;
  private final FdoContext fdoContext;

  public CcCommon(RuleContext ruleContext) throws RuleErrorException {
    this(
        ruleContext,
        Preconditions.checkNotNull(
            CppHelper.getToolchainUsingDefaultCcToolchainAttribute(ruleContext)));
  }

  public CcCommon(RuleContext ruleContext, CcToolchainProvider ccToolchain) {
    this.ruleContext = ruleContext;
    this.fdoContext = ccToolchain.getFdoContext();
    this.ccToolchain = ccToolchain;
  }

  /**
   * Returns our own linkopts from the rule attribute. This determines linker options to use when
   * building this target and anything that depends on it.
   */
  public ImmutableList<String> getLinkopts() {
    Preconditions.checkState(hasAttribute("linkopts", Type.STRING_LIST));
    ImmutableList<String> result = CppHelper.getLinkopts(ruleContext);

    if (ApplePlatform.isApplePlatform(ccToolchain.getTargetCpu()) && result.contains("-static")) {
      ruleContext.attributeError(
          "linkopts", "Apple builds do not support statically linked binaries");
    }

    return ImmutableList.copyOf(result);
  }

  public ImmutableList<String> getCopts() {
    if (!getCoptsFilter(ruleContext).passesFilter("-Wno-future-warnings")) {
      ruleContext.attributeWarning(
          "nocopts",
          String.format(
              "Regular expression '%s' is too general; for example, it matches "
                  + "'-Wno-future-warnings'.  Thus it might *re-enable* compiler warnings we wish "
                  + "to disable globally.  To disable all compiler warnings, add '-w' to copts "
                  + "instead",
              Preconditions.checkNotNull(getNoCoptsPattern(ruleContext))));
    }

    return ImmutableList.copyOf(CppHelper.getAttributeCopts(ruleContext));
  }

  private boolean hasAttribute(String name, Type<?> type) {
    return ruleContext.attributes().has(name, type);
  }

  /**
   * Returns a list of ({@link Artifact}, {@link Label}) pairs. Each pair represents an input source
   * file and the label of the rule that generates it (or the label of the source file itself if it
   * is an input file).
   */
  List<Pair<Artifact, Label>> getPrivateHeaders() {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    Iterable<? extends TransitiveInfoCollection> providers =
        ruleContext.getPrerequisitesIf("srcs", FileProvider.class);
    for (TransitiveInfoCollection provider : providers) {
      for (Artifact artifact :
          provider.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        // TODO(bazel-team): We currently do not produce an error for duplicate headers and other
        // non-source artifacts with different labels, as that would require cleaning up the code
        // base without significant benefit; we should eventually make this consistent one way or
        // the other.
        if (CppFileTypes.CPP_HEADER.matches(artifact.getExecPath())) {
          map.put(artifact, provider.getLabel());
        }
      }
    }
    return mapToListOfPairs(map);
  }

  /**
   * Returns a list of ({@link Artifact}, {@link Label}) pairs. Each pair represents an input source
   * file and the label of the rule that generates it (or the label of the source file itself if it
   * is an input file).
   */
  List<Pair<Artifact, Label>> getSources() {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    Iterable<? extends TransitiveInfoCollection> providers =
        ruleContext.getPrerequisitesIf("srcs", FileProvider.class);
    for (TransitiveInfoCollection provider : providers) {
      for (Artifact artifact :
          provider.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        if (!CppFileTypes.CPP_HEADER.matches(artifact.getExecPath())) {
          Label oldLabel = map.put(artifact, provider.getLabel());
          if (SourceCategory.CC_AND_OBJC.getSourceTypes().matches(artifact.getExecPathString())
              && oldLabel != null
              && !oldLabel.equals(provider.getLabel())) {
            ruleContext.attributeError(
                "srcs",
                String.format(
                    "Artifact '%s' is duplicated (through '%s' and '%s')",
                    artifact.getExecPathString(), oldLabel, provider.getLabel()));
          }
        }
      }
    }
    return mapToListOfPairs(map);
  }

  private List<Pair<Artifact, Label>> mapToListOfPairs(Map<Artifact, Label> map) {
    ImmutableList.Builder<Pair<Artifact, Label>> result = ImmutableList.builder();
    for (Map.Entry<Artifact, Label> entry : map.entrySet()) {
      result.add(Pair.of(entry.getKey(), entry.getValue()));
    }
    return result.build();
  }

  /**
   * Returns the files from headers and does some checks. Note that this method reports warnings to
   * the {@link RuleContext} as a side effect, and so should only be called once for any given rule.
   */
  public static List<Pair<Artifact, Label>> getHeaders(RuleContext ruleContext) {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    for (TransitiveInfoCollection target :
        ruleContext.getPrerequisitesIf("hdrs", FileProvider.class)) {
      FileProvider provider = target.getProvider(FileProvider.class);
      for (Artifact artifact : provider.getFilesToBuild().toList()) {
        if (CppRuleClasses.DISALLOWED_HDRS_FILES.matches(artifact.getFilename())) {
          ruleContext.attributeWarning(
              "hdrs",
              "file '"
                  + artifact.getFilename()
                  + "' from target '"
                  + target.getLabel()
                  + "' is not allowed in hdrs");
          continue;
        }
        Label oldLabel = map.put(artifact, target.getLabel());
        if (oldLabel != null && !oldLabel.equals(target.getLabel())) {
          ruleContext.attributeWarning(
              "hdrs",
              String.format(
                  "Artifact '%s' is duplicated (through '%s' and '%s')",
                  artifact.getExecPathString(), oldLabel, target.getLabel()));
        }
      }
    }

    ImmutableList.Builder<Pair<Artifact, Label>> result = ImmutableList.builder();
    for (Map.Entry<Artifact, Label> entry : map.entrySet()) {
      result.add(Pair.of(entry.getKey(), entry.getValue()));
    }
    return result.build();
  }

  /**
   * Returns the files from headers and does some checks. Note that this method reports warnings to
   * the {@link RuleContext} as a side effect, and so should only be called once for any given rule.
   */
  public List<Pair<Artifact, Label>> getHeaders() {
    return getHeaders(ruleContext);
  }

  /** Returns the C++ toolchain provider. */
  public CcToolchainProvider getToolchain() {
    return ccToolchain;
  }

  /** Returns the C++ FDO optimization support provider. */
  public FdoContext getFdoContext() {
    return fdoContext;
  }

  public static void reportInvalidOptions(
      RuleContext ruleContext, CppConfiguration cppConfiguration, CcToolchainProvider ccToolchain) {
    if (cppConfiguration.getLibcTopLabel() != null && ccToolchain.getDefaultSysroot() == null) {
      ruleContext.ruleError(
          "The selected toolchain "
              + ccToolchain.getToolchainIdentifier()
              + " does not support setting --grte_top (it doesn't specify builtin_sysroot).");
    }
  }

  /**
   * Supply CC_FLAGS Make variable value computed from FeatureConfiguration. Appends them to
   * original CC_FLAGS, so FeatureConfiguration can override legacy values.
   */
  public static class CcFlagsSupplier implements MakeVariableSupplier {

    private final RuleContext ruleContext;

    public CcFlagsSupplier(RuleContext ruleContext) {
      this.ruleContext = Preconditions.checkNotNull(ruleContext);
    }

    @Override
    @Nullable
    public String getMakeVariable(String variableName) throws ExpansionException {
      if (!variableName.equals(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME)) {
        return null;
      }

      TransitiveInfoCollection toolchain;
      if (ruleContext.attributes().has(CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME)) {
        toolchain = ruleContext.getPrerequisite(CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME);
      } else {
        toolchain =
            ruleContext.getPrerequisite(
                CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME_FOR_STARLARK);
      }

      try {
        return CcCommon.computeCcFlags(ruleContext, toolchain);
      } catch (RuleErrorException e) {
        throw new ExpansionException(e.getMessage());
      }
    }

    @Override
    public ImmutableMap<String, String> getAllMakeVariables() throws ExpansionException {
      return ImmutableMap.of(
          CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME,
          getMakeVariable(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME));
    }
  }

  /** A filter that removes copts from a c++ compile action according to a nocopts regex. */
  public static final class CoptsFilter {
    private final Pattern noCoptsPattern;
    private final boolean allPasses;

    private CoptsFilter(Pattern noCoptsPattern, boolean allPasses) {
      this.noCoptsPattern = noCoptsPattern;
      this.allPasses = allPasses;
    }

    /** Creates a filter that filters all matches to a regex. */
    public static CoptsFilter fromRegex(Pattern noCoptsPattern) {
      return new CoptsFilter(noCoptsPattern, false);
    }

    /** Creates a filter that passes on all inputs. */
    public static CoptsFilter alwaysPasses() {
      return new CoptsFilter(null, true);
    }

    /**
     * Returns true if the provided string passes through the filter, or false if it should be
     * removed.
     */
    public boolean passesFilter(String flag) {
      if (allPasses) {
        return true;
      } else {
        return !noCoptsPattern.matcher(flag).matches();
      }
    }
  }

  private static CoptsFilter getCoptsFilter(RuleContext ruleContext) {
    Pattern noCoptsPattern = getNoCoptsPattern(ruleContext);
    if (noCoptsPattern == null) {
      return CoptsFilter.alwaysPasses();
    }
    return CoptsFilter.fromRegex(noCoptsPattern);
  }

  @Nullable
  private static Pattern getNoCoptsPattern(RuleContext ruleContext) {
    if (!ruleContext.getRule().isAttrDefined(NO_COPTS_ATTRIBUTE, Type.STRING)) {
      return null;
    }
    String nocoptsValue = ruleContext.attributes().get(NO_COPTS_ATTRIBUTE, Type.STRING);
    if (Strings.isNullOrEmpty(nocoptsValue)) {
      return null;
    }

    if (ruleContext.getConfiguration().getFragment(CppConfiguration.class).disableNoCopts()) {
      ruleContext.attributeError(
          NO_COPTS_ATTRIBUTE,
          "This attribute was removed. See https://github.com/bazelbuild/bazel/issues/8706 for"
              + " details.");
    }

    String nocoptsAttr = ruleContext.getExpander().expand(NO_COPTS_ATTRIBUTE, nocoptsValue);
    try {
      return Pattern.compile(nocoptsAttr);
    } catch (PatternSyntaxException e) {
      ruleContext.attributeError(
          NO_COPTS_ATTRIBUTE,
          "invalid regular expression '" + nocoptsAttr + "': " + e.getMessage());
      return null;
    }
  }

  private static final String DEFINES_ATTRIBUTE = "defines";

  /**
   * Returns a list of define tokens from "defines" attribute.
   *
   * <p>We tokenize the "defines" attribute, to ensure that the handling of
   * quotes and backslash escapes is consistent Bazel's treatment of the "copts" attribute.
   *
   * <p>But we require that the "defines" attribute consists of a single token.
   */
  public List<String> getDefines() {
    return getDefinesFromAttribute(DEFINES_ATTRIBUTE);
  }

  private List<String> getDefinesFromAttribute(String attr) {
    List<String> defines = new ArrayList<>();

    // collect labels that can be subsituted in defines
    Map<Label, ImmutableCollection<Artifact>> map = Maps.newLinkedHashMap();

    if (ruleContext.attributes().has("deps", LABEL_LIST)) {
      for (TransitiveInfoCollection current : ruleContext.getPrerequisites("deps")) {
        map.put(
            AliasProvider.getDependencyLabel(current),
            current.getProvider(FileProvider.class).getFilesToBuild().toList());
      }
    }

    if (ruleContext.attributes().has("data", LABEL_LIST)) {
      for (TransitiveInfoCollection current : ruleContext.getPrerequisites("data")) {
        Label dataDependencyLabel = AliasProvider.getDependencyLabel(current);
        if (!map.containsKey(dataDependencyLabel)) {
          map.put(
              dataDependencyLabel,
              current.getProvider(FileProvider.class).getFilesToBuild().toList());
        }
      }
    }
    // tokenize defines and substitute make variables
    for (String define :
        ruleContext.getExpander().withExecLocations(ImmutableMap.copyOf(map)).list(attr)) {
      List<String> tokens = new ArrayList<>();
      try {
        ShellUtils.tokenize(tokens, define);
        if (tokens.size() == 1) {
          defines.add(tokens.get(0));
        } else if (tokens.isEmpty()) {
          ruleContext.attributeError(attr, "empty definition not allowed");
        } else {
          ruleContext.attributeError(
              attr,
              String.format(
                  "definition contains too many tokens (found %d, expecting exactly one)",
                  tokens.size()));
        }
      } catch (ShellUtils.TokenizationException e) {
        ruleContext.attributeError(attr, e.getMessage());
      }
    }
    return defines;
  }

  @StarlarkMethod(name = "loose_include_dirs", structField = true, documented = false)
  public Sequence<String> getLooseIncludeDirsForStarlark() {
    return StarlarkList.immutableCopyOf(
        getLooseIncludeDirs().stream().map(PathFragment::toString).collect(toImmutableList()));
  }

  /**
   * Determines a list of loose include directories that are only allowed to be referenced when
   * headers checking is {@link HeadersCheckingMode#LOOSE}.
   */
  Set<PathFragment> getLooseIncludeDirs() {
    ImmutableSet.Builder<PathFragment> result = ImmutableSet.builder();
    // The package directory of the rule contributes includes. Note that this also covers all
    // non-subpackage sub-directories.
    PathFragment rulePackage =
        ruleContext
            .getLabel()
            .getPackageIdentifier()
            .getExecPath(ruleContext.getConfiguration().isSiblingRepositoryLayout());
    result.add(rulePackage);

    if (ruleContext
            .getConfiguration()
            .getOptions()
            .get(CppOptions.class)
            .experimentalIncludesAttributeSubpackageTraversal
        && ruleContext.getRule().isAttributeValueExplicitlySpecified("includes")) {
      PathFragment packageFragment =
          ruleContext
              .getLabel()
              .getPackageIdentifier()
              .getExecPath(ruleContext.getConfiguration().isSiblingRepositoryLayout());
      // For now, anything with an 'includes' needs a blanket declaration
      result.add(packageFragment.getRelative("**"));
    }
    return result.build();
  }

  List<PathFragment> getSystemIncludeDirs() {
    boolean siblingRepositoryLayout = ruleContext.getConfiguration().isSiblingRepositoryLayout();
    List<PathFragment> result = new ArrayList<>();
    PackageIdentifier packageIdentifier = ruleContext.getLabel().getPackageIdentifier();
    PathFragment packageExecPath = packageIdentifier.getExecPath(siblingRepositoryLayout);
    PathFragment packageSourceRoot = packageIdentifier.getPackagePath(siblingRepositoryLayout);
    for (String includesAttr : ruleContext.getExpander().list("includes")) {
      if (includesAttr.startsWith("/")) {
        ruleContext.attributeWarning("includes",
            "ignoring invalid absolute path '" + includesAttr + "'");
        continue;
      }
      PathFragment includesPath = packageExecPath.getRelative(includesAttr);
      if (!siblingRepositoryLayout && includesPath.containsUplevelReferences()) {
        ruleContext.attributeError("includes",
            "Path references a path above the execution root.");
      }
      if (includesPath.isEmpty()) {
        ruleContext.attributeError(
            "includes",
            "'"
                + includesAttr
                + "' resolves to the workspace root, which would allow this rule and all of its "
                + "transitive dependents to include any file in your workspace. Please include only"
                + " what you need");
      } else if (!includesPath.startsWith(packageExecPath)) {
        ruleContext.attributeWarning(
            "includes",
            "'"
                + includesAttr
                + "' resolves to '"
                + includesPath
                + "' not below the relative path of its package '"
                + packageExecPath
                + "'. This will be an error in the future");
      }
      result.add(includesPath);
      // We don't need to perform the above checks against outIncludesPath again since any errors
      // must have manifested in includesPath already.
      PathFragment outIncludesPath = packageSourceRoot.getRelative(includesAttr);
      if (ruleContext.getConfiguration().hasSeparateGenfilesDirectory()) {
        result.add(ruleContext.getGenfilesFragment().getRelative(outIncludesPath));
      }
      result.add(ruleContext.getBinFragment().getRelative(outIncludesPath));
    }
    return result;
  }

  /**
   * Returns all additional linker inputs specified in the |additional_linker_inputs| attribute of
   * the rule.
   */
  List<Artifact> getAdditionalLinkerInputs() {
    return ruleContext.getPrerequisiteArtifacts("additional_linker_inputs").list();
  }

  public String getPurpose(CppSemantics semantics) {
    return semantics.getClass().getSimpleName()
        + "_build_arch_"
        + ruleContext.getConfiguration().getMnemonic();
  }

  public static ImmutableList<String> getCoverageFeatures(CppConfiguration cppConfiguration) {
    ImmutableList.Builder<String> coverageFeatures = ImmutableList.builder();
    if (cppConfiguration.collectCodeCoverage()) {
      coverageFeatures.add(CppRuleClasses.COVERAGE);
      if (cppConfiguration.useLLVMCoverageMapFormat()) {
        coverageFeatures.add(CppRuleClasses.LLVM_COVERAGE_MAP_FORMAT);
      } else {
        coverageFeatures.add(CppRuleClasses.GCC_COVERAGE_MAP_FORMAT);
      }
    }
    return coverageFeatures.build();
  }

  /**
   * Creates a feature configuration for a given rule. Assumes strictly cc sources.
   *
   * @param ruleContext the context of the rule we want the feature configuration for.
   * @param toolchain C++ toolchain provider.
   * @return the feature configuration for the given {@code ruleContext}.
   */
  public static FeatureConfiguration configureFeaturesOrReportRuleError(
      RuleContext ruleContext,
      Language language,
      CcToolchainProvider toolchain,
      CppSemantics semantics) {
    return configureFeaturesOrReportRuleError(
        ruleContext,
        /* requestedFeatures= */ ruleContext.getFeatures(),
        /* unsupportedFeatures= */ ruleContext.getDisabledFeatures(),
        language,
        toolchain,
        semantics);
  }

  /**
   * Creates the feature configuration for a given rule.
   *
   * @return the feature configuration for the given {@code ruleContext}.
   */
  public static FeatureConfiguration configureFeaturesOrReportRuleError(
      RuleContext ruleContext,
      ImmutableSet<String> requestedFeatures,
      ImmutableSet<String> unsupportedFeatures,
      Language language,
      CcToolchainProvider toolchain,
      CppSemantics cppSemantics) {
    return configureFeaturesOrReportRuleError(
        ruleContext,
        ruleContext.getConfiguration(),
        requestedFeatures,
        unsupportedFeatures,
        language,
        toolchain,
        cppSemantics);
  }

  public static FeatureConfiguration configureFeaturesOrReportRuleError(
      RuleContext ruleContext,
      BuildConfigurationValue buildConfiguration,
      ImmutableSet<String> requestedFeatures,
      ImmutableSet<String> unsupportedFeatures,
      Language language,
      CcToolchainProvider toolchain,
      CppSemantics cppSemantics) {
    cppSemantics.validateLayeringCheckFeatures(
        ruleContext, /* aspectDescriptor= */ null, toolchain, ImmutableSet.of());
    try {
      return configureFeaturesOrThrowEvalException(
          requestedFeatures,
          unsupportedFeatures,
          language,
          toolchain,
          buildConfiguration.getFragment(CppConfiguration.class));
    } catch (EvalException e) {
      ruleContext.ruleError(e.getMessage());
      return FeatureConfiguration.EMPTY;
    }
  }

  public static FeatureConfiguration configureFeaturesOrThrowEvalException(
      ImmutableSet<String> requestedFeatures,
      ImmutableSet<String> unsupportedFeatures,
      Language language,
      CcToolchainProvider toolchain,
      CppConfiguration cppConfiguration)
      throws EvalException {
    ImmutableSet.Builder<String> allRequestedFeaturesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> unsupportedFeaturesBuilder = ImmutableSet.builder();
    unsupportedFeaturesBuilder.addAll(unsupportedFeatures);
    if (!toolchain.supportsHeaderParsing()) {
      // TODO(b/159096411): Remove once supports_header_parsing has been removed from the
      // cc_toolchain rule.
      unsupportedFeaturesBuilder.add(CppRuleClasses.PARSE_HEADERS);
    }

    if (language != Language.OBJC
        && language != Language.OBJCPP
        && toolchain.getCcInfo().getCcCompilationContext().getCppModuleMap() == null) {
      unsupportedFeaturesBuilder.add(CppRuleClasses.MODULE_MAPS);
    }

    if (cppConfiguration.forcePic()) {
      if (unsupportedFeatures.contains(CppRuleClasses.SUPPORTS_PIC)) {
        throw new EvalException(PIC_CONFIGURATION_ERROR);
      }
      allRequestedFeaturesBuilder.add(CppRuleClasses.SUPPORTS_PIC);
    }

    if (cppConfiguration.appleGenerateDsym()) {
      allRequestedFeaturesBuilder.add(CppRuleClasses.GENERATE_DSYM_FILE_FEATURE_NAME);
    } else {
      allRequestedFeaturesBuilder.add(CppRuleClasses.NO_GENERATE_DEBUG_SYMBOLS_FEATURE_NAME);
    }

    if (language == Language.OBJC || language == Language.OBJCPP) {
      allRequestedFeaturesBuilder.add(CppRuleClasses.LANG_OBJC);
      if (cppConfiguration.objcGenerateLinkmap()) {
        allRequestedFeaturesBuilder.add(CppRuleClasses.GENERATE_LINKMAP_FEATURE_NAME);
      }
      if (cppConfiguration.objcShouldStripBinary()) {
        allRequestedFeaturesBuilder.add(CppRuleClasses.DEAD_STRIP_FEATURE_NAME);
      }
    }

    ImmutableSet<String> allUnsupportedFeatures = unsupportedFeaturesBuilder.build();

    ImmutableList.Builder<String> allFeatures =
        new ImmutableList.Builder<String>()
            .addAll(ImmutableSet.of(cppConfiguration.getCompilationMode().toString()))
            .addAll(DEFAULT_ACTION_CONFIGS)
            .addAll(requestedFeatures)
            .addAll(toolchain.getFeatures().getDefaultFeaturesAndActionConfigs());

    if (language == Language.OBJC || language == Language.OBJCPP) {
      allFeatures.addAll(OBJC_ACTIONS);
    }

    if (!cppConfiguration.dontEnableHostNonhost()) {
      if (toolchain.isToolConfiguration()) {
        allFeatures.add("host");
      } else {
        allFeatures.add("nonhost");
      }
    }

    allFeatures.addAll(getCoverageFeatures(cppConfiguration));

    if (!allUnsupportedFeatures.contains(CppRuleClasses.FDO_INSTRUMENT)) {
      if (cppConfiguration.getFdoInstrument() != null) {
        allFeatures.add(CppRuleClasses.FDO_INSTRUMENT);
      } else {
        if (cppConfiguration.getCSFdoInstrument() != null) {
          allFeatures.add(CppRuleClasses.CS_FDO_INSTRUMENT);
        }
      }
    }

    FdoContext.BranchFdoProfile branchFdoProvider = toolchain.getFdoContext().getBranchFdoProfile();

    boolean enablePropellerOptimize =
        (cppConfiguration.getPropellerOptimizeLabel() != null
            || cppConfiguration.getPropellerOptimizeAbsoluteCCProfile() != null
            || cppConfiguration.getPropellerOptimizeAbsoluteLdProfile() != null);

    if (branchFdoProvider != null && cppConfiguration.getCompilationMode() == CompilationMode.OPT) {
      if ((branchFdoProvider.isLlvmFdo() || branchFdoProvider.isLlvmCSFdo())
          && !allUnsupportedFeatures.contains(CppRuleClasses.FDO_OPTIMIZE)) {
        allFeatures.add(CppRuleClasses.FDO_OPTIMIZE);
        // Support implicit enabling of ThinLTO for FDO unless it has been explicitly disabled.
        if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_FDO_THINLTO);
        }

        // Support implicit enabling of split functions for FDO unless it has been explicitly
        // disabled
        // or propeller_optimize is used. propeller_optimize must also disable split functions as
        // they are mutually exclusive.
        if (!allUnsupportedFeatures.contains(CppRuleClasses.SPLIT_FUNCTIONS)
            && !enablePropellerOptimize) {
          allFeatures.add(CppRuleClasses.ENABLE_FDO_SPLIT_FUNCTIONS);
        }
      }
      if (branchFdoProvider.isLlvmCSFdo()) {
        allFeatures.add(CppRuleClasses.CS_FDO_OPTIMIZE);
      }
      if (branchFdoProvider.isAutoFdo()) {
        allFeatures.add(CppRuleClasses.AUTOFDO);
        // Support implicit enabling of ThinLTO for AFDO unless it has been disabled.
        if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_AFDO_THINLTO);
        }
        // Support implicit enabling of FSAFDO for AFDO unless it has been disabled.
        if (!allUnsupportedFeatures.contains(CppRuleClasses.FSAFDO)) {
          allFeatures.add(CppRuleClasses.ENABLE_FSAFDO);
        }
      }
      if (branchFdoProvider.isAutoXBinaryFdo()) {
        allFeatures.add(CppRuleClasses.XBINARYFDO);
        // Support implicit enabling of ThinLTO for XFDO unless it has been explicitly disabled.
        if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_XFDO_THINLTO);
        }
      }
    }
    if (cppConfiguration.getFdoPrefetchHintsLabel() != null) {
      allRequestedFeaturesBuilder.add(CppRuleClasses.FDO_PREFETCH_HINTS);
    }

    if (enablePropellerOptimize) {
      allRequestedFeaturesBuilder.add(CppRuleClasses.PROPELLER_OPTIMIZE);
    }

    for (String feature : allFeatures.build()) {
      if (!allUnsupportedFeatures.contains(feature)) {
        allRequestedFeaturesBuilder.add(feature);
      }
    }

    try {
      FeatureConfiguration featureConfiguration =
          toolchain.getFeatures().getFeatureConfiguration(allRequestedFeaturesBuilder.build());
      for (String feature : unsupportedFeatures) {
        if (featureConfiguration.isEnabled(feature)) {
          throw Starlark.errorf(
              "The C++ toolchain '%s' unconditionally implies feature '%s', which is unsupported"
                  + " by this rule. This is most likely a misconfiguration in the C++ toolchain.",
              toolchain.getCcToolchainLabel(), feature);
        }
      }
      if (cppConfiguration.forcePic()
          && !featureConfiguration.isEnabled(CppRuleClasses.PIC)
          && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)) {
        throw new EvalException(PIC_CONFIGURATION_ERROR);
      }
      return featureConfiguration;
    } catch (CollidingProvidesException ex) {
      throw new EvalException(ex);
    }
  }

  /**
   * Computes the appropriate value of the {@code $(CC_FLAGS)} Make variable based on the given
   * toolchain.
   */
  public static String computeCcFlags(RuleContext ruleContext, TransitiveInfoCollection toolchain)
      throws RuleErrorException {
    CcToolchainProvider toolchainProvider = toolchain.get(CcToolchainProvider.PROVIDER);

    // Determine the original value of CC_FLAGS.
    String originalCcFlags = toolchainProvider.getLegacyCcFlagsMakeVariable();

    // Ensure that Sysroot is set properly.
    // TODO(b/129045294): We assume --incompatible_disable_genrule_cc_toolchain_dependency will
    //   be flipped sooner than --incompatible_enable_cc_toolchain_resolution. Then this method
    //   will be gone.
    String sysrootCcFlags =
        computeCcFlagForSysroot(
            toolchainProvider.getCppConfigurationEvenThoughItCanBeDifferentThanWhatTargetHas(),
            toolchainProvider);

    // Fetch additional flags from the FeatureConfiguration.
    List<String> featureConfigCcFlags =
        computeCcFlagsFromFeatureConfig(ruleContext, toolchainProvider);

    // Combine the different flag sources.
    ImmutableList.Builder<String> ccFlags = new ImmutableList.Builder<>();
    ccFlags.add(originalCcFlags);

    // Only add the sysroot flag if nothing else adds sysroot, _but_ it must appear before
    // the feature config flags.
    if (!containsSysroot(originalCcFlags, featureConfigCcFlags)) {
      ccFlags.add(sysrootCcFlags);
    }

    ccFlags.addAll(featureConfigCcFlags);
    return Joiner.on(" ").join(ccFlags.build());
  }

  private static boolean containsSysroot(String ccFlags, List<String> moreCcFlags) {
    return Stream.concat(Stream.of(ccFlags), moreCcFlags.stream())
        .anyMatch(str -> str.contains(SYSROOT_FLAG));
  }

  private static String computeCcFlagForSysroot(
      CppConfiguration cppConfiguration, CcToolchainProvider toolchainProvider) {
    PathFragment sysroot = toolchainProvider.getSysrootPathFragment(cppConfiguration);
    String sysrootFlag = "";
    if (sysroot != null) {
      sysrootFlag = SYSROOT_FLAG + sysroot;
    }

    return sysrootFlag;
  }

  private static List<String> computeCcFlagsFromFeatureConfig(
      RuleContext ruleContext, CcToolchainProvider toolchainProvider) throws RuleErrorException {
    FeatureConfiguration featureConfiguration = null;
    CppConfiguration cppConfiguration;
    if (toolchainProvider.requireCtxInConfigureFeatures()) {
      // When --incompatible_require_ctx_in_configure_features is flipped, this whole method will go
      // away. But I'm keeping it there so we can experiment with flags before they are flipped.
      cppConfiguration = ruleContext.getFragment(CppConfiguration.class);
    } else {
      cppConfiguration =
          toolchainProvider.getCppConfigurationEvenThoughItCanBeDifferentThanWhatTargetHas();
    }
    try {
      featureConfiguration =
          configureFeaturesOrThrowEvalException(
              ruleContext.getFeatures(),
              ruleContext.getDisabledFeatures(),
              Language.CPP,
              toolchainProvider,
              cppConfiguration);
    } catch (EvalException e) {
      ruleContext.ruleError(e.getMessage());
    }
    if (featureConfiguration.actionIsConfigured(CppActionNames.CC_FLAGS_MAKE_VARIABLE)) {
      CcToolchainVariables buildVariables =
          toolchainProvider.getBuildVariables(
              ruleContext.getConfiguration().getOptions(), cppConfiguration);
      return CppHelper.getCommandLine(
          ruleContext, featureConfiguration, buildVariables, CppActionNames.CC_FLAGS_MAKE_VARIABLE);
    }
    return ImmutableList.of();
  }

  public static boolean isOldStarlarkApiWhiteListed(
      StarlarkRuleContext starlarkRuleContext, List<String> whitelistedPackages) {
    RuleContext context = starlarkRuleContext.getRuleContext();
    Rule rule = context.getRule();

    RuleClass ruleClass = rule.getRuleClassObject();
    Label label = ruleClass.getRuleDefinitionEnvironmentLabel();
    if (label.getRepository().getNameWithAt().equals("@_builtins")) {
      // always permit builtins
      return true;
    }
    if (label != null) {
      return whitelistedPackages.stream()
          .anyMatch(path -> label.getPackageFragment().toString().startsWith(path));
    }
    return false;
  }
}
