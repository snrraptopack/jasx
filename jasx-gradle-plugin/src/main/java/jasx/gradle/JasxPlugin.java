package jasx.gradle;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
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
 * <p>For each source set ({@code main}, {@code test}, ...) it adds a task, such as {@code compileJasx}
 * for {@code main} and {@code compileTestJasx} for {@code test}, that:
 *
 * <ul>
 *   <li>compiles the {@code .jasx} files found in the source set's Java directories
 *       ({@code src/main/java}) and in {@code src/main/jasx};
 *   <li>also reads the {@code .java} files there, so that {@code @jasx.Component} methods in plain Java
 *       can be used from markup;
 *   <li>writes the generated Java to {@code build/generated/sources/jasx/main}.
 * </ul>
 *
 * <p>That output directory is added to the source set's Java sources, so {@code compileJava} depends on
 * the task and compiles the generated files along with the hand-written ones.
 */
public final class JasxPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        JasxExtension extension = project.getExtensions().create("jasx", JasxExtension.class);
        extension.getTarget().convention("html");

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.all(sourceSet -> addCompileTask(project, extension, sourceSet));
    }

    private static void addCompileTask(Project project, JasxExtension extension, SourceSet sourceSet) {
        String taskName = sourceSet.getTaskName("compile", "Jasx");
        Provider<Directory> outputDirectory = project.getLayout().getBuildDirectory()
                .dir("generated/sources/jasx/" + sourceSet.getName());

        // Where .jasx files may live: next to .java files, or in their own directory. This is read before
        // the output directory is added to the Java sources below, so the output is never an input.
        Set<File> sourceRoots = new LinkedHashSet<>(sourceSet.getJava().getSrcDirs());
        sourceRoots.add(project.file("src/" + sourceSet.getName() + "/jasx"));
        FileCollection roots = project.files(sourceRoots);

        TaskProvider<JasxCompile> compileJasx = project.getTasks().register(taskName, JasxCompile.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles the " + sourceSet.getName() + " .jasx files to Java.");
            task.getSourceRoots().from(roots);
            task.getSource().from(roots.getAsFileTree().matching(files -> files.include("**/*.jasx")));
            task.getJavaSources().from(roots.getAsFileTree().matching(files -> files.include("**/*.java")));
            task.getClasspath().from(sourceSet.getCompileClasspath());
            task.getTarget().set(extension.getTarget());
            task.getOutputDirectory().set(outputDirectory);
        });

        // Adding the task's output as a source directory also makes compileJava depend on the task.
        sourceSet.getJava().srcDir(compileJasx.flatMap(JasxCompile::getOutputDirectory));
    }
}
