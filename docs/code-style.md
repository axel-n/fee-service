# fee-service — Code style

How the code is written. What is built, and the rules it follows (hard rules, architecture, Restate, tests), is in
[`../AGENTS.md`](../AGENTS.md) and [`design.md`](design.md). Only formatting is checked by a tool (ktlint, in every
build). Everything else here is followed by whoever writes the code, and checked in review.

## Money

- Money is `BigDecimal` wherever it appears: amounts, rates, minimums, fees, in every type and calculation. Never
  `Double` or `Float`, and no `toDouble()` / `toFloat()` on the way.
- Create values from a `String`: `BigDecimal("0.0015")`, never `BigDecimal(0.0015)`, which holds the binary fraction
  nearest to 0.0015 and not 0.0015 (a whole constant: see number literals below).
- Round only with `RoundingMode.HALF_EVEN`, at the asset's decimals.
- Compare with `compareTo`, not `==`: `BigDecimal("1.5") == BigDecimal("1.50")` is `false`.

## Kotlin

- Code is ktlint-clean. Run `mvn ktlint:format`, then fix by hand what it can't, e.g. a file must be named after its
  single class (a test class takes its file's name, `FeeRuleTest`), and a KDoc can't be followed by a `//` comment.
- Idiomatic Kotlin: `val` over `var`, data classes for values, sealed types for closed hierarchies, no `!!`, no `lateinit`
  in production code.
- Number literals of 1000 or more are grouped by thousands with underscores, in main and test code: `1_000`, `65_535`,
  `3_000_000_000L`. Strings can't take underscores (JSON, `BigDecimal("…")`), so a whole `BigDecimal` constant is written
  `3_000_000_000.toBigDecimal()`; a fraction stays `BigDecimal("0.0015")`. Ports are grouped too (`9_080`); only a year
  in a date stays as written (`LocalDateTime.of(2023, 8, 30, …)`).

## Spring, time and configuration

- Constructor injection only in production code: no `@Autowired`, no field injection. Every dependency should be mockable or replaceable.
- Inject `Clock` in production code: never `Instant.now()`, `LocalDateTime.now()` or `System.currentTimeMillis()` without one.
- Tunable values (fee numbers, URLs, ports, timeouts) come from `application.yml` through `@ConfigurationProperties` classes and are
  never hardcoded. Defaults live only in the packaged `application.yml`. Renaming a key breaks existing override files and
  environment variables; call it out.
- **Every `@Configuration` and every `@ConfigurationProperties` class lives in `config`**, never next to the bean it defines
  or that uses it. Properties classes: one file per top-level section of `application.yml`.

## Layout, SQL, JSON and errors

- **SQL is a named constant**: each statement is a `private const val` (a raw string for more than one line) in the
  companion object of the class that runs it, with named bind parameters (`:id`), never a string glued together at the
  call. Only where an API takes no parameters (Restate's query API) is a quoted value appended to a constant prefix.
- Package and file layout: a package holds one kind of class, and an extension point's implementations get a sub-package
  (`fee.rule`, `fee.formula`, `validation.rule`). A file holds one main type plus the small types and helpers that exist only to
  serve it (its result and field types, its default implementation, internal helpers). Don't create a file for a lone data class,
  enum or helper function that belongs to another type. A test file mirrors its production file, one test class per production class.
- JSON uses snake_case through `@JsonProperty` on DTOs; a `BigDecimal` property carries `@PlainBigDecimal` (a plain number).
  Durable and contract types use only `com.fasterxml.jackson.annotation`, which both Jackson generations read. A databind
  annotation (`@JsonSerialize`, …) is read by one generation only, so it goes into a bundle for both, as `@PlainBigDecimal` does.
- Errors are subclasses of the sealed `FeeException` (api module, each with a `FeeErrorCode`) and are mapped to `ProblemDetail`
  in `ApiExceptionHandler`. A new property of an error goes into `FeeProblem` too, so clients can read it.
- Small classes with one responsibility. Comment only *why*, not *what*. Add KDoc to public extension points (`FeeRule`, `TransactionValidator`).
