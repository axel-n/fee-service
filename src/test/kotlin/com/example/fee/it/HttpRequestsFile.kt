package com.example.fee.it

import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Reads `http/requests.http`, the example requests for the IntelliJ / VS Code HTTP clients, so the tests can run them
 * ([HttpRequestsFileIT]) and check their shape ([HttpRequestsFileTest]).
 *
 * The subset of the format the file uses: `@name = value` variables, `### name` separators, `#` comments, a request
 * line `METHOD URL`, headers up to the first blank line, then the body. Each request has a `# Expect: <status> [json]`
 * comment: the status and, optionally, a JSON object with fields the response body must contain.
 */
object HttpRequestsFile {

    /** One request of the file, variables substituted. [expectedBody] is the JSON text after the status, or `null`. */
    data class Example(
        val name: String,
        val expectedStatus: Int,
        val expectedBody: String?,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    /** The file in the project directory (Surefire's and Failsafe's `basedir`). */
    val path: Path = Path.of(System.getProperty("basedir") ?: ".").resolve("http/requests.http")

    /** Every request of the project's file; [overrides] replace the file's variables (e.g. `host`, `run`). */
    fun load(overrides: Map<String, String> = emptyMap()): List<Example> = parse(path.readText(), overrides)

    /** Every request of [text]. Fails on a request without `# Expect:`, a request line or a known variable. */
    fun parse(text: String, overrides: Map<String, String> = emptyMap()): List<Example> {
        val lines = text.lines()
        val variables = lines.mapNotNull { VARIABLE.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() } + overrides
        val substitute = { raw: String -> substitute(raw, variables) }
        return blocks(lines).map { (name, block) -> example(name, block, substitute) }
    }

    private fun blocks(lines: List<String>): List<Pair<String, List<String>>> {
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        lines.forEach { line ->
            if (line.startsWith(SEPARATOR)) {
                blocks += line.removePrefix(SEPARATOR).trim() to mutableListOf()
            } else {
                blocks.lastOrNull()?.second?.add(line)
            }
        }
        return blocks
    }

    private fun example(name: String, block: List<String>, substitute: (String) -> String): Example {
        val requestLineIndex = block.indexOfFirst { it.isNotBlank() && !isComment(it) && !VARIABLE.matches(it.trim()) }
        require(requestLineIndex >= 0) { "request '$name' has no request line" }
        val expect = block.take(requestLineIndex).firstNotNullOfOrNull { EXPECT.matchEntire(it.trim()) }
            ?: throw IllegalArgumentException("request '$name' has no '# Expect: <status>' line before its request line")
        val (method, url) = block[requestLineIndex].trim().split(Regex("\\s+"), limit = 2)
            .also { require(it.size == 2) { "request '$name': '${block[requestLineIndex]}' is not 'METHOD URL'" } }
        val rest = block.drop(requestLineIndex + 1)
        val headerLines = rest.takeWhile { it.isNotBlank() }
        val headers = headerLines.associate { line ->
            val colon = line.indexOf(':')
            require(colon > 0) { "request '$name': header line '$line' has no ':'" }
            line.substring(0, colon).trim() to substitute(line.substring(colon + 1).trim())
        }
        val body = rest.drop(headerLines.size).joinToString("\n").trim().takeIf { it.isNotEmpty() }
        return Example(
            name = name,
            expectedStatus = expect.groupValues[1].toInt(),
            expectedBody = expect.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let(substitute),
            method = method,
            url = substitute(url),
            headers = headers,
            body = body?.let(substitute),
        )
    }

    private fun isComment(line: String) = line.trimStart().let { it.startsWith("#") || it.startsWith("//") }

    private fun substitute(raw: String, variables: Map<String, String>): String =
        PLACEHOLDER.replace(raw) { match ->
            val name = match.groupValues[1]
            variables[name] ?: throw IllegalArgumentException("unknown variable {{$name}} in '$raw'")
        }

    private const val SEPARATOR = "###"
    private val VARIABLE = Regex("""@(\w+)\s*=\s*(.*)""")
    private val EXPECT = Regex("""#\s*Expect:\s*(\d{3})(.*)""")
    private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
}
