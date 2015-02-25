/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class AppleBinaryDescription
    implements Description<AppleNativeTargetDescriptionArg>, Flavored {
  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("apple_binary");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CompilationDatabase.COMPILATION_DATABASE,
      AppleDescriptions.HEADERS);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final AppleConfig appleConfig;
  private final CxxBinaryDescription delegate;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<CxxPlatform, AppleSdkPaths> appleCxxPlatformsToAppleSdkPaths;

  public AppleBinaryDescription(
      AppleConfig appleConfig,
      CxxBinaryDescription delegate,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<CxxPlatform, AppleSdkPaths> appleCxxPlatformsToAppleSdkPaths) {
    this.appleConfig = appleConfig;
    this.delegate = delegate;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformsToAppleSdkPaths = ImmutableMap.copyOf(appleCxxPlatformsToAppleSdkPaths);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleNativeTargetDescriptionArg createUnpopulatedConstructorArg() {
    return new AppleNativeTargetDescriptionArg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        delegate.hasFlavors(flavors);
  }

  @Override
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    TargetSources targetSources = TargetSources.fromSourcesWithFlags(
        pathResolver,
        args.srcs.get(),
        args.headers.get(),
        args.exportedHeaders.get());
    Optional<BuildRule> flavoredRule = AppleDescriptions
        .createFlavoredRule(
            params,
            resolver,
            args,
            appleConfig,
            pathResolver,
            targetSources);
    if (flavoredRule.isPresent()) {
      return flavoredRule.get();
    }

    CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    CxxLibraryDescription.TypeAndPlatform typeAndPlatform =
        CxxLibraryDescription.getTypeAndPlatform(
            params.getBuildTarget(),
            cxxPlatformFlavorDomain);
    Optional<AppleSdkPaths> appleSdkPaths = Optional.fromNullable(
        appleCxxPlatformsToAppleSdkPaths.get(typeAndPlatform.getPlatform()));
    Sets.SetView<SourcePath> allHeaderPaths = Sets.union(
        targetSources.getPublicHeaderPaths(),
        targetSources.getPrivateHeaderPaths());
    String headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(args, params.getBuildTarget());
    ImmutableMap<String, SourcePath> headerMap = ImmutableMap.<String, SourcePath>builder()
        .putAll(
            AppleDescriptions.convertToFlatCxxHeaders(
                Paths.get(""),
                pathResolver,
                allHeaderPaths))
        .putAll(
            AppleDescriptions.convertToFlatCxxHeaders(
                Paths.get(headerPathPrefix),
                pathResolver,
                allHeaderPaths))
        .build();
    AppleDescriptions.populateCxxConstructorArg(
        delegateArg,
        args,
        ImmutableSet.copyOf(
            Iterables.transform(
                targetSources.getSourcesWithFlags(),
                SourceWithFlags.TO_SOURCE_PATH)),
        headerMap,
        appleSdkPaths);

    return delegate.createBuildRule(params, resolver, delegateArg);
  }
}
