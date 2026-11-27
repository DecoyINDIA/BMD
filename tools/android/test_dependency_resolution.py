"""Meaningful safety checks for the dependency-only retry classifier."""
import unittest
from run_dependency_resolution import classify, gradle_wrapper


class TransportClassifierTest(unittest.TestCase):
    # From the actual CI Maven Central failure on 2026-11-26.
    TRANSPORT = """A problem occurred configuring root project 'BlackMarlinAndroid'.
Could not resolve all artifacts for configuration 'classpath'.
Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
"""

    def test_real_dependency_429_is_retryable(self):
        self.assertTrue(classify(self.TRANSPORT)[0])

    # From the actual CI failure of run 45846587660 (2026-11-26): Maven Central's rate limit on
    # an artifact whose name holds "pkix" was taken for a certificate failure and not retried.
    BOUNCY_CASTLE_429 = """* What went wrong:
Could not resolve all artifacts for configuration 'classpath'.
   > Could not resolve org.bouncycastle:bcpkix-jdk18on:1.79.
      > Could not get resource 'https://plugins.gradle.org/m2/org/bouncycastle/bcpkix-jdk18on/1.79/bcpkix-jdk18on-1.79.pom'.
      > Could not get resource 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.79/bcpkix-jdk18on-1.79.pom'.
         > Could not GET 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.79/bcpkix-jdk18on-1.79.pom'. Received status code 429 from server: Too Many Requests
"""

    def test_artifact_names_are_not_diagnostics(self):
        self.assertEqual(classify(self.BOUNCY_CASTLE_429), (True, "dependency transport failure"))
        # Other rule words inside coordinates or URLs don't decide either.
        for name in ("commons-digest-error", "signature-failed-lib", "hash-invalid", "certificate-failed-kit"):
            with self.subTest(name=name):
                log = self.BOUNCY_CASTLE_429.replace("bcpkix-jdk18on", name)
                self.assertTrue(classify(log)[0])

    def test_certificate_diagnostics_still_deny(self):
        for failure in (
            "sun.security.validator.ValidatorException: PKIX path building failed",
            "PKIX path validation failed: java.security.cert.CertPathValidatorException",
            "unable to find valid certification path to requested target",
            "javax.net.ssl.SSLHandshakeException: Received fatal alert",
            "The server certificate has expired",
        ):
            with self.subTest(failure=failure):
                self.assertEqual(classify(self.BOUNCY_CASTLE_429 + failure), (False, "integrity/trust failure"))

    def test_task_paths_are_not_taken_for_names(self):
        for task in ("> Task :app:minifyReleaseWithR8", "> Task :a:b:compileKotlin",
                     "Execution failed for task ':app:lintRelease'."):
            with self.subTest(task=task):
                self.assertFalse(classify(self.TRANSPORT + task)[0])

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


class GradleWrapperTest(unittest.TestCase):
    def test_each_system_gets_a_wrapper_it_can_start(self):
        self.assertEqual(gradle_wrapper("win32").name, "gradlew.bat")
        for platform in ("linux", "darwin"):
            with self.subTest(platform=platform):
                self.assertEqual(gradle_wrapper(platform).name, "gradlew")
        for platform in ("win32", "linux"):
            with self.subTest(exists=platform):
                self.assertTrue(gradle_wrapper(platform).is_file())


if __name__ == "__main__":
    unittest.main()
