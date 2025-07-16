package xyz.block.trailblaze.agent.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.JsonSerializationUtil
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.util.UUID
import kotlin.reflect.KClass

class MixedModeTestCase(
  yamlContent: String,
  private val executeRecordedSteps: Boolean = true,
  private val additionalTrailblazeTools: List<KClass<out TrailblazeTool>> = emptyList(),
) : TestCase(yamlContent) {
  private val allPossibleTools: Set<KClass<out TrailblazeTool>> =
    TrailblazeToolSet.AllBuiltInTrailblazeTools + additionalTrailblazeTools

  // New property to hold context string if present
  val contextString: String?

  override val objectives: List<TestObjective>

  init {
    // Always parse YAML as a list
    val yaml = Yaml()
    val parsedYaml = yaml.load<Any>(instructions)
    if (parsedYaml !is List<*>) {
      throw TrailblazeException("YAML must be a list")
    }
    val list = parsedYaml
    if (list.isNotEmpty() && list[0] is Map<*, *>) {
      val firstMap = list[0] as Map<*, *>
      if (firstMap.size == 1 && firstMap.containsKey("context")) {
        contextString = firstMap["context"]?.toString()
        objectives = parseObjectivesFromYaml(list.drop(1))
      } else {
        contextString = null
        objectives = parseObjectivesFromYaml(list)
      }
    } else {
      contextString = null
      objectives = parseObjectivesFromYaml(list)
    }
  }

  private fun parseObjectivesFromYaml(yamlObj: Any?): List<TestObjective> {
    if (yamlObj !is List<*>) {
      throw TrailblazeException("Objectives must be a list")
    }
    return yamlObj.map { command ->
      println("### Parsing command from yaml $command")
      when (command) {
        is Map<*, *> -> {
          // Handle Map commands (most common for YAML blocks)
          val key = command.keys.firstOrNull()?.toString()
          val value = command.values.firstOrNull()

          // Handle special commands
          with(key) {
            when {
              isTrailblazeCommand() -> parseTrailblazePrompt(value)
              isRememberTextCommand() -> parseRememberTextCommand(value)
              isRememberNumberCommand() -> parseRememberNumberCommand(value)
              isRememberWithAiCommand() -> parseRememberWithAiCommand(value)
              isAssertEqualsCommand() -> parseAssertEqualsCommand(value)
              isAssertNotEqualsCommand() -> parseAssertNotEqualsCommand(value)
              isAssertWithAiCommand() -> parseAssertWithAiCommand(value)
              isAssertMathCommand() -> parseAssertMathCommand(value)
              isMaestroCommand() -> parseMaestroCommand(this!!, value)
              else -> parseTrailblazePrompt(listOf("$key ${value ?: ""}"))
            }
          }
        }

        else -> {
          val commandStr = command?.toString()?.trim()
          if (commandStr.isNoParamMaestroCommand()) {
            parseMaestroCommand(commandStr!!, null)
          } else {
            parseTrailblazePrompt(listOf(command))
          }
        }
      }
    }
  }

  // TODO: Many, many unit tests for this
  private fun parseTrailblazePrompt(
    value: Any?,
  ): TestObjective.TrailblazeObjective = when (value) {
    is List<*> -> parseTrailblazePromptSteps(value)
    is String -> parseSinglePrompt(value)
    else -> throw TrailblazeException("No objectives found in Trailblaze objective: $value")
  }

  private fun parseTrailblazePromptSteps(
    objectives: List<*>,
  ): TestObjective.TrailblazeObjective = if (shouldParseCommands(objectives)) {
    println("### parsing trailblaze commands")
    parseTrailblazeCommands(objectives)
  } else {
    println("### parsing full prompt with prompt steps")
    // If all items are strings, treat each as a step, even if multi-line
    val allStrings = objectives.all { it is String }
    if (allStrings) {
      val stringList = objectives.filterIsInstance<String>()
      val fullInstructions = stringList.joinToString("\n")
      val promptSteps = generatePromptStepsFromList(stringList, fullInstructions)
      TestObjective.TrailblazeObjective.TrailblazePrompt(fullInstructions, promptSteps)
    } else {
      val fullInstructions = parseFullPrompt(objectives, useOnlyKeys = !executeRecordedSteps)
      val promptSteps = generatePromptStepsFromString(fullInstructions)
      TestObjective.TrailblazeObjective.TrailblazePrompt(fullInstructions, promptSteps)
    }
  }

  // Accepts a list of strings, each string is a step (even if multi-line)
  private fun generatePromptStepsFromList(steps: List<String>, fullPrompt: String): List<TrailblazePromptStep> {
    val taskId = UUID.randomUUID().toString()
    return steps.mapIndexed { index, step ->
      TrailblazePromptStep(
        description = step,
        taskId = taskId,
        taskIndex = index,
        fullPrompt = fullPrompt,
      )
    }
  }

  // Accepts a single string, splits by lines (legacy/compat)
  private fun generatePromptStepsFromString(stepInstructions: String): List<TrailblazePromptStep> {
    val taskId = UUID.randomUUID().toString()
    return stepInstructions.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapIndexed { index, line ->
        TrailblazePromptStep(
          description = line,
          taskId = taskId,
          taskIndex = index,
          fullPrompt = stepInstructions,
        )
      }
  }

  private fun parseSinglePrompt(instructions: String): TestObjective.TrailblazeObjective = TestObjective.TrailblazeObjective.TrailblazePrompt(
    fullPrompt = instructions,
    steps = listOf(
      TrailblazePromptStep(
        description = instructions,
      ),
    ),
  )

  private fun shouldParseCommands(objectives: List<*>): Boolean = objectives.all { it is Map<*, *> } and executeRecordedSteps

  private fun parseTrailblazeCommands(objectives: List<*>): TestObjective.TrailblazeObjective.TrailblazeCommand {
    val staticObjectives = objectives.map { obj ->
      val objMap = obj as? Map<*, *>
        ?: throw TrailblazeException("Cannot parse objectives from invalid type $obj")

      val objValue = objMap.values.firstOrNull()
      val steps: List<*> = objValue as? List<*>
        ?: throw TrailblazeException("Cannot parse trailblaze commands from empty steps")
      val desc = objMap.keys.firstOrNull()?.toString() ?: ""
      println("[MixedModeExecutor] Executing steps for objective: $desc")

      val tools = steps.map { parseTrailblazeToolsForStep(it) }
      StaticObjective(tools)
    }
    return TestObjective.TrailblazeObjective.TrailblazeCommand(staticObjectives)
  }

  private fun parseTrailblazeToolsForStep(step: Any?): TrailblazeTool {
    val stepMap = step as? Map<*, *>
      ?: throw TrailblazeException("Cannot parse Trailblaze tools for invalid step type $step")
    val toolName = stepMap.keys.firstOrNull()?.toString()
      ?: throw TrailblazeException("Unknown tool name provided for $step")
    val params = stepMap.values.firstOrNull() as? Map<*, *> ?: emptyMap<String, Any?>()
    val toolClass = allPossibleTools.firstOrNull { toolKClass ->
      toolKClass.toKoogToolDescriptor().name == toolName
    } ?: throw TrailblazeException("Could not find TrailblazeTool that matches tool name $toolName")
    val jsonParams = buildJsonObject {
      params.forEach { (k, v) ->
        when (v) {
          is Boolean -> put(k.toString(), JsonPrimitive(v))
          is Number -> put(k.toString(), JsonPrimitive(v))
          is String -> put(k.toString(), JsonPrimitive(v))
          else -> put(k.toString(), JsonPrimitive(v.toString()))
        }
      }
    }
    return try {
      JsonSerializationUtil.deserializeTrailblazeTool(toolClass, jsonParams)
    } catch (e: Exception) {
      throw TrailblazeException("Failed to deserialize tool: $toolName with params $params: \" + e.message")
    }
  }

  private fun parseFullPrompt(objectives: List<*>, useOnlyKeys: Boolean = false): String {
    // Combine all descriptions into a single objective separated by newlines
    val fullInstructions = objectives.mapNotNull {
      when (it) {
        is String -> it
        is Map<*, *> -> {
          val key = it.keys.firstOrNull()?.toString() ?: ""
          if (useOnlyKeys) key else "$key: ${it.values.firstOrNull()?.toString() ?: ""}"
        }
        else -> null
      }
    }.joinToString("\n")
    if (fullInstructions.isBlank()) {
      throw TrailblazeException("Cannot parse objectives from empty instructions.")
    }
    return fullInstructions
  }

  private fun parseRememberTextCommand(value: Any?): TestObjective.RememberTextCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberText command: missing prompt or variable name")
    }

    TestObjective.RememberTextCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberText command format")
  }

  private fun parseRememberNumberCommand(value: Any?): TestObjective.RememberNumberCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberNumber command: missing prompt or variable name")
    }

    TestObjective.RememberNumberCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberNumber command format")
  }

  private fun parseRememberWithAiCommand(value: Any?): TestObjective.RememberWithAiCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberWithAI command: missing prompt or variable name")
    }
    TestObjective.RememberWithAiCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberWithAI command format")
  }

  private fun parseAssertEqualsCommand(value: Any?): TestObjective.AssertEqualsCommand = if (value is Map<*, *>) {
    val actual = value["actual"]?.toString()
    val expected = value["expected"]?.toString()

    if (actual == null || expected == null) {
      throw TrailblazeException("Invalid assertEquals command: missing actual or expected name")
    }

    TestObjective.AssertEqualsCommand(actual, expected)
  } else {
    throw TrailblazeException("Invalid assertEquals command format")
  }

  private fun parseAssertNotEqualsCommand(value: Any?): TestObjective.AssertNotEqualsCommand = if (value is Map<*, *>) {
    val actual = value["actual"]?.toString()
    val expected = value["expected"]?.toString()

    if (actual == null || expected == null) {
      throw TrailblazeException("Invalid assertNotEquals command: missing actual or expected name")
    }

    TestObjective.AssertNotEqualsCommand(actual, expected)
  } else {
    throw TrailblazeException("Invalid assertNotEquals command format")
  }

  private fun parseAssertWithAiCommand(value: Any?): TestObjective.AssertWithAiCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
      ?: throw TrailblazeException("Invalid assertWithAi command: missing prompt")

    TestObjective.AssertWithAiCommand(prompt)
  } else {
    throw TrailblazeException("Invalid assertWithAi command")
  }

  private fun parseAssertMathCommand(value: Any?): TestObjective.AssertMathCommand = if (value is Map<*, *>) {
    val expression = value["expression"]?.toString()
    val expected = value["expected"]?.toString()

    if (expression == null || expected == null) {
      throw TrailblazeException("Invalid assertMath command: missing expression or expected values")
    }

    TestObjective.AssertMathCommand(expression, expected)
  } else {
    throw TrailblazeException("Invalid assertMath command")
  }

  private fun parseMaestroCommand(key: String, value: Any?): TestObjective.MaestroCommand {
    val maestroCommand = when (value) {
      null -> "- $key"
      is Map<*, *> -> {
        // Serialize as inline YAML object (flow style)
        val options = DumperOptions().apply {
          defaultFlowStyle = DumperOptions.FlowStyle.FLOW
          indent = 2
        }
        val yaml = Yaml(options)
        val inline = yaml.dump(value).trim()
        "- $key: $inline"
      }

      else -> "- $key: $value"
    }

    return TestObjective.MaestroCommand(maestroCommand)
  }

  companion object {
    private val trailblazeCommands = setOf(
      "tb",
      "trailblaze",
      "🧭",
      "run",
    )
    private val noParamMaestroCommands = setOf(
      "scroll",
      "back",
      "hideKeyboard",
      "waitForAnimationToEnd",
    )
    private val maestroCommands = setOf(
      "addMedia",
      "assertVisible",
      "assertNotVisible",
      "assertTrue",
      "assertNoDefectsWithAi",
      "back",
      "clearKeychain",
      "clearState",
      "copyTextFrom",
      "evalScript",
      "eraseText",
      "extendedWaitUntil",
      "hideKeyboard",
      "inputText",
      "killApp",
      "launchApp",
      "openLink",
      "pressKey",
      "pasteText",
      "repeat",
      "retry",
      "runFlow",
      "runScript",
      "scroll",
      "scrollUntilVisible",
      "setAirplaneMode",
      "setLocation",
      "startRecording",
      "stopApp",
      "stopRecording",
      "swipe",
      "takeScreenshot",
      "toggleAirplaneMode",
      "tapOn",
      "doubleTapOn",
      "longPressOn",
      "travel",
      "waitForAnimationToEnd",
    )

    private fun String?.isTrailblazeCommand(): Boolean = this != null && trailblazeCommands.contains(this)
    private fun String?.isRememberTextCommand(): Boolean = "rememberText" == this
    private fun String?.isRememberNumberCommand(): Boolean = "rememberNumber" == this
    private fun String?.isRememberWithAiCommand(): Boolean = "rememberWithAI" == this
    private fun String?.isAssertEqualsCommand(): Boolean = "assertEquals" == this
    private fun String?.isAssertNotEqualsCommand(): Boolean = "assertNotEquals" == this
    private fun String?.isAssertWithAiCommand(): Boolean = "assertWithAI" == this
    private fun String?.isAssertMathCommand(): Boolean = "assertMath" == this
    private fun String?.isMaestroCommand(): Boolean = this != null && maestroCommands.contains(this)
    private fun String?.isNoParamMaestroCommand(): Boolean = this != null && noParamMaestroCommands.contains(this)
  }
}
