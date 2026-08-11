package python.multiplatform.overhead

import kotlin.time.TimeSource
import kotlin.time.measureTime

object Benchmark {
    private val results = mutableListOf<BenchmarkResult>()

    data class BenchmarkResult(
        val name: String,
        val iterations: Int,
        val totalTimeNs: Long,
        val nsPerOp: Double
    )

    fun run(
        name: String,
        warmupIterations: Int = 1000,
        iterations: Int = 100_000,
        block: () -> Unit
    ) {
        // Warmup
        for (i in 0 until warmupIterations) {
            block()
        }

        // Measure
        val time = measureTime {
            for (i in 0 until iterations) {
                block()
            }
        }
        
        val totalNs = time.inWholeNanoseconds
        val nsPerOp = totalNs.toDouble() / iterations
        
        results.add(BenchmarkResult(name, iterations, totalNs, nsPerOp))
    }

    fun printReport() {
        if (results.isEmpty()) return

        println("\n--- Benchmark Report ---")
        val nameWidth = results.maxOfOrNull { it.name.length }?.coerceAtLeast(30) ?: 30
        
        println("Operation".padEnd(nameWidth) + " | " + "Iterations".padStart(10) + " | " + "ns / op".padStart(15))
        println("-".repeat(nameWidth + 31))

        for (result in results) {
            val nsStr = result.nsPerOp.toString()
            val formattedNs = if (nsStr.contains(".")) {
                val parts = nsStr.split(".")
                parts[0] + "." + parts[1].take(2).padEnd(2, '0')
            } else {
                "$nsStr.00"
            }
            println(result.name.padEnd(nameWidth) + " | " + result.iterations.toString().padStart(10) + " | " + formattedNs.padStart(15))
        }
        println("-".repeat(nameWidth + 31))
        println()
        results.clear()
    }
}
