package com.teaplayer.teahome.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminCsrfFilterTest {

    private AdminCsrfFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminCsrfFilter();
    }

    @Test
    void allowsLoginPostWithoutOriginOrReferer() throws Exception {
        MockHttpServletRequest request = post("/admin/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsMismatchedOrigin() throws Exception {
        MockHttpServletRequest request = post("/admin/login");
        request.addHeader("Origin", "http://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Bad Origin").contains("操作未完成");
    }

    @Test
    void stillRequiresTokenWhenOriginIsAbsent() throws Exception {
        MockHttpServletRequest request = post("/admin/maintenance/images/gc");
        request.setAttribute(AdminAttributes.SESSION, session());
        request.setAttribute(AdminAttributes.CSRF, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF token missing or invalid");
    }

    @Test
    void acceptsMatchingOriginAndToken() throws Exception {
        MockHttpServletRequest request = post("/admin/maintenance/images/gc");
        request.addHeader("Origin", "http://127.0.0.1:18081");
        request.addParameter("_csrf", "expected-token");
        request.setAttribute(AdminAttributes.SESSION, session());
        request.setAttribute(AdminAttributes.CSRF, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void ignoresSafeMethods() throws Exception {
        MockHttpServletRequest request = request("/admin/maintenance", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest post(String path) {
        return request(path, "POST");
    }

    private static MockHttpServletRequest request(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(18081);
        request.addHeader("Host", "127.0.0.1:18081");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static AdminSessionInfo session() {
        Instant now = Instant.now();
        return new AdminSessionInfo(UUID.randomUUID(), 1L, "admin", "管理员", "owner",
                "hash", now.plusSeconds(3600), now, now.plusSeconds(300));
    }
}
