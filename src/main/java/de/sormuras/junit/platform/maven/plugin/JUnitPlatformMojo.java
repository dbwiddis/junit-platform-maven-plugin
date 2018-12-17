/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.sormuras.junit.platform.maven.plugin;

import static de.sormuras.junit.platform.isolator.Version.JUNIT_PLATFORM_VERSION;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;

import de.sormuras.junit.platform.isolator.Configuration;
import de.sormuras.junit.platform.isolator.ConfigurationBuilder;
import de.sormuras.junit.platform.isolator.Driver;
import de.sormuras.junit.platform.isolator.Isolator;
import de.sormuras.junit.platform.isolator.Modules;
import de.sormuras.junit.platform.isolator.TestMode;
import de.sormuras.junit.platform.isolator.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;

/** Launch JUnit Platform Mojo. */
@org.apache.maven.plugins.annotations.Mojo(
    name = "launch",
    defaultPhase = LifecyclePhase.TEST,
    threadSafe = true,
    requiresDependencyCollection = ResolutionScope.TEST,
    requiresDependencyResolution = ResolutionScope.TEST)
@org.codehaus.plexus.component.annotations.Component(role = AbstractMavenLifecycleParticipant.class)
public class JUnitPlatformMojo extends AbstractMavenLifecycleParticipant implements Mojo {

  /** Skip execution of this plugin. */
  @Parameter(defaultValue = "false")
  private boolean skip = false;

  /** Isolate artifacts in separated class loaders. */
  @Parameter(defaultValue = "true")
  private boolean isolate = true;

  /** Put main and test output directories into same classloader. */
  @Parameter(defaultValue = "true")
  private boolean reunite = true;

  /** Dry-run mode discovers tests but does not execute them. */
  @Parameter(defaultValue = "false")
  private boolean dryRun = false;

  /** Global timeout duration in seconds. */
  @Parameter(defaultValue = "300")
  private long timeout = 300L;

  /** Execution mode. */
  @Parameter(defaultValue = "DIRECT")
  private Executor executor = Executor.DIRECT;

  /** Customized Java command line options. */
  @Parameter private JavaOptions javaOptions = new JavaOptions();

  /** Custom version map to override detected version. */
  @Parameter private Map<String, String> versions = emptyMap();

  /** The underlying Maven build model. */
  @Parameter(defaultValue = "${project.build}", readonly = true, required = true)
  private Build mavenBuild;

  /** The underlying Maven project. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject mavenProject;

  /** The current repository/network configuration of Maven. */
  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession mavenRepositorySession;

  /** The entry point to Maven Artifact Resolver, i.e. the component doing all the work. */
  @Component private RepositorySystem mavenResolver;

  /**
   * Launcher configuration parameters.
   *
   * <p>Set a configuration parameter for test discovery and execution.
   *
   * <h3>Console Launcher equivalent</h3>
   *
   * {@code --config <key=value>}
   *
   * @see <a
   *     href="https://junit.org/junit5/docs/current/user-guide/#running-tests-config-params">Configuration
   *     Parameters</a>
   */
  @Parameter private Map<String, String> parameters = emptyMap();

  /**
   * Tags or tag expressions to include only tests whose tags match.
   *
   * <p>All tags and expressions will be combined using {@code OR} semantics.
   *
   * <h3>Console Launcher equivalent</h3>
   *
   * {@code --include-tag <String>}
   *
   * @see <a
   *     href="https://junit.org/junit5/docs/current/user-guide/#running-tests-tag-expressions">Tag
   *     Expressions</a>
   * @see <a
   *     href="https://junit.org/junit5/docs/current/user-guide/#writing-tests-tagging-and-filtering">Tagging
   *     and Filtering</a>
   */
  @Parameter private List<String> tags = emptyList();

  /** The log instance passed in via setter. */
  private Log log;

  /** Modular world helper. */
  private Modules projectModules;

  /** Versions detected by scanning the artifacts of the current project. */
  private Map<String, String> projectVersions;

  @Override
  public void setLog(Log log) {
    this.log = log;
  }

  @Override
  public Log getLog() {
    if (this.log == null) {
      this.log = new SystemStreamLog();
    }
    return this.log;
  }

  void debug(String format, Object... args) {
    getLog().debug(formatMessage(format, args));
  }

