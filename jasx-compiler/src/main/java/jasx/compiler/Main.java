package jasx.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point.
 *
 * <pre>
 *   jasx-compiler [--target html] [--out DIR] FILE...
 * </pre>
 *
 * Compiles {@code .jasx} files (and indexes components from {@code .java} files) with the target found on
 * the classpath. Generated Java is written to {@code DIR} (default: stdout). Exit code 1 on errors.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        String targetId = "html";
        Path outDir = null;
        List<SourceFile> sources = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> targetId = args[++i];
                case "--out" -> outDir = Path.of(args[++i]);
                default -> {
                    Path file = Path.of(args[i]);
                    sources.add(new SourceFile(file.toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        }
        if (sources.isEmpty()) {
            System.err.println("usage: jasx-compiler [--target ID] [--out DIR] FILE...");
            System.exit(2);
        }
        ClassLoader loader = Main.class.getClassLoader();
        JasxCompiler compiler = new JasxCompiler(Target.load(targetId, loader), ComponentIndex.fromClasspath(loader));
        CompilationResult result = compiler.compile(sources);
        result.diagnostics().forEach(d -> System.err.println(d.format()));
        for (GeneratedFile file : result.files()) {
            if (outDir == null) {
                System.out.print("// " + file.javaPath() + "\n" + file.content() + "\n");
            } else {
                Path target = outDir.resolve(file.javaPath());
                Files.createDirectories(target.toAbsolutePath().getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            }
        }
        System.exit(result.hasErrors() ? 1 : 0);
    }
}
