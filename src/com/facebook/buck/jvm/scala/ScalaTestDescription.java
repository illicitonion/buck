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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.Optional;
import java.util.logging.Level;

public class ScalaTestDescription implements Description<ScalaTestDescription.Arg>,
    ImplicitDepsInferringDescription<ScalaTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("scala_test");

  private final ScalaBuckConfig config;
  private final JavaOptions javaOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public ScalaTestDescription(
      ScalaBuckConfig config,
      JavaOptions javaOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.config = config;
    this.javaOptions = javaOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    final BuildRule scalaLibrary = resolver.getRule(config.getScalaLibraryTarget());
    BuildRuleParams params = rawParams.copyWithDeps(
        () -> ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(rawParams.getDeclaredDeps().get())
            .add(scalaLibrary)
            .build(),
        rawParams.getExtraDeps()
    );

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            params,
            args.useCxxLibraries,
            args.cxxLibraryWhitelist,
            resolver,
            pathResolver,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    Tool scalac = config.getScalac(resolver);

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps))),
                        scalac.getDeps(pathResolver)))
                    .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                pathResolver,
                args.srcs,
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources),
                /* generatedSourceFolderName */ Optional.empty(),
                /* proguardConfig */ Optional.empty(),
                /* postprocessClassesCommands */ ImmutableList.of(),
                /* exportDeps */ ImmutableSortedSet.of(),
                /* providedDeps */ ImmutableSortedSet.of(),
                new BuildTargetSourcePath(abiJarTarget),
                /* trackClassUsage */ false,
                /* additionalClasspathEntries */ ImmutableSet.of(),
                new ScalacToJarStepFactory(
                    scalac,
                    ImmutableList.<String>builder()
                        .addAll(config.getCompilerFlags())
                        .addAll(args.extraArguments)
                        .build()
                ),
                args.resourcesRoot,
                args.manifestFile,
                args.mavenCoords,
                /* tests */ ImmutableSortedSet.of(),
                /* classesToRemoveFromJar */ ImmutableSet.of()));

    JavaTest scalaTest =
        resolver.addToIndex(
            new JavaTest(
                params.copyWithDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
                pathResolver,
                testsLibrary,
                /* additionalClasspathEntries */ ImmutableSet.of(),
                args.labels,
                args.contacts,
                args.testType.orElse(TestType.JUNIT),
                javaOptions.getJavaRuntimeLauncher(),
                args.vmArgs,
                cxxLibraryEnhancement.nativeLibsEnvironment,
                args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
                args.env,
                args.runTestSeparately.orElse(false),
                args.forkMode.orElse(ForkMode.NONE),
                args.stdOutLogLevel,
                args.stdErrLogLevel));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(testsLibrary.getBuildTarget())));

    return scalaTest;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return ImmutableList.<BuildTarget>builder()
        .add(config.getScalaLibraryTarget())
        .addAll(OptionalCompat.asSet(config.getScalacTarget()))
        .build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends ScalaLibraryDescription.Arg {
    public ImmutableSortedSet<String> contacts = ImmutableSortedSet.of();
    public ImmutableSortedSet<Label> labels = ImmutableSortedSet.of();
    public ImmutableList<String> vmArgs = ImmutableList.of();
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Boolean> useCxxLibraries;
    public ImmutableSet<BuildTarget> cxxLibraryWhitelist = ImmutableSet.of();
    public Optional<Long> testRuleTimeoutMs;
    public ImmutableMap<String, String> env = ImmutableMap.of();
  }
}
