package io.agents.arya.tool

import io.agents.arya.agent.llm.ToolSpec
import org.json.JSONObject

fun BaseTool.toJsonSchema(): String {
    val props = JSONObject()
    val required = org.json.JSONArray()
    for (p in getParametersWithWaitAfter()) {
        props.put(
            p.name,
            JSONObject().apply {
                put("type", p.type)
                put("description", p.description)
            },
        )
        if (p.isRequired) required.put(p.name)
    }
    return JSONObject().apply {
        put("type", "object")
        put("properties", props)
        put("required", required)
    }.toString()
}

fun ToolRegistry.toToolSpecs(): List<ToolSpec> =
    getAllTools().map { tool ->
        ToolSpec(
            name = tool.getName(),
            descriptionFa = tool.getDescription(),
            paramsJsonSchema = tool.toJsonSchema(),
        )
    }
