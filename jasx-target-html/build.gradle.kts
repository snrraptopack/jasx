// The html reference target (jasx-spec/targets/html.md): descriptor, schema, factory, renderer, and
// attribute builders generated from the schema.

import groovy.json.JsonSlurper

plugins {
    `java-library`
}

dependencies {
    api(project(":jasx-runtime"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val schemaFile = file("src/main/resources/schemas/html.json")
val generatedDir = layout.buildDirectory.dir("generated/sources/attrs/java/main")

/** Generates one typed attribute builder per element in schemas/html.json (semantics.md §6.3). */
val generateAttributeBuilders = tasks.register("generateAttributeBuilders") {
    inputs.file(schemaFile)
    outputs.dir(generatedDir)
    doLast {
        val out = generatedDir.get().asFile.resolve("jasx/html/attrs")
        out.deleteRecursively()
        out.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val schema = JsonSlurper().parse(schemaFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val globals = schema["globalAttributes"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val events = schema["eventAttributes"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val elements = schema["elements"] as Map<String, Map<String, Any?>>

        fun camel(name: String) = name.split('-').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
        fun pascal(tag: String) = camel(tag).replaceFirstChar { it.uppercaseChar() }
        fun method(self: String, name: String, spec: Map<String, Any?>): String {
            val methodName = spec["method"] as String? ?: camel(name)
            val type = if (spec["type"] == "boolean") "boolean" else "java.lang.Object"
            return "    public $self $methodName($type value) {\n        return set(\"$name\", value);\n    }\n"
        }
        val header = "// Generated from schemas/html.json by :jasx-target-html:generateAttributeBuilders. Do not edit.\n" +
            "package jasx.html.attrs;\n\n"

        val base = StringBuilder(header)
        base.append("/** Global and event attributes of every element. */\n")
        base.append("public abstract class HtmlAttributes<T extends HtmlAttributes<T>> extends jasx.html.Attributes<T> {\n")
        base.append("    protected HtmlAttributes() {}\n\n")
        globals.toSortedMap().forEach { (name, spec) -> base.append(method("T", name, spec)) }
        events.sorted().forEach { name ->
            base.append("    public T $name(java.lang.String value) {\n        return set(\"$name\", value);\n    }\n")
        }
        base.append("}\n")
        out.resolve("HtmlAttributes.java").writeText(base.toString())

        fun builder(className: String, doc: String, attributes: Map<String, Map<String, Any?>>) {
            val code = StringBuilder(header)
            code.append("/** $doc */\n")
            code.append("public final class $className extends HtmlAttributes<$className> {\n")
            code.append("    private $className() {}\n\n")
            code.append("    public static $className create() {\n        return new $className();\n    }\n")
            attributes.toSortedMap().filterKeys { it !in globals }.forEach { (name, spec) ->
                code.append(method(className, name, spec))
            }
            code.append("}\n")
            out.resolve("$className.java").writeText(code.toString())
        }
        elements.forEach { (tag, element) ->
            @Suppress("UNCHECKED_CAST")
            val attributes = element["attributes"] as Map<String, Map<String, Any?>>? ?: emptyMap()
            builder(pascal(tag), "Attributes of {@code <$tag>}.", attributes)
        }
        builder("Custom", "Attributes of a custom element (a tag containing {@code -}).", emptyMap())
    }
}

sourceSets.main {
    java.srcDir(generateAttributeBuilders)
}
