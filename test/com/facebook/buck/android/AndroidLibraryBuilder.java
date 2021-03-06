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

package com.facebook.buck.android;

import static com.facebook.buck.android.AndroidLibraryCompilerFactory.JAVA_ONLY_COMPILER_FACTORY;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import java.nio.file.Path;
import java.util.Optional;

public class AndroidLibraryBuilder
    extends AbstractNodeBuilder<AndroidLibraryDescription.Arg, AndroidLibraryDescription> {

  private AndroidLibraryBuilder(BuildTarget target) {
    super(new AndroidLibraryDescription(ANDROID_JAVAC_OPTIONS, JAVA_ONLY_COMPILER_FACTORY), target);
  }

  public static AndroidLibraryBuilder createBuilder(BuildTarget target) {
    return new AndroidLibraryBuilder(target);
  }

  public AndroidLibraryBuilder addProcessor(String processor) {
    arg.annotationProcessors = amendSet(arg.annotationProcessors, processor);
    return this;
  }

  public AndroidLibraryBuilder addProcessorBuildTarget(BuildTarget processorRule) {
    arg.annotationProcessorDeps = amend(
        arg.annotationProcessorDeps,
        processorRule);
    return this;
  }

  public AndroidLibraryBuilder setManifestFile(SourcePath manifestFile) {
    arg.manifest = Optional.of(manifestFile);
    return this;
  }

  public AndroidLibraryBuilder addDep(BuildTarget rule) {
    arg.deps = amend(arg.deps, rule);
    return this;
  }

  public AndroidLibraryBuilder setDepsQuery(String query) {
    arg.depsQuery = Optional.of(query);
    return this;
  }

  public AndroidLibraryBuilder addProvidedDep(BuildTarget rule) {
    arg.providedDeps = amend(arg.providedDeps, rule);
    return this;
  }

  public AndroidLibraryBuilder addSrc(Path path) {
    arg.srcs = amend(arg.srcs, new PathSourcePath(new FakeProjectFilesystem(), path));
    return this;
  }
}
