# Copyright 2021 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Definition of proto_common module, together with bazel providers for proto rules."""

ProtoLangToolchainInfo = provider(
    doc = "Specifies how to generate language-specific code from .proto files. Used by LANG_proto_library rules.",
    fields = dict(
        out_replacement_format_flag = "(str) Format string used when passing output to the plugin used by proto compiler.",
        plugin_format_flag = "(str) Format string used when passing plugin to proto compiler.",
        plugin = "(FilesToRunProvider) Proto compiler plugin.",
        runtime = "(Target) Runtime.",
        provided_proto_sources = "(list[ProtoSource]) Proto sources provided by the toolchain.",
        proto_compiler = "(FilesToRunProvider) Proto compiler.",
        protoc_opts = "(list[str]) Options to pass to proto compiler.",
        progress_message = "(str) Progress message to set on the proto compiler action.",
        mnemonic = "(str) Mnemonic to set on the proto compiler action.",
    ),
)

def _proto_path_flag(path):
    if path == ".":
        return None
    return "--proto_path=%s" % path

def _Iimport_path_equals_fullpath(proto_source):
    return "-I%s=%s" % (get_import_path(proto_source), proto_source._source_file.path)

def _remove_repo(file):
    """Removes `../repo/` prefix from path, e.g. `../repo/package/path -> package/path`"""
    short_path = file.short_path
    workspace_root = file.owner.workspace_root
    if workspace_root:
        if workspace_root.startswith("external/"):
            workspace_root = "../" + workspace_root.removeprefix("external/")
        return short_path.removeprefix(workspace_root + "/")
    return short_path

def get_import_path(proto_source):
    """Returns the import path of a .proto file, i.e. clean path without any repo or strip_prefixes remapping

    Args:
      proto_source: (ProtoSourceInfo) The .proto file
    Returns:
      (str) import path
    """
    proto_path = proto_source._proto_path
    short_path = _remove_repo(proto_source._source_file)
    if proto_path and not short_path.startswith(proto_path + "/"):
        fail("Bad proto_path %s for proto %s" % (proto_path, short_path))
    return short_path.removeprefix(proto_path + "/")

def _compile(
        actions,
        proto_info,
        proto_lang_toolchain_info,
        generated_files,
        plugin_output = None,
        additional_args = None,
        additional_tools = [],
        additional_inputs = depset(),
        resource_set = None,
        experimental_exec_group = None,
        experimental_progress_message = None):
    """Creates proto compile action for compiling *.proto files to language specific sources.

    Args:
      actions: (ActionFactory)  Obtained by ctx.actions, used to register the actions.
      proto_info: (ProtoInfo) The ProtoInfo from proto_library to generate the sources for.
      proto_lang_toolchain_info: (ProtoLangToolchainInfo) The proto lang toolchain info.
        Obtained from a `proto_lang_toolchain` target or constructed ad-hoc..
      generated_files: (list[File]) The output files generated by the proto compiler.
        Callee needs to declare files using `ctx.actions.declare_file`.
        See also: `proto_common.declare_generated_files`.
      plugin_output: (File|str) The file or directory passed to the plugin.
        Formatted with `proto_lang_toolchain.out_replacement_format_flag`
      additional_args: (Args) Additional arguments to add to the action.
        Accepts an ctx.actions.args() object that is added at the beginning
        of the command line.
      additional_tools: (list[File]) Additional tools to add to the action.
      additional_inputs: (Depset[File]) Additional input files to add to the action.
      resource_set: (func) A callback function that is passed to the created action.
        See `ctx.actions.run`, `resource_set` parameter for full definition of
        the callback.
      experimental_exec_group: (str) Sets `exec_group` on proto compile action.
        Avoid using this parameter.
      experimental_progress_message: Overrides progress_message from the toolchain.
        Don't use this parameter. It's only intended for the transition.
    """
    args = actions.args()
    args.use_param_file(param_file_arg = "@%s")
    args.set_param_file_format("multiline")
    tools = list(additional_tools)

    if plugin_output:
        args.add(plugin_output, format = proto_lang_toolchain_info.out_replacement_format_flag)
    if proto_lang_toolchain_info.plugin:
        tools.append(proto_lang_toolchain_info.plugin)
        args.add(proto_lang_toolchain_info.plugin.executable, format = proto_lang_toolchain_info.plugin_format_flag)

    args.add_all(proto_info.transitive_proto_path, map_each = _proto_path_flag)
    # Example: `--proto_path=--proto_path=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg`

    args.add_all(proto_lang_toolchain_info.protoc_opts)

    # Include maps
    # For each import, include both the import as well as the import relativized against its
    # protoSourceRoot. This ensures that protos can reference either the full path or the short
    # path when including other protos.
    args.add_all(proto_info._transitive_proto_sources, map_each = _Iimport_path_equals_fullpath)
    # Example: `-Ia.proto=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg/a.proto`

    args.add_all(proto_info.direct_sources)

    if additional_args:
        additional_args.use_param_file(param_file_arg = "@%s")
        additional_args.set_param_file_format("multiline")

    actions.run(
        mnemonic = proto_lang_toolchain_info.mnemonic,
        progress_message = experimental_progress_message if experimental_progress_message else proto_lang_toolchain_info.progress_message,
        executable = proto_lang_toolchain_info.proto_compiler,
        arguments = [additional_args, args] if additional_args else [args],
        inputs = depset(transitive = [proto_info.transitive_sources, additional_inputs]),
        outputs = generated_files,
        tools = tools,
        use_default_shell_env = True,
        resource_set = resource_set,
        exec_group = experimental_exec_group,
        toolchain = None,
    )

