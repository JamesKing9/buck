/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final BuildTarget TARGET_TWO = BuildTargetFactory.newInstance("//two/:target");
  private static final CxxPlatform CXX_PLATFORM = PrebuiltCxxLibraryBuilder.createDefaultPlatform();

  private static Path getStaticLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.orElse("lib");
    String libName = arg.libName.orElse(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.a", libName));
  }

  private static Path getStaticPicLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.orElse("lib");
    String libName = arg.libName.orElse(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s_pic.a", libName));
  }

  private static Path getSharedLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.orElse("lib");
    String libName = arg.libName.orElse(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescription.Arg arg) {
    String libName = arg.libName.orElse(TARGET.getShortName());
    return arg.soname.orElse(String.format(
        "lib%s.%s",
        libName,
        CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static ImmutableList<Path> getIncludeDirs(PrebuiltCxxLibraryDescription.Arg arg) {
    return arg.includeDirs.stream()
        .map(TARGET.getBasePath()::resolve)
        .collect(MoreCollectors.toImmutableList());
  }

  private static ImmutableSet<BuildTarget> getInputRules(BuildRule buildRule) {
    return ImmutableSet.of(
        BuildTarget.builder()
            .from(buildRule.getBuildTarget())
            .addFlavors(CXX_PLATFORM.getFlavor())
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .build());
  }

  private static ImmutableSet<Path> getHeaderNames(Iterable<CxxHeaders> includes) {
    ImmutableSet.Builder<Path> names = ImmutableSet.builder();
    for (CxxHeaders headers : includes) {
      CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) headers;
      names.addAll(symlinkTreeHeaders.getNameToPathMap().keySet());
    }
    return names.build();
  }

  @Test
  public void createBuildRuleDefault() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(
            FileListableLinkerInputArg.withSourcePathArg(
                new SourcePathArg(
                    pathResolver,
                    new PathSourcePath(filesystem, getStaticLibraryPath(arg))))),
        ImmutableSet.of(),
        ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.of(),
        ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void headerOnly() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addAllSystemIncludeRoots(getIncludeDirs(arg))
            .build();
    assertThat(
        lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(),
        ImmutableSet.of(),
        ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(),
        ImmutableSet.of(),
        ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void createBuildRuleExternal() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.of(),
        ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void missingSharedLibsAreAutoBuilt() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    BuildRule rule =
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(pathResolver))
            .toList()
            .get(0);
    assertTrue(rule instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(pathResolver))
            .toList(),
        empty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        lib.getSharedLibraries(CXX_PLATFORM));
  }

  @Test
  public void locationMacro() throws NoSuchBuildTargetException {

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);
    Optional<String> libName = Optional.of("test");
    Optional<String> libDir = Optional.of("$(location //other:gen_lib)/");

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget genTarget = BuildTargetFactory.newInstance("//other:gen_lib");
    GenruleBuilder genruleBuilder = GenruleBuilder
        .newGenruleBuilder(genTarget)
        .setOut("lib_dir");

    BuildRule genRule = genruleBuilder.build(resolver);

    CxxPlatform platform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM1"));

    Path path = Preconditions.checkNotNull(genRule.getPathToOutput()).toAbsolutePath();
    final SourcePath staticLibraryPath = PrebuiltCxxLibraryDescription.getStaticLibraryPath(
        TARGET,
        cellRoots,
        filesystem,
        resolver,
        platform,
        libDir,
        libName);
    assertEquals(
        TARGET.getBasePath().resolve(String.format("%s/libtest.a", path)),
        pathResolver.getAbsolutePath(staticLibraryPath));
  }

  @Test
  public void goodPathNoLocation() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    CxxPlatform platform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM1"));

    final SourcePath staticLibraryPath = PrebuiltCxxLibraryDescription.getStaticLibraryPath(
        TARGET_TWO,
        TestCellBuilder.createCellRoots(filesystem),
        filesystem,
        resolver,
        platform,
        Optional.of("lib"),
        Optional.empty());

    assertThat(
        MorePaths.pathWithUnixSeparators(pathResolver.getAbsolutePath(staticLibraryPath)),
        Matchers.containsString(String.format("two/%s/libtarget.a", "lib")));
  }

  @Test
  public void findDepsFromParamsWithLocation() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget genTarget = BuildTargetFactory.newInstance("//other:gen_lib");
    GenruleBuilder genruleBuilder = GenruleBuilder
        .newGenruleBuilder(genTarget)
        .setOut("lib_dir");

    BuildRule genrule = genruleBuilder.build(resolver);

    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("$(location //other:gen_lib)");
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver);

    Iterable<BuildTarget> implicit = builder.findImplicitDeps();
    assertEquals(1, Iterables.size(implicit));
    assertTrue(Iterables.contains(implicit, genTarget));

    assertThat(
        lib.getDeps(),
        Matchers.contains(genrule));
  }

  @Test
  public void findDepsFromParamsWithNone() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("lib");
    builder.build(resolver);

    assertEquals(0, Iterables.size(builder.findImplicitDeps()));
  }

  @Test
  public void findDepsFromParamsWithPlatform() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("$(platform)");
    builder.build(resolver);

    assertEquals(0, Iterables.size(builder.findImplicitDeps()));
  }

  @Test
  public void platformMacro() {
    Optional<String> libDir = Optional.of("libs/$(platform)");
    Optional<String> libName = Optional.of("test-$(platform)");

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);

    CxxPlatform platform1 =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM1"));
    CxxPlatform platform2 =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM2"));

    assertEquals(
        filesystem.resolve(
        TARGET.getBasePath()
            .resolve(
                String.format(
                    "libs/PLATFORM1/libtest-PLATFORM1.%s",
                    platform1.getSharedLibraryExtension()))),
        pathResolver.getAbsolutePath(PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            TARGET,
            cellRoots,
            filesystem,
            resolver,
            platform1,
            libDir,
            libName)));
    assertEquals(
        filesystem.resolve(TARGET.getBasePath()
            .resolve("libs/PLATFORM1/libtest-PLATFORM1.a")),
        pathResolver.getAbsolutePath(PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            TARGET,
            cellRoots,
            filesystem,
            resolver,
            platform1,
            libDir,
            libName)));

    assertEquals(
        filesystem.resolve(TARGET.getBasePath()
            .resolve(
                String.format(
                    "libs/PLATFORM2/libtest-PLATFORM2.%s",
                    platform2.getSharedLibraryExtension()))),
        pathResolver.getAbsolutePath(PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            TARGET,
            cellRoots,
            filesystem,
            resolver,
            platform2,
            libDir,
            libName)));
    assertEquals(
        filesystem.resolve(TARGET.getBasePath()
            .resolve("libs/PLATFORM2/libtest-PLATFORM2.a")),
        pathResolver.getAbsolutePath(PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            TARGET,
            cellRoots,
            filesystem,
            resolver,
            platform2,
            libDir,
            libName)));
  }

  @Test
  public void exportedHeaders() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedHeaders(
            SourceList.ofNamedSources(
                ImmutableSortedMap.of(
                    "foo.h",
                    new FakeSourcePath("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.hasItem(filesystem.getRootPath().getFileSystem().getPath("foo.h")));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, pathResolver)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void exportedPlatformHeaders() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedPlatformHeaders(
            PatternMatchedCollection.<SourceList>builder()
                .add(
                    Pattern.compile(CXX_PLATFORM.getFlavor().toString()),
                    SourceList.ofNamedSources(
                        ImmutableSortedMap.of(
                            "foo.h",
                            new FakeSourcePath("foo.h"))))
                .add(
                    Pattern.compile("DO NOT MATCH ANYTNING"),
                    SourceList.ofNamedSources(
                        ImmutableSortedMap.of(
                            "bar.h",
                            new FakeSourcePath("bar.h"))))
                .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.hasItem(filesystem.getRootPath().getFileSystem().getPath("foo.h")));
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.not(Matchers.hasItem(filesystem.getRootPath().getFileSystem().getPath("bar.h"))));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, pathResolver)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void testBuildSharedWithDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxPlatform platform = CxxLibraryBuilder.createDefaultPlatform();

    BuildRule genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_libx"))
            .setOut("gen_libx")
            .setCmd("something")
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "class Test {}",
        new File(Preconditions.checkNotNull(genSrc.getPathToOutput()).toString(), "libx.so")
            .toPath());

    BuildTarget target = BuildTargetFactory.newInstance("//:x")
        .withFlavors(platform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(target)
        .setLibName("x")
        .setLibDir("$(location //:gen_libx)");
    CxxLink lib = (CxxLink) builder.build(resolver, filesystem);
    assertNotNull(lib);
    assertThat(lib.getDeps(), Matchers.contains(genSrc));
  }

  @Test
  public void headerNamespace() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderNamespace("hello")
        .setExportedHeaders(
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.of(new FakeSourcePath("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.contains(filesystem.getRootPath().getFileSystem().getPath("hello", "foo.h")));
  }

  @Test
  public void staticPicLibsUseCorrectPath() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CXX_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs()).get(0),
        Matchers.endsWith(
            getStaticPicLibraryPath(libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void missingStaticPicLibsUseStaticLibs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    filesystem.touch(
        filesystem.resolve(getStaticPicLibraryPath(libBuilder.build().getConstructorArg())));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs()).get(0),
        Matchers.endsWith(
            getStaticPicLibraryPath(
                libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void forceStatic() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(TARGET)
            .setForceStatic(true)
            .build(resolver, filesystem);
    assertThat(
        prebuiltCxxLibrary.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.STATIC));
  }

  @Test
  public void exportedLinkerFlagsAreUsedToBuildSharedLibrary() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target =
        BuildTarget.builder(BuildTargetFactory.newInstance("//:lib"))
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .addFlavors(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor())
            .build();
    CxxLink cxxLink =
        (CxxLink) new PrebuiltCxxLibraryBuilder(target)
            .setExportedLinkerFlags(ImmutableList.of("--some-flag"))
            .setForceStatic(true)
            .build(resolver);
    assertThat(
        Arg.stringify(cxxLink.getArgs()),
        Matchers.hasItem("--some-flag"));
  }

  @Test
  public void nativeLinkableDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxLibraryBuilder.createDefaultPlatform())),
        empty());
  }

  @Test
  public void nativeLinkableExportedDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        ImmutableList.copyOf(rule.getNativeLinkableDepsForPlatform(
            CxxLibraryBuilder.createDefaultPlatform())),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.contains(dep));
  }

  @Test
  public void includesDirs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setIncludeDirs(ImmutableList.of("include"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC)
            .getIncludes(),
        Matchers.contains(
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.SYSTEM,
                new PathSourcePath(
                    filesystem,
                    rule.getBuildTarget().getBasePath().resolve("include")))));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setIncludeDirs(ImmutableList.of());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        empty());
    assertThat(
        input.getSystemIncludeRoots(),
        empty());
    assertThat(
        ImmutableList.copyOf(input.getDeps(resolver, pathResolver)),
        empty());
  }

  @Test
  public void linkWithoutSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setLinkWithoutSoname(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    NativeLinkableInput input =
        rule.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED);
    assertThat(
        Arg.stringify(input.getArgs()),
        Matchers.contains(
            "-L" + filesystem.resolve(rule.getBuildTarget().getBasePath()).resolve("lib"),
            "-lrule"));
  }

  @Test
  public void missingStaticLibIsNotANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void providedLibIsNotANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setProvided(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void existingStaticLibIsANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertTrue(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSoname("libsoname.so");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getNativeLinkTarget(CXX_PLATFORM).get()
            .getNativeLinkTargetMode(CXX_PLATFORM),
        Matchers.equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver, filesystem);
    PrebuiltCxxLibrary exportedDep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(
                BuildTargetFactory.newInstance("//:exported_dep"))
            .build(resolver, filesystem);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(
                BuildTargetFactory.newInstance("//:rule"))
            .setExportedDeps(
                ImmutableSortedSet.of(dep.getBuildTarget(), exportedDep.getBuildTarget()))
            .build(resolver, filesystem);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkTarget(CXX_PLATFORM).get()
                .getNativeLinkTargetDeps(CXX_PLATFORM)),
        Matchers.hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedLinkerFlags(ImmutableList.of("--exported-flag"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(ruleBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule = (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem);
    NativeLinkableInput input =
        rule.getNativeLinkTarget(CXX_PLATFORM).get()
            .getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(input.getArgs()),
        Matchers.hasItems("--exported-flag"));
  }

  @Test
  public void missingStaticLibPrefersSharedLinking() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getPreferredLinkage(CXX_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void providedDoNotReturnSharedLibs() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setProvided(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getSharedLibraries(CXX_PLATFORM).entrySet(),
        empty());
  }

  @Test
  public void headerOnlyLibPrefersAnyLinking() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setHeaderOnly(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getPreferredLinkage(CXX_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.ANY));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(target);
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(targetGraph1, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary prebuiltCxxLibrary = (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder
        .build(resolver1, filesystem, targetGraph1);
    assertThat(
        prebuiltCxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.not(empty()));
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        Matchers.not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    prebuiltCxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver2 =
        new BuildRuleResolver(targetGraph2, new DefaultTargetNodeToBuildRuleTransformer());
    prebuiltCxxLibrary = (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder
        .build(resolver2, filesystem, targetGraph2);
    assertThat(
        prebuiltCxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        empty());
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        empty());
  }

}
