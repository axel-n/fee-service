package com.example.fee

import java.nio.file.Path

/**
 * Where the tests find the project's files. Surefire and Failsafe run them in the service module's directory (their
 * `basedir`, also an IDE's default); the api module sits in the root above it.
 */
object ProjectLayout {

    /** The service module: `service/`. */
    val serviceDir: Path = Path.of(System.getProperty("basedir") ?: ".").toAbsolutePath().normalize()

    /** The project root, the parent of both modules. */
    val rootDir: Path = checkNotNull(serviceDir.parent) { "the service module $serviceDir has no parent directory" }

    /** The api module: `api/`. */
    val apiDir: Path = rootDir.resolve("api")
}
