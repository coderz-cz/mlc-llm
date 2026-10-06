package ai.mlc.mlcchat.tools

/**
 * Text protocol that lets the model request shell commands without relying
 * on the engine's native OpenAI tool-calling (unreliable on the small
 * quantized models bundled here). The model emits a fenced block:
 *
 * ```sh exec
 * <command>
 * ```
 *
 * The app extracts it, (optionally) asks the user to approve it, runs it
 * through [TermuxShell], and feeds the captured output back as the next
 * turn, looping until the model stops emitting exec blocks.
 */
object ShellToolProtocol {

    /** Max exec blocks honored for a single user turn, to bound the loop. */
    const val MAX_STEPS = 8

    /**
     * System prompt prepended when shell tools are enabled. Kept compact:
     * context windows here are only 4096 tokens.
     */
    val SYSTEM_PROMPT = """
You can run shell commands on this Android device through Termux (a full
Linux environment with bash, python, pip, git, etc.). To run a command,
reply with EXACTLY one fenced block in this form and nothing else after it:

```sh exec
<your command here>
```

Rules:
- Emit at most ONE exec block per reply. Stop generating right after it.
- The command runs via `bash -c`. Use normal shell syntax; chain with && or
  write a short script with here-docs if you need multiple steps.
- You will receive the command's stdout, stderr and exit code as the next
  message. Use that output to decide your next step.
- Only use exec blocks when actually running code or inspecting the system.
  For plain explanations, answer normally without an exec block.
- When you are done and have a final answer, reply normally WITHOUT an exec
  block.
Example — to test a Python snippet:
```sh exec
python3 -c "print(sum(range(10)))"
```
""".trimIndent()

    private val EXEC_BLOCK = Regex(
        // ```sh exec  (also tolerates ```shell exec / ```bash exec / ```exec)
        "```(?:sh|shell|bash)?\\s*exec\\s*\\r?\\n(.*?)```",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /** Returns the command from the first exec block in [text], or null. */
    fun extractCommand(text: String): String? {
        val m = EXEC_BLOCK.find(text) ?: return null
        return m.groupValues[1].trim().ifEmpty { null }
    }

    fun hasExecBlock(text: String): Boolean = EXEC_BLOCK.containsMatchIn(text)

    /**
     * Formats a [TermuxShell.Result] into the text fed back to the model as
     * the next user turn. Truncated so a runaway command can't blow the
     * context window.
     */
    fun formatResultForModel(result: TermuxShell.Result, maxChars: Int = 4000): String {
        if (result.isBridgeError) {
            return "The command could not be run: ${result.bridgeError}"
        }
        val sb = StringBuilder()
        sb.append("Command finished (exit code ${result.exitCode ?: "unknown"}).\n")
        val out = result.stdout.trim()
        val err = result.stderr.trim()
        if (out.isNotEmpty()) {
            sb.append("\nstdout:\n").append(truncate(out, maxChars))
        }
        if (err.isNotEmpty()) {
            sb.append("\nstderr:\n").append(truncate(err, maxChars))
        }
        if (out.isEmpty() && err.isEmpty()) {
            sb.append("\n(no output)")
        }
        return sb.toString()
    }

    private fun truncate(s: String, maxChars: Int): String {
        if (s.length <= maxChars) return s
        val head = s.take(maxChars / 2)
        val tail = s.takeLast(maxChars / 2)
        val omitted = s.length - maxChars
        return "$head\n…[${omitted} chars truncated]…\n$tail"
    }
}
