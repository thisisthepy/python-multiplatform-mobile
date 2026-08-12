package org.thisisthepy.python.multiplatform.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.thisisthepy.python.multiplatform.demo.PythonDemo
import org.thisisthepy.python.multiplatform.demo.UpcallDemo

/**
 * Four sections, one per thing the library can do that it could not do when this sample was last
 * touched. Deliberately plain: the interesting part is what each button reaches, not the layout.
 *
 * Every composable in this app lives under this package because `build.gradle.kts` excludes it
 * from the upcall table -- a `@Composable` cannot be called from a generated non-composable
 * lambda.
 */
@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Python Multiplatform", style = MaterialTheme.typography.headlineSmall)

                RuntimeSection()
                EvaluateSection()
                UpcallSection()
                TableSection()
            }
        }
    }
}

/** 1. The interpreter is really up, and it is the build's configured one. */
@Composable
private fun RuntimeSection() {
    DemoCard("1 — embedded interpreter") {
        val summary = remember { runCatching { PythonDemo.runtimeSummary() }.getOrElse { "not started: ${it.message}" } }
        Mono(summary)
    }
}

/** 2. Kotlin builds a Python object, Python computes with it, Kotlin reads the result back. */
@Composable
private fun EvaluateSection() {
    DemoCard("2 — object model round trip") {
        var expression by remember { mutableStateOf(PythonDemo.DEFAULT_EXPRESSION) }
        var result by remember { mutableStateOf("") }

        Text("`kotlin_numbers` is a Python list built from a Kotlin List<Long> by PyList/PyInt.")
        OutlinedTextField(
            value = expression,
            onValueChange = { expression = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { result = PythonDemo.evaluate(expression) }) { Text("eval") }
        if (result.isNotEmpty()) Mono(result)
    }
}

/** 3. The direction that had no example at all until now: Python calling Kotlin. */
@Composable
private fun UpcallSection() {
    DemoCard("3 — Python calls Kotlin (upcall)") {
        var presses by remember { mutableStateOf(0) }
        var answer by remember { mutableStateOf("") }

        if (!UpcallDemo.available) {
            Mono(UpcallDemo.callFromPython())
            return@DemoCard
        }

        Text("Python resolves \"${UpcallDemo.entryName}\" against the generated table, then calls it.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { UpcallDemo.press(); presses += 1 }) { Text("press ($presses)") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { answer = UpcallDemo.callFromPython() }) { Text("ask Python") }
        }
        if (answer.isNotEmpty()) Mono(answer)
    }
}

/** 4. Proof the KSP processor ran, read off the runtime table rather than off the build log. */
@Composable
private fun TableSection() {
    DemoCard("4 — generated function table") {
        Mono(UpcallDemo.tableSummary())
        if (UpcallDemo.available) {
            Mono(
                if (UpcallDemo.optOutHeld()) {
                    "@PythonInternal held: the annotated member is absent from the table"
                } else {
                    "@PythonInternal did NOT hold -- the opt-out is broken"
                },
            )
        }
    }
}

@Composable
private fun DemoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
