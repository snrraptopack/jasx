package jasx.gradle;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * The JASX Gradle plugin, {@code id("dev.jasx")}.
 *
 * <p>For each source set ({@code main}, {@code test}, ...) it adds two tasks:
 *
 * <ul>
 *   <li>{@code compileJasx} (for {@code main}; {@code compileTestJasx} for {@code test}, ...) compiles the
 *       {@code .jasx} files found in the source set's Java directories ({@code src/main/java}) and in
 *       {@code src/main/jasx}, and writes the generated Java to {@code build/generated/sources/jasx/main}.
 *       It also reads the {@code .java} files there, so that {@code @jasx.Component} methods in plain Java
 *       can be used from markup.
 *   <li>{@code reportJasxJavacErrors} runs only when {@code compileJava} fails, and reprints javac's errors
 *       in generated code at their {@code .jasx} locations ({@link JasxJavacReport}).
 * </ul>
 *
 * <p>The output directory is added to the source set's Java sources, so {@code compileJava} depends on
 * {@code compileJasx} and compiles the generated files along with the hand-written ones.
 */
public final class JasxPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        JasxExtension extension = project.getExtensions().create("jasx", JasxExtension.class);
        extension.getTarget().convention("html");

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.all(sourceSet -> configure(project, extension, sourceSet));
    }

    private static void configure(Project project, JasxExtension extension, SourceSet sourceSet) {
        // Where .jasx files may live: next to .java files, or in their own directory. This is read before
        // the output directory is added to the Java sources below, so the output is never an input.
        Set<File> sourceRootDirs = new LinkedHashSet<>(sourceSet.getJava().getSrcDirs());
        sourceRootDirs.add(project.file("src/" + sourceSet.getName() + "/jasx"));
        FileCollection sourceRoots = project.files(sourceRootDirs);
        FileCollection jasxFiles = sourceRoots.getAsFileTree().matching(files -> files.include("**/*.jasx"));
        FileCollection javaFiles = sourceRoots.getAsFileTree().matching(files -> files.include("**/*.java"));

        // The target's descriptor and schema are resources. A project dependency (in a multi-project or
        // composite build) puts only its classes on the compile classpath, and its resources only on the
        // runtime classpath, so both are needed. The runtime classpath *configuration* is used, not
        // sourceSet.getRuntimeClasspath(), which would include this source set's own output and so
        // depend on compileJava, which depends on compileJasx.
        FileCollection jasxClasspath = sourceSet.getCompileClasspath()
                .plus(project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));

        Provider<Directory> outputDirectory = project.getLayout().getBuildDirectory()
                .dir("generated/sources/jasx/" + sourceSet.getName());

        TaskProvider<JasxCompile> compileJasx = project.getTasks().register(
                sourceSet.getTaskName("compile", "Jasx"), JasxCompile.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Compiles the " + sourceSet.getName() + " .jasx files to Java.");
                    task.getSourceRoots().from(sourceRoots);
                    task.getSource().from(jasxFiles);
                    task.getJavaSources().from(javaFiles);
                    task.getClasspath().from(jasxClasspath);
                    task.getTarget().set(extension.getTarget());
                    task.getOutputDirectory().set(outputDirectory);
                });

        // Adding the task's output as a source directory also makes compileJava depend on the task.
        sourceSet.getJava().srcDir(compileJasx.flatMap(JasxCompile::getOutputDirectory));

        // When compileJava fails, reprint its errors in generated code at their .jasx locations.
        TaskProvider<Task> compileJava = project.getTasks().named(sourceSet.getCompileJavaTaskName());
        TaskProvider<JasxJavacReport> report = project.getTasks().register(
                sourceSet.getTaskName("report", "JasxJavacErrors"), JasxJavacReport.class, task -> {
                    task.setDescription("Reports javac errors in code generated from .jasx files at their .jasx locations.");
                    task.getSourceRoots().from(sourceRoots);
                    task.getJavaSources().from(javaFiles);
                    task.getCompileClasspath().from(sourceSet.getCompileClasspath());
                    task.getJasxClasspath().from(jasxClasspath);
                    task.getTarget().set(extension.getTarget());
                    // Only useful when compileJava failed, and only if there are .jasx files.
                    task.onlyIf("compileJava failed", t -> compileJava.get().getState().getFailure() != null);
                    task.onlyIf("there are .jasx files", t -> !jasxFiles.isEmpty());
                });
        compileJava.configure(task -> task.finalizedBy(report));
    }
}
