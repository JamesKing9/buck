/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Optional;

public class AndroidNativeLibsPackageableGraphEnhancerTest {

  @Test
  public void testNdkLibrary() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);

    NdkLibrary ndkLibrary = (NdkLibrary) new NdkLibraryBuilder(
        BuildTargetFactory.newInstance("//:ndklib"))
        .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.of(ndkLibrary))
            .build();

    APKModuleGraph apkModuleGraph = new APKModuleGraph(
        TargetGraph.EMPTY,
        target,
        Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            originalParams,
            ImmutableMap.of(),
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph
        );

    AndroidPackageableCollector collector = new AndroidPackageableCollector(
        target,
        ImmutableSet.of(),
        ImmutableSet.of(),
        apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            ImmutableSet.of(ndkLibrary)));

    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibrariesOptional =
        enhancer.enhance(collector.build()).getCopyNativeLibraries();
    CopyNativeLibraries copyNativeLibraries =
        copyNativeLibrariesOptional
            .get()
            .get(apkModuleGraph.getRootAPKModule());

    assertThat(copyNativeLibraries.getStrippedObjectDescriptions(), Matchers.empty());
    assertThat(
        copyNativeLibraries.getNativeLibDirectories().stream()
            .map(sourcePathResolver::deprecatedGetPath)
            .collect(MoreCollectors.toImmutableList()),
        Matchers.contains(
            ndkLibrary.getLibraryPath()
        )
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCxxLibrary() throws Exception {

    NdkCxxPlatform ndkCxxPlatform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxPlatforms.CxxRuntime.GNUSTL)
            .setCxxSharedRuntimePath(Paths.get("runtime"))
            .build();

    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms =
        ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>builder()
        .put(NdkCxxPlatforms.TargetCpuType.ARMV7, ndkCxxPlatform)
        .put(NdkCxxPlatforms.TargetCpuType.X86, ndkCxxPlatform)
        .build();

    CxxLibraryBuilder cxxLibraryBuilder = new CxxLibraryBuilder(
        BuildTargetFactory.newInstance("//:cxxlib"))
        .setSoname("somelib.so")
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test/bar.cpp"))));
    TargetNode<CxxLibraryDescription.Arg, ?> cxxLibraryDescription = cxxLibraryBuilder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(cxxLibraryDescription);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(
        ruleResolver,
        new FakeProjectFilesystem(),
        targetGraph);
    ruleResolver.addToIndex(cxxLibrary);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.of(cxxLibrary))
            .build();

    APKModuleGraph apkModuleGraph = new APKModuleGraph(
        TargetGraph.EMPTY,
        target,
        Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            originalParams,
            nativePlatforms,
            ImmutableSet.of(NdkCxxPlatforms.TargetCpuType.ARMV7),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph
        );

    AndroidPackageableCollector collector = new AndroidPackageableCollector(
        target,
        ImmutableSet.of(),
        ImmutableSet.of(),
        apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            ImmutableSet.of(cxxLibrary)));

    AndroidPackageableCollection packageableCollection = collector.build();
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibrariesOptional =
        enhancer.enhance(packageableCollection).getCopyNativeLibraries();
    CopyNativeLibraries copyNativeLibraries =
        copyNativeLibrariesOptional
            .get()
            .get(apkModuleGraph.getRootAPKModule());

    assertThat(
        copyNativeLibraries.getStrippedObjectDescriptions(),
        Matchers.containsInAnyOrder(
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType",
                    Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName",
                    Matchers.equalTo("somelib.so"))
            ),
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType",
                    Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName",
                    Matchers.equalTo("libgnustl_shared.so"))
            )
        )
    );
    assertThat(copyNativeLibraries.getNativeLibDirectories(), Matchers.empty());
    ImmutableCollection<BuildRule> stripRules = sourcePathResolver.filterBuildRuleInputs(
        copyNativeLibraries.getStrippedObjectDescriptions().stream()
            .map(StrippedObjectDescription::getSourcePath)
            .collect(MoreCollectors.toImmutableSet()));
    assertThat(
        stripRules,
        Matchers.contains(
            Matchers.instanceOf(StripLinkable.class),
            Matchers.instanceOf(StripLinkable.class)));
  }
}
