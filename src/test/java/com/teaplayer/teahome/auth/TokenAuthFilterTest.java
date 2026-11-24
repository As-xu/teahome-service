package com.teaplayer.teahome.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TokenAuthFilterTest {

    private final TokenAuthFilter filter = new TokenAuthFilter(mock(ApiTokenService.class));

    @Test
    void gatesV1PathsIncludingPercentEncodedOnes() {
        assertFalse(filter.shouldNotFilter(request("/v1/feed")));
        assertFalse(filter.shouldNotFilter(request("/%76%31/feed")));
        assertFalse(filter.shouldNotFilter(request("/v1")));
        assertFalse(filter.shouldNotFilter(request("/v1;x/feed")));
        assertFalse(filter.shouldNotFilter(request("/v1;a=b/feed")));
        assertFalse(filter.shouldNotFilter(request("/v1;/feed")));
        assertFalse(filter.shouldNotFilter(request("/v1/feed;x")));
    }

    @Test
    void leavesHealthAndActuatorOpen() {
        assertTrue(filter.shouldNotFilter(request("/health")));
        assertTrue(filter.shouldNotFilter(request("/actuator/health")));
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
