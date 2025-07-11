package xyz.block.trailblaze.examples.clock

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import maestro.orchestra.BackPressCommand
import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.InstrumentationArgUtil

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = AndroidTrailblazeRule(
    llmClient = OpenAILLMClient(
      apiKey = InstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
      settings = OpenAIClientSettings(
        baseUrl = InstrumentationArgUtil.getBaseUrlFromInstrumentationArg()
      )
    ),
    llmModel = OpenAIModels.Chat.GPT4_1,
  )

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.airbnb.android",
        stopApp = false,
        clearState = false,
      )
    )
  }

  @Test
  fun setAnAlarm() {
    trailblazeRule.prompt(
      """
      - search for Portland
      """.trimIndent()
    )
    trailblazeRule.maestroCommands(
      BackPressCommand()
    )
  }

}
