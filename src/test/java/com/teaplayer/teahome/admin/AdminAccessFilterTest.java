package com.teaplayer.teahome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAccessFilterTest {

    private AdminSessionService sessions;
    private AdminAccessFilter filter;

    @BeforeEach
    void setUp() {
        sessions = mock(AdminSessionService.class);
        when(sessions.resolve(isNull())).thenReturn(Optional.empty());
        filter = new AdminAccessFilter(sessions);
    }

    @Test
    void rejectsNonLoopbackHost() throws Exception {
        MockHttpServletRequest request = request("/admin");
        request.addHeader("Host", "evil.example:18081");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(421);
    }

    @Test
    void rejectsNonLoopbackPeer() throws Exception {
        MockHttpServletRequest request = request("/admin");
        request.addHeader("Host", "127.0.0.1:18081");
        request.setRemoteAddr("192.168.5.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void redirectsAnonymousToLogin() throws Exception {
        MockHttpServletRequest request = request("/admin");
        request.addHeader("Host", "127.0.0.1:18081");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login");
    }

    @Test
    void allowsLoginPageWithoutSession() throws Exception {
        MockHttpServletRequest request = request("/admin/login");
        request.addHeader("Host", "127.0.0.1:18081");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(18081);
        request.setRequestURI(uri);
        return request;
    }
}
