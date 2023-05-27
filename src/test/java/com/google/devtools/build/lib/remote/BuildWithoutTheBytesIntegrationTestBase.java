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
package com.google.devtools.build.lib.remote;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Base class for integration tests for BwoB. */
public abstract class BuildWithoutTheBytesIntegrationTestBase extends BuildIntegrationTestCase {
  // Concrete implementations should by default set the necessary flags to download minimal outputs.
  // These methods should override the necessary flags to download top-level outputs or all outputs.
  protected abstract void setDownloadToplevel();

  protected abstract void setDownloadAll();

  protected abstract void assertOutputEquals(Path path, String expectedContent) throws Exception;

  protected abstract void assertOutputContains(String content, String contains) throws Exception;

  protected abstract void evictAllBlobs() throws Exception;

  protected abstract boolean hasAccessToRemoteOutputs();

  protected void waitDownloads() throws Exception {
    // Trigger afterCommand of modules so that downloads are waited.
    runtimeWrapper.newCommand();
  }

  @Test
  public void outputsAreNotDownloaded() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputsDoNotExist("//:foo");
    assertOutputsDoNotExist("//:foobar");
  }

  @Test
  public void disableRunfiles_buildSuccessfully() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  cmd = 'echo foo > $@',",
        "  outs = ['foo.data'],",
        ")",
        "sh_test(",
        "  name = 'foobar',",
        "  srcs = ['test.sh'],",
        "  data = [':foo'],",
        ")");
    write("test.sh");
    getWorkspace().getRelative("test.sh").setExecutable(true);
    addOptions("--build_runfile_links", "--enable_runfiles=no");

    buildTarget("//:foobar");
  }

  @Test
  public void downloadOutputsWithRegex() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo\\.txt$");

    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("out/foo.txt", "foo\n");
    assertOutputsDoNotExist("//:foobar");

    // Assert that no actions have been executed for the next incremental build since nothing
    // changed
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    // Override out/foo.txt with the same content
    {
      var path = getOutputPath("out/foo.txt");
      var isWritable = path.isWritable();
      if (!isWritable) {
        path.setWritable(true);
      }
      writeContent(path, UTF_8, "foo\n");
      if (!isWritable) {
        path.setWritable(false);
      }
    }
    buildTarget("//:foobar");
    assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty();
  }

  @Test
  public void downloadOutputsWithRegex_deleteOutput_reDownload() throws Exception {
    // Arrange: Do a clean build and download out/foo.txt
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo\\.txt$");

    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("out/foo.txt", "foo\n");
    assertOutputsDoNotExist("//:foobar");

    // Arrange: Delete out/foo.txt and do an incremental build
    getOutputPath("out/foo.txt").delete();
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");
    waitDownloads();

    // Assert: out/foo.txt is re-downloaded
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1);
    assertValidOutputFile("out/foo.txt", "foo\n");
  }

  @Test
  public void downloadOutputsWithRegex_changeRegex_downloadNewMatches() throws Exception {
    // Arrange: Do a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");

    buildTarget("//:foobar");
    // Add the new option here because waitDownloads below will internally create a new command
    // which will parse the new option.
    addOptions("--experimental_remote_download_regex=.*foobar\\.txt$");
    waitDownloads();

    assertOutputsDoNotExist("//:foo");
    assertOutputsDoNotExist("//:foobar");

    // Arrange: Change regex
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");
    waitDownloads();

    // Assert: out/foobar.txt is downloaded
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1);
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
  }

  @Test
  public void downloadOutputsWithRegex_treeOutput_regexMatchesTreeFile() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo/file-2$");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo/file-2", "2");
    assertOutputDoesNotExist("foo/file-1");
    assertOutputDoesNotExist("foo/file-3");
  }

  @Test
  public void downloadOutputsWithRegex_treeOutput_regexMatchesTreeRoot() throws Exception {
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo$");

    buildTarget("//:foo");
    waitDownloads();

    assertThat(getOutputPath("foo").exists()).isTrue();
    assertOutputDoesNotExist("foo/file-1");
    assertOutputDoesNotExist("foo/file-2");
    assertOutputDoesNotExist("foo/file-3");
  }

  @Test
  public void downloadOutputsWithRegex_regexMatchParentPath_filesNotDownloaded() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'file-1',",
        "  srcs = [],",
        "  outs = ['foo/file-1'],",
        "  cmd = 'echo file-1 > $@',",
        ")",
        "genrule(",
        "  name = 'file-2',",
        "  srcs = [],",
        "  outs = ['foo/file-2'],",
        "  cmd = 'echo file-2 > $@',",
        ")",
        "genrule(",
        "  name = 'file-3',",
        "  srcs = [],",
        "  outs = ['foo/file-3'],",
        "  cmd = 'echo file-3 > $@',",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo$");

    buildTarget("//:file-1", "//:file-2", "//:file-3");
    waitDownloads();

    assertOutputDoesNotExist("foo/file-1");
    assertOutputDoesNotExist("foo/file-2");
    assertOutputDoesNotExist("foo/file-3");
  }

  @Test
  public void intermediateOutputsAreInputForLocalActions_prefetchIntermediateOutputs()
      throws Exception {
    // Test that a remote-only output that's an input to a local action is downloaded lazily before
    // executing the local action.
    write(
        "a/BUILD",
        "genrule(",
        "  name = 'remote',",
        "  srcs = [],",
        "  outs = ['remote.txt'],",
        "  cmd = 'echo -n remote > $@',",
        ")",
        "",
        "genrule(",
        "  name = 'local',",
        "  srcs = [':remote'],",
        "  outs = ['local.txt'],",
        "  cmd = 'cat $(location :remote) > $@ && echo -n local >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//a:remote");
    waitDownloads();
    assertOutputsDoNotExist("//a:remote");
    buildTarget("//a:local");
    waitDownloads();

    assertOnlyOutputContent("//a:remote", "remote.txt", "remote");
    assertOnlyOutputContent("//a:local", "local.txt", "remotelocal");
  }

  @Test
  public void symlinkToSourceFile() throws Exception {
    write(
        "a/defs.bzl",
        "def _impl(ctx):",
        "  if ctx.attr.chain_length < 1:",
        "    fail('chain_length must be > 0')",
        "",
        "  file = ctx.file.target",
        "",
        "  for i in range(ctx.attr.chain_length):",
        "    sym = ctx.actions.declare_file(ctx.label.name + '.sym' + str(i))",
        "    ctx.actions.symlink(output = sym, target_file = file)",
        "    file = sym",
        "",
        "  out = ctx.actions.declare_file(ctx.label.name + '.out')",
        "  ctx.actions.run_shell(",
        "    inputs = [sym],",
        "    outputs = [out],",
        "    command = '[[ hello == $(cat $1) ]] && touch $2',",
        "    arguments = [sym.path, out.path],",
        "    execution_requirements = {'no-remote': ''} if ctx.attr.local else {},",
        "  )",
        "",
        "  return DefaultInfo(files = depset([out]))",
        "",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'target': attr.label(allow_single_file = True),",
        "    'chain_length': attr.int(),",
        "    'local': attr.bool(),",
        "  },",
        ")");

    write(
        "a/BUILD",
        "load(':defs.bzl', 'my_rule')",
        "",
        "my_rule(name = 'one_local', target = 'src.txt', local = True, chain_length = 1)",
        "my_rule(name = 'two_local', target = 'src.txt', local = True, chain_length = 2)",
        "my_rule(name = 'one_remote', target = 'src.txt', local = False, chain_length = 1)",
        "my_rule(name = 'two_remote', target = 'src.txt', local = False, chain_length = 2)");

    write("a/src.txt", "hello");

    buildTarget("//a:one_local", "//a:two_local", "//a:one_remote", "//a:two_remote");
  }

  @Test
  public void localAction_stdoutIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-output-message > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) && touch $@',",
        "  tags = ['no-remote'],",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputContains(outErr.outAsLatin1(), "my-output-message");
  }

  @Test
  public void localAction_stderrIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-error-message > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) >&2 && exit 1',",
        "  tags = ['no-remote'],",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    assertThrows(BuildFailedException.class, () -> buildTarget("//:foobar"));

    assertOutputContains(outErr.errAsLatin1(), "my-error-message");
  }

  @Test
  public void dynamicExecution_stdoutIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    addOptions("--internal_spawn_scheduler");
    addOptions("--strategy=Genrule=dynamic");
    addOptions("--experimental_local_execution_delay=9999999");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-output-message > $@',",
        "  tags = ['no-local'],",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) && touch $@',",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputContains(outErr.outAsLatin1(), "my-output-message");
  }

  @Test
  public void dynamicExecution_stderrIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    addOptions("--internal_spawn_scheduler");
    addOptions("--strategy=Genrule=dynamic");
    addOptions("--experimental_local_execution_delay=9999999");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-error-message > $@',",
        "  tags = ['no-local'],",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) >&2 && exit 1',",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    assertThrows(BuildFailedException.class, () -> buildTarget("//:foobar"));

    assertOutputContains(outErr.errAsLatin1(), "my-error-message");
  }

  @Test
  public void downloadToplevel_outputsFromAspect_notAggregated() throws Exception {
    setDownloadToplevel();
    writeCopyAspectRule(/* aggregate= */ false);
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['foobar.out'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    write("foo.in", "foo");

    addOptions("--aspects=rules.bzl%copy_aspect", "--output_groups=+copy");
    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("foobar.out", "foo" + lineSeparator() + "bar\n");
    assertOutputDoesNotExist("foo.in.copy");
    assertValidOutputFile("foo.out.copy", "foo" + lineSeparator());
  }

  @Test
  public void downloadToplevel_outputsFromAspect_aggregated() throws Exception {
    setDownloadToplevel();
    writeCopyAspectRule(/* aggregate= */ true);
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['foobar.out'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    write("foo.in", "foo");

    addOptions("--aspects=rules.bzl%copy_aspect", "--output_groups=+copy");
    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("foobar.out", "foo" + lineSeparator() + "bar\n");
    assertValidOutputFile("foo.in.copy", "foo" + lineSeparator());
    assertValidOutputFile("foo.out.copy", "foo" + lineSeparator());
  }

  @Test
  public void downloadToplevel_outputsFromAspect_notDownloadedIfNoOutputGroups() throws Exception {
    setDownloadToplevel();
    writeCopyAspectRule(/* aggregate= */ true);
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['foobar.out'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    write("foo.in", "foo");

    addOptions("--aspects=rules.bzl%copy_aspect");
    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("foobar.out", "foo" + lineSeparator() + "bar\n");
    assertOutputDoesNotExist("foo.in.copy");
    assertOutputDoesNotExist("foo.out.copy");
  }

  @Test
  public void downloadToplevel_outputsFromImportantOutputGroupAreDownloaded() throws Exception {
    setDownloadToplevel();
    write(
        "rules.bzl",
        "def _gen_impl(ctx):",
        "  output = ctx.actions.declare_file(ctx.attr.name)",
        "  ctx.actions.run_shell(",
        "    outputs = [output],",
        "    arguments = [ctx.attr.content, output.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  extra1 = ctx.actions.declare_file(ctx.attr.name + '1')",
        "  ctx.actions.run_shell(",
        "    outputs = [extra1],",
        "    arguments = [ctx.attr.content, extra1.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  extra2 = ctx.actions.declare_file(ctx.attr.name + '2')",
        "  ctx.actions.run_shell(",
        "    outputs = [extra2],",
        "    arguments = [ctx.attr.content, extra2.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  return [",
        "    DefaultInfo(files = depset([output])),",
        "    OutputGroupInfo(",
        "      extra1_files = depset([extra1]),",
        "      extra2_files = depset([extra2]),",
        "    ),",
        "  ]",
        "",
        "gen = rule(",
        "  implementation = _gen_impl,",
        "  attrs = {",
        "    'content': attr.string(mandatory = True),",
        "  }",
        ")");
    write(
        "BUILD",
        "load(':rules.bzl', 'gen')",
        "gen(",
        "  name = 'foo',",
        "  content = 'foo-content',",
        ")");
    addOptions("--output_groups=+extra1_files");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo", "foo-content\n");
    assertValidOutputFile("foo1", "foo-content\n");
    assertOutputDoesNotExist("foo2");
  }

  @Test
  public void downloadToplevel_outputsFromHiddenOutputGroupAreNotDownloaded() throws Exception {
    setDownloadToplevel();
    write(
        "rules.bzl",
        "def _gen_impl(ctx):",
        "  output = ctx.actions.declare_file(ctx.attr.name)",
        "  ctx.actions.run_shell(",
        "    outputs = [output],",
        "    arguments = [ctx.attr.content, output.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  validation_file = ctx.actions.declare_file(ctx.attr.name + '.validation')",
        "  ctx.actions.run_shell(",
        "    outputs = [validation_file],",
        "    arguments = [ctx.attr.content, validation_file.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  return [",
        "    DefaultInfo(files = depset([output])),",
        "    OutputGroupInfo(",
        "      _validation = depset([validation_file]),",
        "    ),",
        "  ]",
        "",
        "gen = rule(",
        "  implementation = _gen_impl,",
        "  attrs = {",
        "    'content': attr.string(mandatory = True),",
        "  }",
        ")");
    write(
        "BUILD",
        "load(':rules.bzl', 'gen')",
        "gen(",
        "  name = 'foo',",
        "  content = 'foo-content',",
        ")");
    addOptions("--output_groups=+_validation");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo", "foo-content\n");
    assertOutputDoesNotExist("foo.validation");
  }

  @Test
  public void downloadToplevel_treeArtifacts() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    setDownloadToplevel();
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
        ")");

    buildTarget("//:foo");

    assertValidOutputFile("foo/file-1", "1");
    assertValidOutputFile("foo/file-2", "2");
    assertValidOutputFile("foo/file-3", "3");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
  }

  @Test
  public void downloadToplevel_multipleToplevelTargets() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo1',",
        "  srcs = [],",
        "  outs = ['out/foo1.txt'],",
        "  cmd = 'echo foo1 > $@',",
        ")",
        "genrule(",
        "  name = 'foo2',",
        "  srcs = [],",
        "  outs = ['out/foo2.txt'],",
        "  cmd = 'echo foo2 > $@',",
        ")",
        "genrule(",
        "  name = 'foo3',",
        "  srcs = [],",
        "  outs = ['out/foo3.txt'],",
        "  cmd = 'echo foo3 > $@',",
        ")");
    setDownloadToplevel();

    buildTarget("//:foo1", "//:foo2", "//:foo3");

    assertValidOutputFile("out/foo1.txt", "foo1\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo1").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
    assertValidOutputFile("out/foo2.txt", "foo2\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo2").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
    assertValidOutputFile("out/foo3.txt", "foo3\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo3").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
  }

  @Test
  public void downloadToplevel_incrementalBuild_multipleToplevelTargets() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo1',",
        "  srcs = [],",
        "  outs = ['out/foo1.txt'],",
        "  cmd = 'echo foo1 > $@',",
        ")",
        "genrule(",
        "  name = 'foo2',",
        "  srcs = [],",
        "  outs = ['out/foo2.txt'],",
        "  cmd = 'echo foo2 > $@',",
        ")",
        "genrule(",
        "  name = 'foo3',",
        "  srcs = [],",
        "  outs = ['out/foo3.txt'],",
        "  cmd = 'echo foo3 > $@',",
        ")");

    buildTarget("//:foo1", "//:foo2", "//:foo3");

    assertOutputsDoNotExist("//:foo1");
    assertThat(getMetadata("//:foo1").values().stream().allMatch(FileArtifactValue::isRemote))
        .isTrue();
    assertOutputsDoNotExist("//:foo2");
    assertThat(getMetadata("//:foo2").values().stream().allMatch(FileArtifactValue::isRemote))
        .isTrue();
    assertOutputsDoNotExist("//:foo3");
    assertThat(getMetadata("//:foo3").values().stream().allMatch(FileArtifactValue::isRemote))
        .isTrue();

    setDownloadToplevel();
    buildTarget("//:foo1", "//:foo2", "//:foo3");

    assertValidOutputFile("out/foo1.txt", "foo1\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo1").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
    assertValidOutputFile("out/foo2.txt", "foo2\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo2").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
    assertValidOutputFile("out/foo3.txt", "foo3\n");
    // TODO(chiwang): Make metadata for downloaded outputs local.
    // assertThat(getMetadata("//:foo3").values().stream().noneMatch(FileArtifactValue::isRemote))
    //     .isTrue();
  }

  @Test
  public void treeOutputsFromLocalFileSystem_works() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    // Test that tree artifact generated locally can be consumed by other actions.
    // See https://github.com/bazelbuild/bazel/issues/16789

    // Disable remote execution so tree outputs are generated locally
    addOptions("--modify_execution_info=OutputDir=+no-remote-exec");
    setDownloadToplevel();
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1'},",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo)/file-1 > $@ && echo bar >> $@',",
        ")");

    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("out/foobar.txt", "1bar\n");
  }

  @Test
  public void emptyTreeConsumedByLocalAction() throws Exception {
    // Disable remote execution so that the empty tree artifact is prefetched.
    addOptions("--modify_execution_info=Genrule=+no-remote-exec");
    addOptions("--verbose_failures");
    setDownloadToplevel();
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {},", // no files
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['foobar.txt'],",
        "  cmd = 'touch $@',",
        ")");

    buildTarget("//:foobar");
    waitDownloads();
  }

  @Test
  public void multiplePackagePaths_buildsSuccessfully() throws Exception {
    write(
        "../a/src/BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = ['//src:foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location //src:foo) > $@ && echo bar >> $@',",
        ")");
    addOptions("--package_path=%workspace%:%workspace%/../a");
    setDownloadToplevel();

    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
  }

  @Test
  public void incrementalBuild_deleteOutputsInUnwritableParentDirectory() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'unwritable',",
        "  srcs = ['file.in'],",
        "  outs = ['unwritable/somefile.out'],",
        "  cmd = 'cat $(SRCS) > $@; chmod a-w $$(dirname $@)',",
        "  local = True,",
        ")");
    write("file.in", "content");
    buildTarget("//:unwritable");

    write("file.in", "updated content");

    buildTarget("//:unwritable");
  }

  @Test
  public void incrementalBuild_treeArtifacts_correctlyProducesNewTree() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
        ")");
    setDownloadToplevel();
    buildTarget("//:foo");
    waitDownloads();

    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  content_map = {'file-1': '1', 'file-4': '4'},",
        ")");
    restartServer();
    setDownloadToplevel();
    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo/file-1", "1");
    assertValidOutputFile("foo/file-4", "4");
    assertOutputDoesNotExist("foo/file-2");
    assertOutputDoesNotExist("foo/file-3");
  }

  @Test
  public void incrementalBuild_restartServer_hitActionCache() throws Exception {
    // Prepare workspace
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);

    // Clean build
    buildTarget("//:foobar");

    // all action should be executed
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(3);
    // no outputs are staged
    assertOutputsDoNotExist("//:foobar");

    restartServer();
    actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);

    // Incremental build
    buildTarget("//:foobar");

    // all actions should hit the action cache.
    assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty();
    // no outputs are staged
    assertOutputsDoNotExist("//:foobar");
  }

  @Test
  public void incrementalBuild_sourceModified_rerunActions() throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write("foo.in", "foo");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo" + lineSeparator());
    assertValidOutputFile("out/foobar.txt", "foo" + lineSeparator() + "bar\n");

    // Act: Modify source file and run an incremental build
    write("foo.in", "modified");

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: All actions transitively depend on the source file are re-executed and outputs are
    // correct.
    assertValidOutputFile("out/foo.txt", "modified" + lineSeparator());
    assertValidOutputFile("out/foobar.txt", "modified" + lineSeparator() + "bar\n");
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(2);
  }

  @Test
  public void incrementalBuild_intermediateOutputDeleted_nothingIsReEvaluated() throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");

    // Act: Delete intermediate output and run an incremental build
    var fooPath = getOutputPath("out/foo.txt");
    fooPath.delete();

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: local output is deleted, skyframe should trust remote files so no nodes will be
    // re-evaluated.
    assertOutputDoesNotExist("out/foo.txt");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(0);
  }

  @Test
  public void incrementalBuild_remoteFileMetadataIsReplacedWithLocalFileMetadata()
      throws Exception {
    // We need to download the intermediate output
    if (!hasAccessToRemoteOutputs()) {
      return;
    }

    // Arrange: Prepare workspace and run a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(getOnlyElement(getMetadata("//:foo").values()).isRemote()).isTrue();

    // Act: Do an incremental build without any modifications
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: remote file metadata is replaced with local file metadata
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty();
    // Two actions are invalidated but were able to hit the action cache
    assertThat(actionEventCollector.getCachedActionEvents()).hasSize(2);
    assertThat(getOnlyElement(getMetadata("//:foo").values()).isRemote()).isFalse();
  }

  protected ImmutableMap<Artifact, FileArtifactValue> getMetadata(String target) throws Exception {
    var result = ImmutableMap.<Artifact, FileArtifactValue>builder();
    var evaluator = getRuntimeWrapper().getSkyframeExecutor().getEvaluator();
    for (var artifact : getArtifacts(target)) {
      var value = evaluator.getExistingValue(Artifact.key(artifact));
      if (value instanceof ActionExecutionValue) {
        result.putAll(((ActionExecutionValue) value).getAllFileValues());
      } else if (value instanceof TreeArtifactValue) {
        result.putAll(((TreeArtifactValue) value).getChildValues());
      }
    }
    return result.buildOrThrow();
  }

  protected FileArtifactValue getMetadata(Artifact output) throws Exception {
    var evaluator = getRuntimeWrapper().getSkyframeExecutor().getEvaluator();
    var value = evaluator.getExistingValue(Artifact.key(output));
    if (value instanceof ActionExecutionValue) {
      return ((ActionExecutionValue) value).getAllFileValues().get(output);
    } else if (value instanceof TreeArtifactValue) {
      return ((TreeArtifactValue) value).getChildValues().get(output);
    }
    return null;
  }

  @Test
  public void incrementalBuild_intermediateOutputModified_rerunGeneratingActions()
      throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");

    // Act: Modify the intermediate output and run a incremental build
    var fooPath = getOutputPath("out/foo.txt");
    fooPath.delete();
    writeAbsolute(fooPath, "modified");

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: the stale intermediate file should be deleted by skyframe before executing the
    // generating action. Since download minimal, the output didn't get downloaded. Since the input
    // to action :foobar didn't change, we hit the skyframe cache, so the action node didn't event
    // get evaluated. The input didn't get prefetched neither.
    assertOutputDoesNotExist("out/foo.txt");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1);
    assertThat(actionEventCollector.getCachedActionEvents()).isEmpty();
    var executedAction = actionEventCollector.getActionExecutedEvents().get(0).getAction();
    assertThat(executedAction.getPrimaryOutput().getFilename()).isEqualTo("foo.txt");
  }

  @Test
  public void remoteCacheEvictBlobs_whenPrefetchingInputFile_incrementalBuildCanContinue()
      throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write(
        "a/BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'bar',",
        "  srcs = ['foo.out', 'bar.in'],",
        "  outs = ['bar.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")");
    write("a/foo.in", "foo");
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    getOutputPath("a/foo.out").delete();
    getOutputPath("a/bar.out").delete();
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out");

    // Evict blobs from remote cache
    evictAllBlobs();

    // trigger build error
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    // Build failed because of remote cache eviction
    assertThrows(BuildFailedException.class, () -> buildTarget("//a:bar"));

    // Act: Do an incremental build without "clean" or "shutdown"
    buildTarget("//a:bar");

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "foo" + lineSeparator() + "updated bar" + lineSeparator());
  }

  @Test
  public void remoteCacheEvictBlobs_whenPrefetchingInputTree_incrementalBuildCanContinue()
      throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write("BUILD");
    writeOutputDirRule();
    write(
        "a/BUILD",
        "load('//:output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo.out',",
        "  content_map = {'file-inside': 'hello world'},",
        ")",
        "genrule(",
        "  name = 'bar',",
        "  srcs = ['foo.out', 'bar.in'],",
        "  outs = ['bar.out'],",
        "  cmd = '( ls $(location :foo.out); cat $(location :bar.in) ) > $@',",
        ")");
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    getOutputPath("a/foo.out").deleteTreesBelow();
    getOutputPath("a/bar.out").delete();
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out/file-inside");

    // Evict blobs from remote cache
    evictAllBlobs();

    // trigger build error
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    // Build failed because of remote cache eviction
    assertThrows(BuildFailedException.class, () -> buildTarget("//a:bar"));

    // Act: Do an incremental build without "clean" or "shutdown"
    buildTarget("//a:bar");

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "file-inside\nupdated bar" + lineSeparator());
  }

  @Test
  public void remoteFilesExpiredBetweenBuilds_rerunGeneratingActions() throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write(
        "a/BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'bar',",
        "  srcs = ['foo.out', 'bar.in'],",
        "  outs = ['bar.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")");
    write("a/foo.in", "foo");
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    getOutputPath("a/foo.out").delete();
    getOutputPath("a/bar.out").delete();
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    setDownloadToplevel();
    addOptions("--experimental_remote_cache_ttl=0s");
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out");

    // Evict blobs from remote cache
    evictAllBlobs();

    // Act: Do an incremental build
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    buildTarget("//a:bar");
    waitDownloads();

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "foo" + lineSeparator() + "updated bar" + lineSeparator());
  }

  @Test
  public void remoteTreeFilesExpiredBetweenBuilds_rerunGeneratingActions() throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write("BUILD");
    writeOutputDirRule();
    write(
        "a/BUILD",
        "load('//:output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo.out',",
        "  content_map = {'file-inside': 'hello world'},",
        ")",
        "genrule(",
        "  name = 'bar',",
        "  srcs = ['foo.out', 'bar.in'],",
        "  outs = ['bar.out'],",
        "  cmd = '( ls $(location :foo.out); cat $(location :bar.in) ) > $@',",
        ")");
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    getOutputPath("a/foo.out").deleteTreesBelow();
    getOutputPath("a/bar.out").delete();
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    setDownloadToplevel();
    addOptions("--experimental_remote_cache_ttl=0s");
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out/file-inside");

    // Evict blobs from remote cache
    evictAllBlobs();

    // Act: Do an incremental build
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    buildTarget("//a:bar");
    waitDownloads();

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "file-inside\nupdated bar" + lineSeparator());
  }

  protected void assertOutputsDoNotExist(String target) throws Exception {
    for (Artifact output : getArtifacts(target)) {
      assertWithMessage(
              "output %s for target %s should not exist", output.getExecPathString(), target)
          .that(output.getPath().exists())
          .isFalse();
    }
  }

  protected Path getOutputPath(String binRelativePath) {
    return getDirectories()
        .getWorkspace()
        .getRelative(getDirectories().getProductName() + "-bin")
        .getRelative(binRelativePath);
  }

  protected void assertOutputDoesNotExist(String binRelativePath) {
    Path output = getOutputPath(binRelativePath);
    assertThat(output.exists()).isFalse();
  }

  protected void assertOnlyOutputContent(String target, String filename, String content)
      throws Exception {
    Artifact output = getOnlyElement(getArtifacts(target));
    assertThat(output.getFilename()).isEqualTo(filename);
    assertThat(output.getPath().exists()).isTrue();
    assertOutputEquals(output.getPath(), content);
  }

  protected void assertValidOutputFile(String binRelativePath, String content) throws Exception {
    Path output = getOutputPath(binRelativePath);
    assertOutputEquals(getOutputPath(binRelativePath), content);
    assertThat(output.isReadable()).isTrue();
    assertThat(output.isWritable()).isFalse();
    assertThat(output.isExecutable()).isTrue();
  }

  protected void writeSymlinkRule() throws IOException {
    write(
        "symlink.bzl",
        "def _symlink_impl(ctx):",
        "  target = ctx.file.target",
        "  if target.is_directory:",
        "    link = ctx.actions.declare_directory(ctx.attr.name)",
        "  else:",
        "    link = ctx.actions.declare_file(ctx.attr.name)",
        "  ctx.actions.symlink(output = link, target_file = target)",
        "  return DefaultInfo(files = depset([link]))",
        "",
        "symlink = rule(",
        "  implementation = _symlink_impl,",
        "  attrs = {",
        "    'target': attr.label(mandatory = True, allow_single_file = True),",
        "  }",
        ")");
  }

  protected void writeOutputDirRule() throws IOException {
    write(
        "output_dir.bzl",
        "def _output_dir_impl(ctx):",
        "  out = ctx.actions.declare_directory(ctx.attr.name)",
        "  args = []",
        "  for name, content in ctx.attr.content_map.items():",
        "    args.append(out.path + '/' + name)",
        "    args.append(content)",
        "  ctx.actions.run_shell(",
        "    mnemonic = 'OutputDir',",
        "    outputs = [out],",
        "    arguments = args,",
        "    command = 'while (($#)); do echo -n \"$2\" > $1; shift 2; done',",
        "  )",
        "  return DefaultInfo(files = depset([out]))",
        "",
        "output_dir = rule(",
        "  implementation = _output_dir_impl,",
        "  attrs = {",
        "    'content_map': attr.string_dict(mandatory = True),",
        "  },",
        ")");
  }

  protected void writeCopyAspectRule(boolean aggregate) throws IOException {
    var lines = ImmutableList.<String>builder();
    lines.add(
        "def _copy_aspect_impl(target, ctx):",
        "  files = []",
        "  for src in ctx.rule.files.srcs:",
        "    dst = ctx.actions.declare_file(src.basename + '.copy')",
        "    ctx.actions.run_shell(",
        "      inputs = [src],",
        "      outputs = [dst],",
        "      command = '''",
        "cp $1 $2",
        "''',",
        "      arguments = [src.path, dst.path],",
        "    )",
        "    files.append(dst)",
        "");
    if (aggregate) {
      lines.add(
          "  files = depset(",
          "    direct = files,",
          "    transitive = [src[OutputGroupInfo].copy for src in ctx.rule.attr.srcs if"
              + " OutputGroupInfo in src],",
          "  )");
    } else {
      lines.add("  files = depset(files)");
    }
    lines.add(
        "",
        "  return [OutputGroupInfo(copy = files)]",
        "",
        "copy_aspect = aspect(",
        "  implementation = _copy_aspect_impl,",
        "  attr_aspects = ['srcs'],",
        ")");
    write("rules.bzl", lines.build().toArray(new String[0]));
  }

  protected static class ActionEventCollector {
    private final List<ActionExecutedEvent> actionExecutedEvents = new ArrayList<>();
    private final List<CachedActionEvent> cachedActionEvents = new ArrayList<>();

    @Subscribe
    public void onActionExecuted(ActionExecutedEvent event) {
      actionExecutedEvents.add(event);
    }

    @Subscribe
    public void onCachedAction(CachedActionEvent event) {
      cachedActionEvents.add(event);
    }

    public int getNumActionNodesEvaluated() {
      return getActionExecutedEvents().size() + getCachedActionEvents().size();
    }

    public void clear() {
      this.actionExecutedEvents.clear();
      this.cachedActionEvents.clear();
    }

    public List<ActionExecutedEvent> getActionExecutedEvents() {
      return actionExecutedEvents;
    }

    public List<CachedActionEvent> getCachedActionEvents() {
      return cachedActionEvents;
    }
  }

  protected void restartServer() throws Exception {
    // Simulates a server restart
    createRuntimeWrapper();
  }

  protected static String lineSeparator() {
    return System.getProperty("line.separator");
  }
}
