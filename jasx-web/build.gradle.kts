// The web target (jasx-spec/targets/web.md): server-driven pages whose dynamic positions update in the
// browser when state changes. Runtime (jasx.web), the target's lowering (jasx.web.compiler), the browser
// client (jasx/web/web.js), a server on the JDK's HTTP server (jasx.web.server), and attribute builders
// generated from the html target's schema.

import groovy.json.JsonSlurper

plugins {
    `java-library`
}

dependencies {
    api(project(":jasx-runtime"))
    // Reactive values and typed events, shared with other interactive targets (jasx-spec/ui.md).
    api(project(":jasx-ui"))
    // The lowering runs inside the compiler, which provides these classes (semantics.md §7.4).
    compileOnly(project(":jasx-compiler"))
    testImplementation(project(":jasx-compiler"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The schema is the html target's (targets/web.md §1), copied in as schemas/web.json so there is one source.
val htmlSchema = project(":jasx-target-html").file("src/main/resources/schemas/html.json")

tasks.processResources {
    from(htmlSchema) {
        into("schemas")
        rename { "web.json" }
    }
}

val generatedDir = layout.buildDirectory.dir("generated/sources/attrs/java/main")

/**
 * The kind of event each event attribute gives its handler (jasx-spec/ui.md §4); the others give a plain
 * jasx.ui.Event. Keep it in step with the table in jasx.ui.Event.
 */
val eventKinds: Map<String, String> = buildMap {
    listOf("input", "beforeinput", "change", "select", "invalid", "focus", "blur").forEach { put(it, "InputEvent") }
    listOf("keydown", "keyup", "keypress").forEach { put(it, "KeyEvent") }
    listOf("click", "dblclick", "auxclick", "contextmenu", "mousedown", "mouseup", "mousemove", "mouseover",
        "mouseout", "mouseenter", "mouseleave", "wheel", "drag", "dragstart", "dragend", "dragenter",
        "dragleave", "dragover", "drop").forEach { put(it, "MouseEvent") }
    put("submit", "FormEvent")
}

/**
 * Generates one attribute builder per element (targets/web.md §2): each attribute has a static overload and
 * a Supplier overload for its dynamic form; each event attribute takes inline JavaScript, a jasx.ui.Action, or
 * a jasx.ui.Handler of its kind of event.
 */
val generateAttributeBuilders = tasks.register("generateAttributeBuilders") {
    inputs.file(htmlSchema)
    inputs.property("eventKinds", eventKinds.toSortedMap().toString())
    outputs.dir(generatedDir)
    doLast {
        val out = generatedDir.get().asFile.resolve("jasx/web/attrs")
        out.deleteRecursively()
        out.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val schema = JsonSlurper().parse(htmlSchema) as Map<String, Any?>
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

        /** The static and the dynamic overload of one attribute. */
        fun methods(self: String, name: String, spec: Map<String, Any?>): String {
            val methodName = spec["method"] as String? ?: camel(name)
            val isBoolean = spec["type"] == "boolean"
            val staticType = if (isBoolean) "boolean" else "java.lang.Object"
            val dynamicType = if (isBoolean) "java.util.function.Supplier<java.lang.Boolean>" else "java.util.function.Supplier<?>"
            return "    public $self $methodName($staticType value) {\n        return set(\"$name\", value);\n    }\n\n" +
                "    public $self $methodName($dynamicType value) {\n        return set(\"$name\", value);\n    }\n\n"
        }
        val header = "// Generated from schemas/web.json by :jasx-web:generateAttributeBuilders. Do not edit.\n" +
            "package jasx.web.attrs;\n\n"

        val base = StringBuilder(header)
        base.append("/** Global and event attributes of every element. */\n")
        base.append("public abstract class WebAttributes<T extends WebAttributes<T>> extends jasx.web.Attributes<T> {\n")
        base.append("    protected WebAttributes() {}\n\n")
        globals.toSortedMap().forEach { (name, spec) -> base.append(methods("T", name, spec)) }
        events.sorted().forEach { name ->
            val kind = "jasx.ui." + (eventKinds[name.removePrefix("on")] ?: "Event")
            base.append("    public T $name(java.lang.String javaScript) {\n        return set(\"$name\", javaScript);\n    }\n\n")
            base.append("    public T $name(jasx.ui.Action action) {\n        return set(\"$name\", action);\n    }\n\n")
            base.append("    public T $name(jasx.ui.Handler<? super $kind> handler) {\n        return set(\"$name\", handler);\n    }\n\n")
        }
        base.append("}\n")
        out.resolve("WebAttributes.java").writeText(base.toString())

        fun builder(className: String, doc: String, attributes: Map<String, Map<String, Any?>>) {
            val code = StringBuilder(header)
            code.append("/** $doc */\n")
            code.append("public final class $className extends WebAttributes<$className> {\n")
            code.append("    private $className() {}\n\n")
            code.append("    public static $className create() {\n        return new $className();\n    }\n\n")
            attributes.toSortedMap().filterKeys { it !in globals }.forEach { (name, spec) ->
                code.append(methods(className, name, spec))
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
