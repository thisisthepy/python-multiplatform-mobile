package experiment.app

import experiment.generated.FunctionTable

fun appFunctionOne() = "App1"

fun main() {
    println("Entries discovered: " + FunctionTable.allEntries())
}
