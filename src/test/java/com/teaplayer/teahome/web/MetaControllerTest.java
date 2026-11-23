package com.teaplayer.teahome.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.teaplayer.teahome.auth.ApiTokenService;
import com.teaplayer.teahome.auth.TokenAuthFilter;
import com.teaplayer.teahome.auth.TokenPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({MetaController.class, HealthController.class})
@Import(TokenAuthFilter.class)
class MetaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiTokenService tokenService;

    @MockitoBean
    private SchemaVersionProvider schemaVersionProvider;

    @BeforeEach
    void setUp() {
        when(schemaVersionProvider.schemaVersion()).thenReturn("2");
        when(tokenService.authenticate(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/v1/meta"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void rejectsUnknownToken() throws Exception {
        mockMvc.perform(get("/v1/meta").header("X-TeaHome-Token", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidToken() throws Exception {
        when(tokenService.authenticate(eq("good-token"), anyString()))
                .thenReturn(Optional.of(principal("read")));

        mockMvc.perform(get("/v1/meta").header("X-TeaHome-Token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiVersions[0]").value("v1"))
                .andExpect(jsonPath("$.tokenRequired").value(true))
                .andExpect(jsonPath("$.categoryKeys.length()").value(7))
                .andExpect(jsonPath("$.schemaVersion").value("2"))
                .andExpect(jsonPath("$.serverTime").isNotEmpty());
    }

    @Test
    void acceptsBearerToken() throws Exception {
        when(tokenService.authenticate(eq("good-token"), anyString()))
                .thenReturn(Optional.of(principal("admin")));

        mockMvc.perform(get("/v1/meta")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointIsNotBehindTokenGate() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static TokenPrincipal principal(String... scopes) {
        return new TokenPrincipal(1L, "test", scopes, "active", null);
    }
}
