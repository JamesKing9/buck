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

package com.facebook.buck.testrunner;

import static com.facebook.buck.testutil.OutputHelper.containsBuckTestOutputLine;
import static com.facebook.buck.util.Verbosity.BINARY_OUTPUTS;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class TestSelectorsIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test_selectors", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldFailWithoutAnySelectors() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure("Some tests fail");
    assertThat(result.getStderr(), containsString("TESTS FAILED: 1 FAILURE"));
  }

  @Test
  public void shouldRunASingleTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--filter", "com.example.clown.FlowerTest");
    result.assertSuccess("The test passed");
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

  @Test
  public void shouldPassWhenUnselectingEntireFailingClass() throws IOException {
    String testSelector = "!com.example.clown.PrimeMinisterialDecreeTest";
    String[] command1 = {"test", "--all", "--test-selectors", testSelector};
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(command1);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldPassWhenUnselectingTheFailingMethod() throws IOException {
    String[] command = {"test", "--all", "--test-selectors", "!#formAllianceWithClowns"};
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(command);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldNeverCacheWhenUsingSelectors() throws IOException {
    String spammyTestOutput = String.valueOf(BINARY_OUTPUTS.ordinal());
    String[] commandWithoutSelector = {"test", "-v", spammyTestOutput, "--all"};
    String[] commandWithSelector =
        {"test", "-v", spammyTestOutput,
        "--all", "--test-selectors", "#testIsComical"};

    // Without selectors, the first run isn't cached and the second run is.
    ProjectWorkspace.ProcessResult result1 = workspace.runBuckCommand(commandWithoutSelector);
    assertNotCached(result1);
    assertOutputWithoutSelectors(result1);
    assertCached(workspace.runBuckCommand(commandWithoutSelector));

    // With a selector, runs are not cached.
    ProjectWorkspace.ProcessResult result2 = workspace.runBuckCommand(commandWithSelector);
    assertNotCached(result2);
    assertOutputWithSelectors(result2);
    assertNotCached(workspace.runBuckCommand(commandWithSelector));

    // But when we go back to not using a selector, the first run is uncached, and the second is.
    ProjectWorkspace.ProcessResult result3 = workspace.runBuckCommand(commandWithoutSelector);
    assertNotCached(result3);
    assertOutputWithoutSelectors(result3);
    assertCached(workspace.runBuckCommand(commandWithoutSelector));
  }

  @Test
  public void shouldNotMatchMethodsUsingPrefixAlone() throws IOException {
    String[] command = {"test", "--all", "--test-selectors", "#test"};
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(command);
    assertNoTestsRan(result);
    result.assertSuccess("Should pass, albeit because nothing actually ran");
  }

  @Test
  public void shouldReportRegularExpressionErrors() throws IOException {
    String error = "Regular expression error in 'Clown(': Unclosed group near index 7";
    try {
      workspace.runBuckCommand("test", "--all", "--filter", "Clown(");
      fail("Did not catch expected exception!");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), containsString(error));
    }
  }

  @Test
  public void shouldFilterFromFile() throws IOException {
    Path testSelectorsFile = workspace.getPath("test-selectors.txt");
    String arg = String.format("@%s", testSelectorsFile.toAbsolutePath().toString());
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--filter", arg);
    result.assertSuccess();
  }

  @Test
  public void shouldFailWithMultipleStringsGivenToFilter() throws IOException {
    String[] goodArgs = {"test", "--filter", "QQ", "//test/com/example/clown:clown"};
    String goodMessage = "Should pass, because the target is real and no test matches the filter.";

    String[] failArgs = {"test", "--filter", "QQ", "bar", "//test/com/example/clown:clown"};
    String failMessage = "Should fail, because two arguments are given to --filter!";

    workspace.runBuckCommand(goodArgs).assertSuccess(goodMessage);
    workspace.runBuckCommand(failArgs).assertFailure(failMessage);
  }

  @Test
  public void shouldRespectTestSelectorsEvenWithDryRun() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--dry-run", "--filter", "com.example.clown.CarTest");
    result.assertSuccess();
    // If filtering is broken during dry-runs, then we'll output the names of other tests!
    assertThat(result.getStderr(), not(containsString("com.example.clown.FlowerTest")));
  }

  @Test
  public void assertNoSummariesForASingleTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "//test/com/example/clown:clown");
    assertNoTestSummaryShown(result);
  }

  private void assertOutputWithSelectors(ProjectWorkspace.ProcessResult result) {
    String stderr = result.getStderr();
    assertThat("CarTest contains matching test that passes",
        stderr, containsBuckTestOutputLine(
        "PASS", 1, 0, 0, "com.example.clown.CarTest"));
    assertThat("FlowerTest contains matching test, but it is @Ignored",
        stderr, containsBuckTestOutputLine(
        "NOTESTS", 0, 1, 0, "com.example.clown.FlowerTest"));
    assertThat("PrimeMinisterialDecreeTest contains matching test, but it has assumption violation",
        stderr, containsBuckTestOutputLine(
        "ASSUME", 0, 1, 0, "com.example.clown.PrimeMinisterialDecreeTest"));
    assertThat("ShoesTest should not appear, because it has no matches",
        stderr, not(containsString("com.example.clown.ShoesTest")));
  }

  private void assertOutputWithoutSelectors(ProjectWorkspace.ProcessResult result) {
    String stderr = result.getStderr();
    assertThat(stderr, containsBuckTestOutputLine(
        "PASS", 2, 1, 0, "com.example.clown.CarTest"));
    assertThat(stderr, containsBuckTestOutputLine(
        "PASS", 2, 1, 0, "com.example.clown.FlowerTest"));
    assertThat(stderr, containsBuckTestOutputLine(
        "FAIL", 4, 1, 1, "com.example.clown.PrimeMinisterialDecreeTest"));
    assertThat(stderr, containsBuckTestOutputLine(
        "PASS", 2, 0, 0, "com.example.clown.ShoesTest"));
    assertTestSummaryShown(result);
  }

  private void assertCached(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), containsString("CACHED"));
  }

  private void assertNotCached(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("CACHED")));
  }

  private void assertThatATestRan(ProjectWorkspace.ProcessResult result, String testName) {
    assertThat(result.getStderr(), containsString(testName));
  }

  private void assertNoTestsRan(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("com.example.clown.CarTest")));
    assertThat(result.getStderr(), not(containsString("com.example.clown.FlowerTest")));
    assertThat(
        result.getStderr(),
        not(containsString("com.example.clown.PrimeMinisterialDecreeTest")));
    assertThat(result.getStderr(), not(containsString("com.example.clown.ShoesTest")));
    assertThat(result.getStderr(), containsString("NO TESTS RAN"));
  }

  private void assertTestsPassed(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

  private void assertTestSummaryShown(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), containsString("Results for //test/com/example/clown:clown"));
  }

  private void assertNoTestSummaryShown(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("Results for ")));
  }
}
