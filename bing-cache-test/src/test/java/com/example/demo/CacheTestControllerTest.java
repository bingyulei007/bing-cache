package com.example.demo;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CacheTestController 手动接口测试.
 */
@SpringBootTest(properties = "bing.cache.redis.enabled=false")
@AutoConfigureMockMvc
class CacheTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeUserEvictScenario() throws Exception {
        MvcResult result = mockMvc.perform(get("/cache-test/evict/user")
                        .param("id", "101")
                        .param("name", "Alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除方法", equalTo("updateUser")))
                .andExpect(jsonPath("$.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.清除后是否重算", equalTo(true)))
                .andReturn();

        String first = JsonTestSupport.readString(result, "$.第一次");
        String second = JsonTestSupport.readString(result, "$.第二次");
        String afterEvict = JsonTestSupport.readString(result, "$.清除后");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertNotEquals(second, afterEvict);
    }

    @Test
    void shouldExposeDictEvictScenario() throws Exception {
        MvcResult result = mockMvc.perform(get("/cache-test/evict/dict")
                        .param("dictType", "gender")
                        .param("value", "new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除方法", equalTo("updateDict")))
                .andExpect(jsonPath("$.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.清除后是否重算", equalTo(true)))
                .andReturn();

        String first = JsonTestSupport.readString(result, "$.第一次");
        String second = JsonTestSupport.readString(result, "$.第二次");
        String afterEvict = JsonTestSupport.readString(result, "$.清除后");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertNotEquals(second, afterEvict);
    }

    @Test
    void shouldExposeUserDetailSpelEvictScenario() throws Exception {
        MvcResult result = mockMvc.perform(get("/cache-test/evict/user-detail")
                        .param("id", "102")
                        .param("source", "app")
                        .param("name", "Bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除方法", equalTo("updateUserDetail")))
                .andExpect(jsonPath("$.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.清除后是否重算", equalTo(true)))
                .andReturn();

        String first = JsonTestSupport.readString(result, "$.第一次");
        String second = JsonTestSupport.readString(result, "$.第二次");
        String afterEvict = JsonTestSupport.readString(result, "$.清除后");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertNotEquals(second, afterEvict);
    }
}
