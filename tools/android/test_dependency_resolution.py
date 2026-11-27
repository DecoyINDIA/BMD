"""Meaningful safety checks for the dependency-only retry classifier."""
import unittest
from run_dependency_resolution import classify


class TransportClassifierTest(unittest.TestCase):
    # From the actual CI Maven Central failure on 2026-11-26.
    TRANSPORT = """A problem occurred configuring root project 'BlackMarlinAndroid'.
Could not resolve all artifacts for configuration 'classpath'.
Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
"""

    def test_real_dependency_429_is_retryable(self):
        self.assertTrue(classify(self.TRANSPORT)[0])

    def test_network_errors_without_resolution_context_fail(self):
        self.assertFalse(classify("HTTP 429 in application response")[0])

    def test_not_found_is_not_transient(self):
        self.assertFalse(classify(self.TRANSPORT.replace("429", "404"))[0])

    def test_approved_transport_failures(self):
        for failure in ("HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504", "Read timed out", "Connection reset"):
            with self.subTest(failure=failure):
                self.assertTrue(classify("Could not get resource. " + failure)[0])

    def test_product_and_integrity_failures_always_win(self):
        failures = (
            "PublicationManagerTest > classMethod FAILED\njava.lang.AssertionError: Failed to fetch maven artifact",
            "48 tests completed, 1 failed",
            "org.opentest4j.AssertionFailedError: wrong bytes",
            "org.junit.ComparisonFailure: mismatch",
            "e: SomeSource.kt:42: Unresolved reference: broken",
            "Compilation failed to complete",
            "ERROR: R8: Missing class com.example.RealDependency",
            "Lint found 2 errors, 47 warnings.",
            "Error: requires API 30 [NewApi]",
            "Artifact checksum verification failed",
            "SHA-256 digest mismatch",
            "Invalid signature on artifact",
            "Signature verification error",
            "Checksum error",
            "DOES NOT VERIFY",
            "javax.net.ssl.SSLHandshakeException: certificate untrusted",
            "> Task :app:testDebugUnitTest",
            "> Task :app:compileReleaseKotlin UP-TO-DATE",
            "> Task :release-check:minifyReleaseWithR8",
        )
        for failure in failures:
            with self.subTest(failure=failure):
                self.assertFalse(classify(failure)[0])
                self.assertFalse(classify(self.TRANSPORT + failure)[0])


if __name__ == "__main__":
    unittest.main()
