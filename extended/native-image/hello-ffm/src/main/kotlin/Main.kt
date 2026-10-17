package ffm

import java.io.File
import kotlin.system.exitProcess
import org.thisisthepy.sample.ffm.NativeCalls

/**
 * Calls C through ffm-lib and reads a file staged beside the executable. Prints one line per
 * check and "ffm check ok" when every one held; exits with 1 at the first that did not.
 */
fun main() {
    fun fail(message: String): Nothing {
        System.err.println("ffm check failed: $message")
        exitProcess(1)
    }

    if (!NativeCalls.nativeAccessEnabled()) fail("native access is not enabled for the unnamed module")
    println("ffm: native access enabled for the unnamed module")

    val pid = NativeCalls.pid()
    if (pid != ProcessHandle.current().pid().toInt()) fail("getpid() returned $pid, the process is ${ProcessHandle.current().pid()}")
    println("ffm: getpid() = $pid")

    val text = "반갑습니다, native image"
    val length = NativeCalls.length(text)
    val expected = text.toByteArray(Charsets.UTF_8).size.toLong()
    if (length != expected) fail("strlen returned $length for $expected UTF-8 bytes")
    println("ffm: strlen = $length")

    val sorted = NativeCalls.sort(intArrayOf(5, 3, 9, 1, 7)).toList()
    if (sorted != listOf(1, 3, 5, 7, 9)) fail("qsort with a Java comparator gave $sorted")
    println("ffm: qsort through an upcall = $sorted")

    val executable = ProcessHandle.current().info().command().orElse(null)
        ?: fail("the process does not know its own executable's path")
    val marker = File(executable).absoluteFile.parentFile.resolve("prefix/lib/marker.txt")
    if (!marker.isFile) fail("$marker is not beside the executable")
    println("ffm: beside the executable: ${marker.readText().trim()}")

    println("ffm check ok")
}
