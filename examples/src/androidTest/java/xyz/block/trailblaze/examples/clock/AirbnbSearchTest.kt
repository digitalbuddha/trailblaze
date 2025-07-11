package xyz.block.trailblaze.examples.clock

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.openai.OpenAiTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class AirbnbSearchTest {

  @get:Rule
  val trailblazeRule = OpenAiTrailblazeRule(
    llmModel = OpenAIModels.Chat.GPT4_1
  )

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.airbnb.android.development",
        stopApp = true,
        clearState = true,
      )
    )
  }

  @Test
  fun searchForPortland() {
    trailblazeRule.prompt(
      """
      - open the airbnb app
      - close login if it is open
      - start your search and search for portland listings for the month of september 
      - pick any date
      - add 1 adult and 2 children
      - start the search
      - make sure search completes and you see results from portland
      """.trimIndent()
    )
  }
}
