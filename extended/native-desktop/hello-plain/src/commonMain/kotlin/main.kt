package hello

class Boom : RuntimeException("thrown on purpose")

private fun deeper(depth: Int) { if (depth == 0) throw Boom() else deeper(depth - 1) }

// A thousand exceptions thrown a few calls down: they only unwind when the toolchain's unwind data survived.
private fun caught(): Int {
    var n = 0
    repeat(1000) { try { deeper(3) } catch (e: Boom) { n++ } }
    return n
}

val startup: String = "kotlin initialised"

fun main(args: Array<String>) {
    println("hello from Kotlin/Native: $startup")
    if (args.contains("--self-check")) {
        val n = caught()
        check(n == 1000) { "caught $n of 1000" }
        println("self-check ok")
    }
}
