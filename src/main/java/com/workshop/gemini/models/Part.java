package com.workshop.gemini.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;


@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Part {

    public String text;
    public FunctionCall functionCall;
    public FunctionResponse functionResponse;
    public InlineData inlineData;
    public String thoughtSignature;

    public Part() {}

    public Part(String text) {
        this.text = text;
    }

    public static Part ofFunctionCall(String name, JsonNode args) {
        Part p = new Part(null);
        p.functionCall = new FunctionCall(name, args);
        return p;
    }

    // Gemini 3.x returns thoughtSignature as a sibling of functionCall on the
    // part, and rejects follow-up turns (400) unless it is echoed back there.
    public static Part ofFunctionCall(String name, JsonNode args, String thoughtSignature) {
        Part p = ofFunctionCall(name, args);
        p.thoughtSignature = thoughtSignature;
        return p;
    }

    public static Part ofFunctionResponse(String name, String result) {
        Part p = new Part(null);
        p.functionResponse = new FunctionResponse(name, result);
        return p;
    }



    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        public String name;
        public JsonNode args;

        public FunctionCall() {}

        public FunctionCall(String name, JsonNode args) {
            this.name = name;
            this.args = args;
        }
    }

    

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionResponse {
        public String name;
        public FunctionResponseBody response;
        

        public FunctionResponse() {}

        public FunctionResponse(String name, String result) {
            this.name = name;
            this.response = new FunctionResponseBody(result);
        }
    }

    public static class FunctionResponseBody {
        public String result;

        public FunctionResponseBody() {}

        public FunctionResponseBody(String result) {
            this.result = result;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InlineData {
        public String data;
    }
}
