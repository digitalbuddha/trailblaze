package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.Tool

/**
 * A marker interface for all Trailblaze commands.
 *
 * All Trailblaze commands should implement this interface and be @[kotlinx.serialization.Serializable].
 *
 * [ai.koog.agents.core.tools.Tool.Args] from Koog is extended to allow us to use existing [TrailblazeTool] types as koog tool arguments.
 */
interface TrailblazeTool : Tool.Args
