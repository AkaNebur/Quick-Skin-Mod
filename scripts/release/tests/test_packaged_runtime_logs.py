from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from e2e.packaged_runtime import RuntimeFailure, scan_runtime_logs


class PackagedRuntimeLogTests(unittest.TestCase):
    def scan(self, content: str) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            log = Path(temporary_directory) / "server.log"
            log.write_text(content, encoding="utf-8")
            scan_runtime_logs([log])

    def test_ignores_only_the_kqueue_debug_appender_recursion(self) -> None:
        self.scan(
            "\n".join(
                [
                    "Server thread ERROR An exception occurred processing Appender DebugFile",
                    "org.apache.logging.log4j.core.appender.AppenderLoggingException: "
                    "java.lang.NoClassDefFoundError: Could not initialize class "
                    "io.netty.channel.kqueue.Native",
                    "\tat org.apache.logging.log4j.core.config.AppenderControl.tryCallAppender",
                    "Caused by: java.lang.NoClassDefFoundError: Could not initialize class "
                    "io.netty.channel.kqueue.Native",
                    "Caused by: java.lang.ExceptionInInitializerError: Exception "
                    "java.lang.IllegalStateException: Only supported on OSX/BSD",
                    "[Server thread/INFO] [minecraft/DedicatedServer]: Done",
                ]
            )
        )

    def test_kqueue_failure_outside_the_debug_appender_remains_fatal(self) -> None:
        with self.assertRaisesRegex(RuntimeFailure, "fatal runtime log evidence"):
            self.scan(
                "java.lang.NoClassDefFoundError: Could not initialize class "
                "io.netty.channel.kqueue.Native\n"
            )

    def test_other_linkage_errors_in_the_same_appender_stack_remain_fatal(self) -> None:
        with self.assertRaisesRegex(RuntimeFailure, "com.quickskin.mod.MissingClass"):
            self.scan(
                "\n".join(
                    [
                        "Server thread ERROR An exception occurred processing Appender DebugFile",
                        "org.apache.logging.log4j.core.appender.AppenderLoggingException: "
                        "java.lang.NoClassDefFoundError: Could not initialize class "
                        "io.netty.channel.kqueue.Native",
                        "Caused by: java.lang.NoClassDefFoundError: "
                        "com.quickskin.mod.MissingClass",
                        "Caused by: java.lang.ExceptionInInitializerError: Exception "
                        "java.lang.IllegalStateException: Only supported on OSX/BSD",
                    ]
                )
            )

    def test_kqueue_appender_error_without_platform_cause_remains_fatal(self) -> None:
        with self.assertRaisesRegex(RuntimeFailure, "fatal runtime log evidence"):
            self.scan(
                "\n".join(
                    [
                        "Server thread ERROR An exception occurred processing Appender DebugFile",
                        "org.apache.logging.log4j.core.appender.AppenderLoggingException: "
                        "java.lang.NoClassDefFoundError: Could not initialize class "
                        "io.netty.channel.kqueue.Native",
                    ]
                )
            )


if __name__ == "__main__":
    unittest.main()