  private void debug(String caption, Collection<Path> paths) {
    debug(caption);
    paths.forEach(path -> debug(String.format("  %-50s -> %s", path.getFileName(), path)));
  }

  void info(String format, Object... args) {
    getLog().info(formatMessage(format, args));
  }

  void warn(String format, Object... args) {
    getLog().warn(formatMessage(format, args));
  }

  void error(String format, Object... args) {
    getLog().error(formatMessage(format, args));
  }

  private static String formatMessage(String pattern, Object... args) {
    // fast-path
    if (args.length == 0) {
      return pattern;
    }
    // guard against illegal patterns...
    try {
      return MessageFormat.format(pattern, args);
    } catch (IllegalArgumentException e) {
      return pattern + " " + Arrays.asList(args) + " // " + e.getClass() + ": " + e.getMessage();
    }
  }

  @Override
  public void afterProjectsRead(MavenSession session) {
    for (MavenProject project : session.getProjects()) {
      Optional<Plugin> thisPlugin =
          findPlugin(project, "de.sormuras", "junit-platform-maven-plugin");
      thisPlugin.ifPresent(plugin -> injectThisPluginIntoTestExecutionPhase(project, plugin));
    }
  }

  private void injectThisPluginIntoTestExecutionPhase(MavenProject project, Plugin thisPlugin) {
    PluginExecution execution = new PluginExecution();
    execution.setId("injected-launch");
    execution.getGoals().add("launch");
    execution.setPhase("test");
    execution.setConfiguration(thisPlugin.getConfiguration());
    thisPlugin.getExecutions().add(execution);

    Optional<Plugin> surefirePlugin =
        findPlugin(project, "org.apache.maven.plugins", "maven-surefire-plugin");
    surefirePlugin.ifPresent(surefire -> surefire.getExecutions().clear());
  }

  private Optional<Plugin> findPlugin(MavenProject project, String group, String artifact) {
    List<Plugin> plugins = project.getModel().getBuild().getPlugins();
    return plugins
        .stream()
        .filter(plugin -> group.equals(plugin.getGroupId()))
        .filter(plugin -> artifact.equals(plugin.getArtifactId()))
        .reduce(
            (u, v) -> {
              throw new IllegalStateException("Plugin is not unique: " + artifact);
            });
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    debug("Executing JUnitPlatformMojo...");

    if (skip) {
      info("JUnit Platform Plugin execution skipped.");
      return;
    }

    if (mavenProject.getPackaging().equals("pom")) {
      info("JUnit Platform Plugin execution skipped: project uses 'pom' packaging");
      return;
    }

    Path mainPath = Paths.get(mavenBuild.getOutputDirectory());
    Path testPath = Paths.get(mavenBuild.getTestOutputDirectory());
    this.projectModules = new Modules(mainPath, testPath);
    this.projectVersions = Version.buildMap(this::artifactVersionOrNull);

    info("Launching JUnit Platform {0}...", version(JUNIT_PLATFORM_VERSION));
    if (getLog().isDebugEnabled()) {
      debug("Path");
      debug("  java.home = {0}", System.getProperty("java.home"));
      debug("  user.dir = {0}", System.getProperty("user.dir"));
      debug("  project.basedir = {0}", mavenProject.getBasedir());
      debug("Class Loader");
      debug("  class loader = {0}", getClass().getClassLoader());
      debug("  context loader = {0}", Thread.currentThread().getContextClassLoader());
      debug("Artifact Map");
      mavenProject
          .getArtifactMap()
          .keySet()
          .stream()
          .sorted()
          .forEach(
              k -> debug(String.format("  %-50s -> %s", k, mavenProject.getArtifactMap().get(k))));
      debug("Version");
      debug("  java.version = {0}", System.getProperty("java.version"));
      debug("  java.class.version = {0}", System.getProperty("java.class.version"));
      Version.forEach(v -> debug("  {0} = {1}", v.getKey(), version(v)));
      debug("Java Module System");
      debug("  main -> {0}", projectModules.toStringMainModule());
      debug("  test -> {0}", projectModules.toStringTestModule());
      debug("  mode -> {0}", projectModules.getMode());
    }

    if (Files.notExists(Paths.get(mavenBuild.getTestOutputDirectory()))) {
      info("Test output directory does not exist.");
      return;
    }

    // Create target directory to store log files...
    Path targetPath = Paths.get(mavenProject.getBuild().getDirectory(), "junit-platform");
    try {
      Files.createDirectories(targetPath);
    } catch (IOException e) {
      throw new MojoExecutionException("Can't create target path: " + targetPath, e);
    }

    // Basic configuration...
    ConfigurationBuilder configurationBuilder =
        new ConfigurationBuilder()
            .setDryRun(isDryRun())
            .setTargetDirectory(targetPath.toString())
            .discovery()
            .setFilterTagsIncluded(tags)
            .setParameters(parameters)
            .end();

    // Configure selectors...
    TestMode mode = projectModules.getMode();
    if (mode == TestMode.CLASSIC) {
      Set<String> roots = singleton(mavenBuild.getTestOutputDirectory());
      configurationBuilder.discovery().setSelectedClasspathRoots(roots);
    } else {
      String module =
          mode == TestMode.MODULAR_PATCHED_TEST_RUNTIME
              ? projectModules.getMainModuleName().orElseThrow(AssertionError::new)
              : projectModules.getTestModuleName().orElseThrow(AssertionError::new);
      Set<String> modules = singleton(module);
      configurationBuilder.discovery().setSelectedModules(modules);
    }

    Configuration configuration = configurationBuilder.build();
    Driver driver = new MavenDriver(this, configuration);
    if (getLog().isDebugEnabled()) {
      debug("Path");
      driver.paths().forEach(this::debug);
    }

    try {
      int result = execute(driver, configuration);
      if (result != 0) {
        throw new MojoFailureException("RED ALERT!");
      }
    } catch (MojoExecutionException | MojoFailureException e) {
      throw e;
    } catch (Exception e) {
      throw new AssertionError("Unexpected exception caught!", e);
    }
  }