_BAZEL_TOOLS_PREFIX = "external/bazel_tools/"

def _experimental_filter_sources(proto_info, proto_lang_toolchain_info):
    if not proto_info.direct_sources:
        return [], []

    # Collect a set of provided protos
    provided_proto_sources = proto_lang_toolchain_info.provided_proto_sources
    provided_paths = {}
    for src in provided_proto_sources:
        path = src._source_file.path

        # For listed protos bundled with the Bazel tools repository, their exec paths start
        # with external/bazel_tools/. This prefix needs to be removed first, because the protos in
        # user repositories will not have that prefix.
        if path.startswith(_BAZEL_TOOLS_PREFIX):
            provided_paths[path[len(_BAZEL_TOOLS_PREFIX):]] = None
        else:
            provided_paths[path] = None

    # Filter proto files
    proto_files = [src._source_file for src in proto_info._direct_proto_sources]
    excluded = []
    included = []
    for proto_file in proto_files:
        if proto_file.path in provided_paths:
            excluded.append(proto_file)
        else:
            included.append(proto_file)
    return included, excluded

def _experimental_should_generate_code(
        proto_info,
        proto_lang_toolchain_info,
        rule_name,
        target_label):
    """Checks if the code should be generated for the given proto_library.

    The code shouldn't be generated only when the toolchain already provides it
    to the language through its runtime dependency.

    It fails when the proto_library contains mixed proto files, that should and
    shouldn't generate code.

    Args:
      proto_info: (ProtoInfo) The ProtoInfo from proto_library to check the generation for.
      proto_lang_toolchain_info: (ProtoLangToolchainInfo) The proto lang toolchain info.
        Obtained from a `proto_lang_toolchain` target or constructed ad-hoc.
      rule_name: (str) Name of the rule used in the failure message.
      target_label: (Label) The label of the target used in the failure message.

    Returns:
      (bool) True when the code should be generated.
    """
    included, excluded = _experimental_filter_sources(proto_info, proto_lang_toolchain_info)

    if included and excluded:
        fail(("The 'srcs' attribute of '%s' contains protos for which '%s' " +
              "shouldn't generate code (%s), in addition to protos for which it should (%s).\n" +
              "Separate '%s' into 2 proto_library rules.") % (
            target_label,
            rule_name,
            ", ".join([f.short_path for f in excluded]),
            ", ".join([f.short_path for f in included]),
            target_label,
        ))

    return bool(included)

def _declare_generated_files(
        actions,
        proto_info,
        extension,
        name_mapper = None):
    """Declares generated files with a specific extension.

    Use this in lang_proto_library-es when protocol compiler generates files
    that correspond to .proto file names.

    The function removes ".proto" extension with given one (e.g. ".pb.cc") and
    declares new output files.

    Args:
      actions: (ActionFactory) Obtained by ctx.actions, used to declare the files.
      proto_info: (ProtoInfo) The ProtoInfo to declare the files for.
      extension: (str) The extension to use for generated files.
      name_mapper: (str->str) A function mapped over the base filename without
        the extension. Used it to replace characters in the name that
        cause problems in a specific programming language.

    Returns:
      (list[File]) The list of declared files.
    """
    proto_sources = proto_info.direct_sources
    outputs = []

    for src in proto_sources:
        basename_no_ext = src.basename[:-(len(src.extension) + 1)]

        if name_mapper:
            basename_no_ext = name_mapper(basename_no_ext)

        # Note that two proto_library rules can have the same source file, so this is actually a
        # shared action. NB: This can probably result in action conflicts if the proto_library rules
        # are not the same.
        outputs.append(actions.declare_file(basename_no_ext + extension, sibling = src))

    return outputs

proto_common_do_not_use = struct(
    compile = _compile,
    declare_generated_files = _declare_generated_files,
    experimental_should_generate_code = _experimental_should_generate_code,
    experimental_filter_sources = _experimental_filter_sources,
    ProtoLangToolchainInfo = ProtoLangToolchainInfo,
)
