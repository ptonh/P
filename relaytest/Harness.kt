package relaytest

import java.security.MessageDigest

var failCount = 0
var passCount = 0
var onlyFilter: String? = null

/** 跑一个用例：抛异常 / check 失败 → FAIL，否则 PASS。 */
fun case(name: String, body: () -> Unit) {
    val f = onlyFilter
    if (f != null && !name.contains(f)) return
    val t0 = System.currentTimeMillis()
    try {
        body()
        passCount++
        println("PASS $name (${System.currentTimeMillis() - t0}ms)")
    } catch (e: Throwable) {
        failCount++
        println("FAIL $name: ${e.message ?: e.javaClass.simpleName}")
        if (e !is CheckFailed) e.printStackTrace(System.out)
    }
}

class CheckFailed(msg: String) : Exception(msg)

fun check(cond: Boolean, msg: () -> String) { if (!cond) throw CheckFailed(msg()) }

fun hexOf(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }
fun sha256() = MessageDigest.getInstance("SHA-256")

fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
    return cond()
}