  private int execute(Driver driver, Configuration configuration) throws Exception {
    if (executor == Executor.DIRECT) {
      return executeDirect(driver, configuration);
    }
    if (executor == Executor.JAVA) {
      return executeJava(driver, configuration);
    }
    throw new MojoExecutionException("Unsupported executor: " + executor);
  }

  private int executeDirect(Driver driver, Configuration configuration) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Integer> future = executor.submit(() -> new Isolator(driver).evaluate(configuration));
    try {
      return future.get(timeout, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      warn("Global timeout of {0} second(s) reached.", timeout);
      throw new MojoFailureException("Global timeout reached.", e);
    } catch (Exception e) {
      throw new MojoExecutionException("Execution failed!", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private int executeJava(Driver driver, Configuration configuration) {
    JavaExecutor executor = new JavaExecutor(this, driver);
    return executor.evaluate(configuration);
  }

  Executor getExecutor() {
    return executor;
  }

  JavaOptions getJavaOptions() {
    return javaOptions;
  }

  Modules getProjectModules() {
    return projectModules;
  }

  MavenProject getMavenProject() {
    return mavenProject;
  }

  RepositorySystemSession getMavenRepositorySession() {
    return mavenRepositorySession;
  }

  RepositorySystem getMavenResolver() {
    return mavenResolver;
  }

  long getTimeout() {
    return timeout;
  }

  Map<String, String> getVersions() {
    return versions;
  }

  boolean isDryRun() {
    return dryRun;
  }

  boolean isIsolate() {
    return isolate;
  }

  boolean isReunite() {
    return reunite;
  }

  private String artifactVersionOrNull(String key) {
    Artifact artifact = mavenProject.getArtifactMap().get(key);
    if (artifact == null) {
      return null;
    }
    return artifact.getBaseVersion();
  }

  String version(Version version) {
    String detectedVersion = projectVersions.get(version.getKey());
    return versions.getOrDefault(version.getKey(), detectedVersion);
  }

  String getJavaExecutable() {
    // TODO javaExecutable parameter or Maven Toolchain?
    //    if (javaExecutable != null) {
    //      return javaExecutable;
    //    }
    String extension = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
    Path home = Paths.get(System.getProperty("java.home"));
    Path java = home.resolve("bin").resolve("java" + extension);
    return java.normalize().toAbsolutePath().toString();
  }
}
