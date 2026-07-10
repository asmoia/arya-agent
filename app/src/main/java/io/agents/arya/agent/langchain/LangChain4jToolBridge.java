// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.langchain;

import io.agents.arya.tool.BaseTool;
import io.agents.arya.tool.ToolParameter;
import io.agents.arya.tool.ToolRegistry;
import io.agents.arya.tool.ToolResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges our custom tool abstraction to LangChain4j's tool system.
 * Converts BaseTool instances into LangChain4j ToolSpecifications and handles execution.
 */
public class LangChain4jToolBridge {

    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, List<ToolSpecification>> SPEC_CACHE = new ConcurrentHashMap<>();

    /** Clears cached schemas after ToolRegistry changes. */
    public static void invalidateCache() {
        SPEC_CACHE.clear();
    }

    /**
     * Builds LangChain4j ToolSpecification list from all registered tools.
     */
    public static List<ToolSpecification> buildToolSpecifications() {
        return buildToolSpecifications(null);
    }

    /**
     * Build only a task-relevant subset when [allowedToolNames] is non-null.
     * On-device function-calling models pay an appreciable first-token cost for
     * every JSON schema. Keeping the full list for cloud models but using a
     * conservative subset for local UI work reduces latency without changing
     * what the registry is allowed to execute.
     */
    public static List<ToolSpecification> buildToolSpecifications(Set<String> allowedToolNames) {
        final String cacheKey;
        if (allowedToolNames == null) {
            cacheKey = "all";
        } else {
            cacheKey = String.join("|", new TreeSet<>(allowedToolNames));
        }
        List<ToolSpecification> cached = SPEC_CACHE.get(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        List<ToolSpecification> specs = new ArrayList<>();
        for (BaseTool tool : ToolRegistry.getInstance().getAllTools()) {
            if (allowedToolNames != null && !allowedToolNames.contains(tool.getName())) continue;
            specs.add(toSpecification(tool));
        }
        SPEC_CACHE.put(cacheKey, new ArrayList<>(specs));
        return specs;
    }

    /**
     * Converts a BaseTool to a LangChain4j ToolSpecification.
     */
    private static ToolSpecification toSpecification(BaseTool tool) {
        List<ToolParameter> params = tool.getParametersWithWaitAfter();

        if (params.isEmpty()) {
            return ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .build();
        }

        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter param : params) {
            JsonSchemaElement schema;
            switch (param.getType()) {
                case "integer":
                    schema = JsonIntegerSchema.builder()
                            .description(param.getDescription())
                            .build();
                    break;
                case "number":
                    schema = JsonNumberSchema.builder()
                            .description(param.getDescription())
                            .build();
                    break;
                case "boolean":
                    schema = JsonBooleanSchema.builder()
                            .description(param.getDescription())
                            .build();
                    break;
                case "string":
                default:
                    schema = JsonStringSchema.builder()
                            .description(param.getDescription())
                            .build();
                    break;
            }
            properties.put(param.getName(), schema);
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        JsonObjectSchema parametersSchema = JsonObjectSchema.builder()
                .addProperties(properties)
                .required(required)
                .build();

        return ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .parameters(parametersSchema)
                .build();
    }

    /**
     * Executes a LangChain4j ToolExecutionRequest using our tool registry.
     */
    public static String executeToolRequest(ToolExecutionRequest request) {
        String toolName = request.name();
        String argsJson = request.arguments();

        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> params;
        try {
            params = GSON.fromJson(argsJson, mapType);
        } catch (Exception e) {
            android.util.Log.w("ToolBridge", "Failed to parse tool args for " + toolName + ": " + argsJson, e);
            params = new HashMap<>();
        }
        if (params == null) {
            params = new HashMap<>();
        }

        ToolResult result = ToolRegistry.getInstance().executeTool(toolName, params);
        return GSON.toJson(result);
    }
}
