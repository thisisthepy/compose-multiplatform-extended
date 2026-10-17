package hello

fun main(args: Array<String>) {
    println("hello from Kotlin/Native desktop")
    if (args.contains("--self-check")) println("self-check ok")
}
