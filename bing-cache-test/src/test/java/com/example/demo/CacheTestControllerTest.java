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


    @Test
    void shouldExposeMultiCacheEvictScenario() throws Exception {
        mockMvc.perform(get("/cache-test/evict/multi-cache")
                        .param("userId", "201")
                        .param("name", "Carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除动作", equalTo("updateUserAccount")))
                .andExpect(jsonPath("$.账号缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.账号缓存.清除后是否重算", equalTo(true)))
                .andExpect(jsonPath("$.订单缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.订单缓存.清除后是否重算", equalTo(true)));
    }

    @Test
    void shouldExposeGroupEvictScenario() throws Exception {
        mockMvc.perform(get("/cache-test/evict/group")
                        .param("id", "301")
                        .param("dictType", "type1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除动作", equalTo("clearAdminGroup")))
                .andExpect(jsonPath("$.管理员用户缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.管理员用户缓存.清除后是否重算", equalTo(true)))
                .andExpect(jsonPath("$.管理员字典缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.管理员字典缓存.清除后是否重算", equalTo(true)));
    }

    @Test
    void shouldExposeGlobalClearScenario() throws Exception {
        mockMvc.perform(get("/cache-test/evict/all")
                        .param("userId", "401")
                        .param("dictType", "global-type"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.清除动作", equalTo("clearAll")))
                .andExpect(jsonPath("$.用户缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.用户缓存.清除后是否重算", equalTo(true)))
                .andExpect(jsonPath("$.字典缓存.清除前是否命中", equalTo(true)))
                .andExpect(jsonPath("$.字典缓存.清除后是否重算", equalTo(true)));
    }

    @Test
    void shouldListEvictEndpointsInStatus() throws Exception {
        mockMvc.perform(get("/cache-test/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.测试接口.单key清除", equalTo("GET /cache-test/evict/user?id=1&name=Alice")))
                .andExpect(jsonPath("$.测试接口.keyPrefix清除", equalTo("GET /cache-test/evict/dict?dictType=gender&value=new")))
                .andExpect(jsonPath("$.测试接口.SpEL清除", equalTo("GET /cache-test/evict/user-detail?id=1&source=app&name=Alice")))
                .andExpect(jsonPath("$.测试接口.多缓存协同清除", equalTo("GET /cache-test/evict/multi-cache?userId=1&name=Alice")))
                .andExpect(jsonPath("$.测试接口.分组清除", equalTo("GET /cache-test/evict/group?id=1&dictType=type1")))
                .andExpect(jsonPath("$.测试接口.全局清空", equalTo("GET /cache-test/evict/all?userId=1&dictType=type1")));
    }

}
