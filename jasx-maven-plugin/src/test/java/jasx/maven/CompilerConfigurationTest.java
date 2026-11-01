package jasx.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.List;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

/** Reading the maven-compiler-plugin's configuration, as the JASX type check needs it. */
class CompilerConfigurationTest {

    private static CompilerConfiguration read(String xml) throws Exception {
        Xpp3Dom configuration = Xpp3DomBuilder.build(new StringReader("<configuration>" + xml + "</configuration>"));
        return CompilerConfiguration.read(configuration);
    }

    @Test
    void annotationProcessorPaths() throws Exception {
        CompilerConfiguration configuration = read("""
                <annotationProcessorPaths>
                  <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path>
                  <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId></path>
                </annotationProcessorPaths>
                """);
        assertEquals(List.of(
                new CompilerConfiguration.ProcessorArtifact("org.projectlombok", "lombok", "1.18.36", null, "jar"),
                new CompilerConfiguration.ProcessorArtifact("org.mapstruct", "mapstruct-processor", null, null, "jar")),
                configuration.processorPaths);
    }

    @Test
    void compilerOptions() throws Exception {
        CompilerConfiguration configuration = read("""
                <compilerArgs><arg>-Amapstruct.defaultComponentModel=spring</arg><arg> -Xlint:all </arg></compilerArgs>
                <compilerArgument>-Akey=value -Aother=x</compilerArgument>
                <parameters>true</parameters>
                <enablePreview>true</enablePreview>
                <proc>full</proc>
                <annotationProcessors><annotationProcessor>a.First</annotationProcessor><annotationProcessor>b.Second</annotationProcessor></annotationProcessors>
                """);
        assertEquals(List.of("-Amapstruct.defaultComponentModel=spring", "-Xlint:all", "-Akey=value", "-Aother=x",
                "-parameters", "--enable-preview", "-proc:full", "-processor", "a.First,b.Second"),
                configuration.compilerArgs);
    }

    @Test
    void noConfigurationIsEmpty() {
        CompilerConfiguration configuration = CompilerConfiguration.read(null);
        assertEquals(List.of(), configuration.processorPaths);
        assertEquals(List.of(), configuration.compilerArgs);
    }

    @Test
    void falseFlagsAddNothing() throws Exception {
        CompilerConfiguration configuration = read("<parameters>false</parameters><enablePreview>false</enablePreview>");
        assertEquals(List.of(), configuration.compilerArgs);
    }
}
