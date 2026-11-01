package ru.fitprogress.ai

/** Runs the exact JUnit @Test methods without Gradle or the optional Hamcrest runner. */
fun main() {
    val instance = DomainTest()
    val tests = DomainTest::class.java.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
    var failed = 0
    tests.forEach { method ->
        try { method.invoke(instance); println("PASS ${method.name}") }
        catch(error: java.lang.reflect.InvocationTargetException) { failed++; System.err.println("FAIL ${method.name}: ${error.cause}"); error.cause?.printStackTrace() }
    }
    println("${tests.size} tests, ${tests.size - failed} passed, $failed failed")
    check(failed == 0) { "Domain tests failed" }
}
