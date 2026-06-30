package com.example.demo;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc JSON 响应读取工具.
 */
final class JsonTestSupport {

    private JsonTestSupport() {
    }

    static String readString(MvcResult result, String expression) throws Exception {
        String json = result.getResponse().getContentAsString();
        return JsonPath.read(json, expression);
    }
}
