package relaytest

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contains("--big")) {
        try { bigTransferInChild(); println("BIG-OK"); return } catch (e: Throwable) { println("BIG-FAIL ${e.message ?: e}"); exitProcess(1) }
    }
    for (a in args) if (a.startsWith("--only=")) onlyFilter = a.removePrefix("--only=")
    val f = onlyFilter
    if (f == null || f.startsWith("STUN") || f.startsWith("TURN") || f.startsWith("pick")) { stunTests(); turnTests() }
    rudpBasicTests()
    rudpSpecTests()
    rudpMoreTests()
    relayTests()
    bigTransferTest()
    println("RESULT: pass=$passCount fail=$failCount")
    if (failCount > 0) exitProcess(1)
}
